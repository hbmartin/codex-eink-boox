package me.haroldmartin.codexeink.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalManagedRelayApi::class)
class ConnectionTest {
    @Test
    fun `request correlates response and does not emit it as unsolicited`() = runBlocking {
        val connection = FakeConnection()
        val pending = async { connection.request("thread/list", timeoutMillis = 1_000) }
        while (connection.sent.isEmpty()) yield()
        val request = connection.sent.single() as JsonRpcRequest
        connection.deliver(JsonRpcResponse(id = request.id, result = kotlinx.serialization.json.JsonNull))

        assertTrue(pending.await().isSuccess)
    }

    @Test
    fun `blocked managed compatibility fails before headers or network`() {
        var headersRequested = false
        val connection = ManagedRelayConnection(
            ManagedRelayConfig(
                endpoint = "wss://example.invalid/controller",
                clientId = "client",
                streamId = "stream",
                headersProvider = ConnectionHeadersProvider {
                    headersRequested = true
                    emptyMap()
                },
                compatibility = RelayCompatibility.Blocked("controller enrollment is undocumented"),
            ),
        )

        assertThrows(RelayCompatibilityException::class.java) {
            runBlocking { connection.connect() }
        }
        assertEquals(false, headersRequested)
        assertTrue(connection.state.value is ConnectionState.Failed)
    }

    @Test
    fun `event received before collector starts is delivered once collector subscribes`() = runBlocking {
        val connection = FakeConnection()
        connection.deliver(JsonRpcNotification(method = "turn/completed"))

        val event = withTimeout(1_000) { connection.events.first() }

        assertTrue(event is ConnectionEvent.MessageReceived)
    }

    @Test
    fun `event queue overflow fails explicitly instead of silently dropping`() = runBlocking {
        val connection = FakeConnection()
        connection.connect()

        repeat(65) { index ->
            connection.deliver(JsonRpcNotification(method = "event/$index"))
        }

        assertTrue(connection.state.value is ConnectionState.Failed)
    }

    @Test
    fun `frame sending stops at first rejection and reports accepted prefix`() {
        val attempted = mutableListOf<String>()

        val result = sendFrames(listOf("one", "two", "three")) { frame ->
            attempted += frame
            frame != "two"
        }

        assertEquals(FrameSendResult(acceptedFrames = 1, totalFrames = 3), result)
        assertTrue(result.partial)
        assertEquals(listOf("one", "two"), attempted)
    }

    private class FakeConnection : BaseCodexConnection() {
        val sent = mutableListOf<JsonRpcMessage>()

        override suspend fun connect() {
            mutableState.value = ConnectionState.Connected()
        }

        override suspend fun disconnect(code: Int, reason: String) {
            mutableState.value = ConnectionState.Disconnected
        }

        override suspend fun send(message: JsonRpcMessage): Boolean {
            sent += message
            return true
        }

        fun deliver(message: JsonRpcMessage) = receive(message)
    }
}
