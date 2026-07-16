package me.haroldmartin.codexeink.protocol

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.haroldmartin.codexeink.Connectivity
import me.haroldmartin.codexeink.data.ConnectionProfile
import me.haroldmartin.codexeink.data.CredentialStore
import me.haroldmartin.codexeink.data.TransportMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolCodexControllerTest {
    private val fixtures = mutableListOf<Fixture>()

    @After
    fun tearDown() {
        fixtures.forEach(Fixture::close)
        fixtures.clear()
    }

    @Test
    fun `initialize notifies host and loads every thread page`() = runBlocking {
        val cursors = CopyOnWriteArrayList<String?>()
        val fixture = fixture { method, params ->
            when (method) {
                "initialize" -> success(emptyObject())
                "thread/list" -> {
                    val cursor = params.objectOrNull()?.string("cursor")
                    cursors += cursor
                    if (cursor == null) {
                        success(
                            json(
                                """
                                {
                                  "data": [
                                    {"id":"thread-1","preview":"First","updatedAt":200}
                                  ],
                                  "nextCursor": "page-2"
                                }
                                """.trimIndent(),
                            ),
                        )
                    } else {
                        success(
                            json(
                                """
                                {
                                  "data": [
                                    {"id":"thread-2","preview":"Second","updatedAt":100},
                                    {"id":"thread-1","preview":"Duplicate","updatedAt":200}
                                  ],
                                  "nextCursor": null
                                }
                                """.trimIndent(),
                            ),
                        )
                    }
                }
                else -> error("Unexpected request: $method")
            }
        }

        fixture.controller.saveAndConnect(PROFILE)
        await("connected with paged task history") {
            fixture.controller.state.value.connectivity == Connectivity.Connected &&
                fixture.controller.state.value.threads.size == 2
        }

        assertEquals(listOf(null, "page-2"), cursors)
        assertEquals(listOf("thread-1", "thread-2"), fixture.controller.state.value.threads.map { it.id })
        assertEquals(listOf("initialize", "thread/list", "thread/list"), fixture.connection.requests.map { it.method })
        assertTrue(
            fixture.connection.sent.any {
                it is JsonRpcNotification && it.method == "initialized"
            },
        )
        val initialize = fixture.connection.requests.first().params.objectOrNull()
        assertEquals("codex_eink", initialize?.get("clientInfo").objectOrNull()?.string("name"))
        assertEquals(
            "true",
            initialize?.get("capabilities")
                .objectOrNull()
                ?.get("experimentalApi")
                ?.jsonPrimitive
                ?.contentOrNull,
        )
    }

    @Test
    fun `thread list failure after initialization leaves transport connected`() = runBlocking {
        val fixture = fixture { method, _ ->
            when (method) {
                "initialize" -> success(emptyObject())
                "thread/list" -> JsonRpcResponse(
                    id = JsonRpcId.NumberId(2),
                    error = JsonRpcError(code = -32_000, message = "history unavailable"),
                )
                else -> error("Unexpected request: $method")
            }
        }

        fixture.controller.saveAndConnect(PROFILE)
        await("post-initialize history error") {
            fixture.controller.state.value.error?.contains("history unavailable") == true
        }

        assertEquals(Connectivity.Connected, fixture.controller.state.value.connectivity)
        assertEquals(ConnectionState.Connected("session-1"), fixture.connection.state.value)
        assertTrue(fixture.controller.state.value.error.orEmpty().startsWith("Connected, but task history"))
    }

    @Test
    fun `completion arriving before turn start response does not resurrect active turn`() = runBlocking {
        lateinit var fixture: Fixture
        fixture = fixture { method, _ ->
            when (method) {
                "initialize" -> success(emptyObject())
                "thread/list" -> success(threadList())
                "thread/resume" -> success(resumedThread())
                "turn/start" -> {
                    fixture.connection.emit(
                        JsonRpcNotification(
                            method = "turn/completed",
                            params = json(
                                """
                                {
                                  "threadId": "thread-1",
                                  "turn": {"id":"turn-fast","status":"completed"}
                                }
                                """.trimIndent(),
                            ),
                        ),
                    )
                    // Let the controller consume the notification before the request response is
                    // returned, reproducing the app-server race this test protects against.
                    delay(100)
                    success(json("""{"turn":{"id":"turn-fast"}}"""))
                }
                else -> error("Unexpected request: $method")
            }
        }
        fixture.connectAndSelect()

        fixture.controller.send("Run quickly")

        assertFalse(fixture.controller.state.value.activeTurn)
        assertNull(fixture.controller.state.value.error)
        assertEquals("turn/start", fixture.connection.requests.last().method)
    }

    @Test
    fun `reconnecting clears approvals and questions from the previous session`() = runBlocking {
        val fixture = connectedSelectedFixture()
        fixture.connection.emit(commandApproval(JsonRpcId.NumberId(7)))
        fixture.connection.emit(question(JsonRpcId.StringId("question-1")))
        await("both pending server prompts") {
            fixture.controller.state.value.pendingApproval != null &&
                fixture.controller.state.value.pendingQuestion != null
        }

        fixture.connection.transition(
            ConnectionState.Reconnecting(attempt = 1, delayMillis = 1_000, reason = "network changed"),
        )
        await("reconnecting state with cleared prompts") {
            val state = fixture.controller.state.value
            state.connectivity == Connectivity.Reconnecting &&
                state.pendingApproval == null &&
                state.pendingQuestion == null
        }

        assertFalse(fixture.controller.state.value.activeTurn)
        assertEquals("network changed", fixture.controller.state.value.error)

        fixture.connection.transition(ConnectionState.Connected("session-2"))
        await("reinitialized session") {
            fixture.controller.state.value.connectivity == Connectivity.Connected &&
                fixture.connection.requests.count { it.method == "initialize" } == 2
        }
        assertNull(fixture.controller.state.value.pendingApproval)
        assertNull(fixture.controller.state.value.pendingQuestion)
    }

    @Test
    fun `numeric-looking string request id remains quoted in approval response`() = runBlocking {
        val fixture = connectedSelectedFixture()
        fixture.connection.emit(commandApproval(JsonRpcId.StringId("42")))
        await("string-id approval") {
            fixture.controller.state.value.pendingApproval?.requestId == "s:42"
        }

        fixture.controller.answerApproval(requestId = "s:42", decision = "accept")

        val response = fixture.connection.sent.filterIsInstance<JsonRpcResponse>().last()
        assertEquals(JsonRpcId.StringId("42"), response.id)
        assertTrue(JsonRpcCodec().encode(response).contains("\"id\":\"42\""))
        assertNull(fixture.controller.state.value.pendingApproval)
    }

    @Test
    fun `streaming deltas remain hidden until one coalesced 500ms flush`() = runBlocking {
        val fixture = connectedSelectedFixture()
        val startNanos = System.nanoTime()
        fixture.connection.emit(agentDelta("Hel"))
        fixture.connection.emit(agentDelta("lo"))
        // A later notification gives the test an observable event-queue barrier: when marker-1
        // appears, both earlier deltas have reached appendDelta and the flush timer is running.
        fixture.connection.emit(
            JsonRpcNotification(
                method = "thread/started",
                params = json(
                    """
                    {"thread":{"id":"marker-1","preview":"Marker","updatedAt":1}}
                    """.trimIndent(),
                ),
            ),
        )
        await("delta queue barrier") {
            fixture.controller.state.value.threads.any { it.id == "marker-1" }
        }

        val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000
        delay((300L - elapsedMillis).coerceAtLeast(0L))
        assertNull(fixture.controller.state.value.timeline.firstOrNull { it.id == "agent-stream" })

        await("coalesced delta flush", timeoutMillis = 2_000) {
            fixture.controller.state.value.timeline.firstOrNull { it.id == "agent-stream" }?.body == "Hello"
        }
        val streamed = fixture.controller.state.value.timeline.filter { it.id == "agent-stream" }
        assertEquals(1, streamed.size)
        assertEquals("Hello", streamed.single().body)
    }

    private fun fixture(
        handler: suspend FakeConnection.(String, JsonElement?) -> JsonRpcResponse,
    ): Fixture {
        val dispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "controller-test").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val connection = FakeConnection().apply { requestHandler = handler }
        val credentials = FakeCredentialStore()
        val result = Fixture(
            controller = ProtocolCodexController(
                credentialStore = credentials,
                scope = scope,
                connectionFactory = CodexConnectionFactory { connection },
            ),
            connection = connection,
            credentials = credentials,
            scope = scope,
            dispatcher = dispatcher,
        )
        fixtures += result
        return result
    }

    private fun connectedSelectedFixture(): Fixture {
        val fixture = fixture { method, _ ->
            when (method) {
                "initialize" -> success(emptyObject())
                "thread/list" -> success(threadList())
                "thread/resume" -> success(resumedThread())
                else -> error("Unexpected request: $method")
            }
        }
        runBlocking { fixture.connectAndSelect() }
        return fixture
    }

    private suspend fun Fixture.connectAndSelect() {
        controller.saveAndConnect(PROFILE)
        await("initial task list") {
            controller.state.value.connectivity == Connectivity.Connected &&
                controller.state.value.threads.any { it.id == "thread-1" }
        }
        controller.selectThread("thread-1")
        await("selected task") { controller.state.value.selectedThreadId == "thread-1" }
    }

    private suspend fun await(
        description: String,
        timeoutMillis: Long = 3_000,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (!condition()) {
            if (System.nanoTime() >= deadline) {
                throw AssertionError("Timed out waiting for $description")
            }
            delay(10)
        }
    }

    private data class Fixture(
        val controller: ProtocolCodexController,
        val connection: FakeConnection,
        val credentials: FakeCredentialStore,
        val scope: CoroutineScope,
        val dispatcher: kotlinx.coroutines.ExecutorCoroutineDispatcher,
    ) {
        fun close() {
            scope.cancel()
            dispatcher.close()
        }
    }

    private class FakeCredentialStore : CredentialStore {
        var stored: ConnectionProfile? = null

        override fun hasStoredProfile(): Boolean = stored != null

        override fun read(): ConnectionProfile? = stored

        override fun write(profile: ConnectionProfile) {
            stored = profile
        }

        override fun clear() {
            stored = null
        }
    }

    private class FakeConnection : CodexConnection {
        private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
        private val mutableEvents = MutableSharedFlow<ConnectionEvent>(extraBufferCapacity = 32)

        override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
        override val events: SharedFlow<ConnectionEvent> = mutableEvents.asSharedFlow()

        val requests = CopyOnWriteArrayList<RequestCall>()
        val sent = CopyOnWriteArrayList<JsonRpcMessage>()
        lateinit var requestHandler: suspend FakeConnection.(String, JsonElement?) -> JsonRpcResponse

        override suspend fun connect() {
            mutableState.value = ConnectionState.Connected("session-1")
        }

        override suspend fun disconnect(code: Int, reason: String) {
            mutableState.value = ConnectionState.Disconnected
        }

        override suspend fun send(message: JsonRpcMessage): Boolean {
            sent += message
            return true
        }

        override suspend fun request(
            method: String,
            params: JsonElement?,
            timeoutMillis: Long,
        ): JsonRpcResponse {
            requests += RequestCall(method, params)
            return requestHandler(method, params)
        }

        suspend fun emit(message: JsonRpcMessage) {
            mutableEvents.emit(ConnectionEvent.MessageReceived(message))
        }

        fun transition(state: ConnectionState) {
            mutableState.value = state
        }
    }

    private data class RequestCall(
        val method: String,
        val params: JsonElement?,
    )

    private companion object {
        val PROFILE = ConnectionProfile(
            displayName = "Test host",
            endpoint = "wss://codex.example.test/app-server",
            credential = "test-capability",
            mode = TransportMode.DirectDiagnostic,
        )

        val JSON = Json { ignoreUnknownKeys = true }

        fun json(value: String): JsonElement = JSON.parseToJsonElement(value)

        fun emptyObject(): JsonObject = buildJsonObject { }

        fun success(result: JsonElement): JsonRpcResponse = JsonRpcResponse(
            id = JsonRpcId.NumberId(1),
            result = result,
        )

        fun threadList(): JsonElement = json(
            """
            {"data":[{"id":"thread-1","preview":"Test task","updatedAt":100}]}
            """.trimIndent(),
        )

        fun resumedThread(): JsonElement = json(
            """
            {
              "thread": {
                "id": "thread-1",
                "preview": "Test task",
                "status": "idle",
                "updatedAt": 100,
                "turns": []
              }
            }
            """.trimIndent(),
        )

        fun commandApproval(id: JsonRpcId): JsonRpcRequest = JsonRpcRequest(
            id = id,
            method = "item/commandExecution/requestApproval",
            params = json(
                """
                {
                  "threadId": "thread-1",
                  "itemId": "command-1",
                  "command": "./gradlew test",
                  "availableDecisions": ["decline", "accept"]
                }
                """.trimIndent(),
            ),
        )

        fun question(id: JsonRpcId): JsonRpcRequest = JsonRpcRequest(
            id = id,
            method = "item/tool/requestUserInput",
            params = json(
                """
                {
                  "threadId": "thread-1",
                  "questions": [
                    {"id":"scope","question":"Which scope?","options":["App"]}
                  ]
                }
                """.trimIndent(),
            ),
        )

        fun agentDelta(delta: String): JsonRpcNotification = JsonRpcNotification(
            method = "item/agentMessage/delta",
            params = buildJsonObject {
                put("threadId", "thread-1")
                put("itemId", "agent-stream")
                put("delta", JsonPrimitive(delta))
            },
        )

        private fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

        private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
    }
}
