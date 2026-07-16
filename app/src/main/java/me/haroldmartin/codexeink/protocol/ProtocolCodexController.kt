package me.haroldmartin.codexeink.protocol

import java.io.IOException
import java.net.URI
import java.util.LinkedHashSet
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.haroldmartin.codexeink.ApprovalUi
import me.haroldmartin.codexeink.BuildConfig
import me.haroldmartin.codexeink.CodexController
import me.haroldmartin.codexeink.CodexUiState
import me.haroldmartin.codexeink.Connectivity
import me.haroldmartin.codexeink.QuestionUi
import me.haroldmartin.codexeink.ThreadUi
import me.haroldmartin.codexeink.TimelineKind
import me.haroldmartin.codexeink.TimelineUi
import me.haroldmartin.codexeink.data.ConnectionProfile
import me.haroldmartin.codexeink.data.CredentialStore
import me.haroldmartin.codexeink.data.TransportMode

/**
 * Adapts the transport-neutral protocol module to the small, e-ink-oriented UI state.
 *
 * Managed Remote intentionally remains compatibility-gated. The public Codex source describes the
 * host relay envelopes, but it does not currently publish a legitimate independent controller
 * enrollment/authentication contract. Direct diagnostic mode talks to an authenticated
 * `codex app-server --listen` WebSocket instead.
 */
class ProtocolCodexController(
    private val credentialStore: CredentialStore,
    private val scope: CoroutineScope,
    private val connectionFactory: CodexConnectionFactory = DefaultCodexConnectionFactory,
) : CodexController {
    private val mutableState = MutableStateFlow(CodexUiState())
    override val state: StateFlow<CodexUiState> = mutableState.asStateFlow()

    private val lifecycleMutex = Mutex()
    private val pendingRequests = ConcurrentHashMap<String, PendingServerRequest>()
    private val activeTurns = ConcurrentHashMap<String, String>()
    private val completedTurnMonitor = Any()
    private val recentlyCompletedTurnIds = LinkedHashSet<String>()
    private val deltaMonitor = Any()
    private val pendingDeltas = mutableMapOf<DeltaKey, PendingDelta>()

    @Volatile
    private var connection: CodexConnection? = null

    @Volatile
    private var profile: ConnectionProfile? = null

    @Volatile
    private var initializedSessionId: String? = null

    @Volatile
    private var protocolHandshakeFailed = false

    private var connectionStateJob: Job? = null
    private var connectionEventsJob: Job? = null
    private var deltaFlushJob: Job? = null

    override suspend fun connectStored() {
        val stored = try {
            credentialStore.read()
        } catch (_: Throwable) {
            mutableState.update {
                it.copy(
                    connectivity = Connectivity.Failed,
                    connectionMessage = "Saved credentials could not be opened.",
                    error = "Forget this host and enter a new capability token.",
                )
            }
            return
        }
        if (stored == null) {
            mutableState.update {
                it.copy(
                    connectivity = Connectivity.Disconnected,
                    connectionMessage = "Set up a host to connect.",
                    environmentName = null,
                )
            }
            return
        }
        runUserAction("Unable to connect to the saved host") {
            connectProfile(stored)
        }
    }

    override suspend fun saveAndConnect(profile: ConnectionProfile) {
        runUserAction("Unable to save this connection") {
            validateProfile(profile)
            credentialStore.write(profile.copy(credential = profile.credential.trim()))
            connectProfile(profile.copy(credential = profile.credential.trim()))
        }
    }

    override suspend fun pair(pairingCode: String) {
        if (pairingCode.isBlank()) {
            showError("Enter a valid Codex Remote pairing code.")
            return
        }
        // Do not persist or exchange pairing material until an independent controller contract is
        // verified. Guessing an endpoint or repurposing a first-party token would be unsafe.
        closeConnection()
        mutableState.update {
            it.copy(
                connectivity = Connectivity.Incompatible,
                connectionMessage = MANAGED_REMOTE_GATE_MESSAGE,
                environmentName = null,
                loading = false,
                error = MANAGED_REMOTE_GATE_DETAIL,
            )
        }
    }

    override suspend fun refreshThreads() {
        runLoadingAction("Unable to refresh tasks") {
            refreshThreadsInternal(requireReadyConnection())
        }
    }

    override suspend fun selectThread(threadId: String) {
        runLoadingAction("Unable to open this task") {
            require(threadId.isNotBlank()) { "Task id is empty" }
            val current = requireReadyConnection()
            val response = current.request(
                method = "thread/resume",
                params = buildJsonObject { put("threadId", threadId) },
            )
            val result = response.resultOrThrow("thread/resume")
            val parsed = ProtocolMapper.resumedThread(result)
                ?: throw IOException("Codex returned an invalid task payload")
            setActiveTurn(parsed.summary.id, parsed.activeTurnId)
            mutableState.update { state ->
                val threads = state.threads.upsert(parsed.summary).sortedByDescending { it.updatedAt ?: 0L }
                state.copy(
                    threads = threads,
                    selectedThreadId = parsed.summary.id,
                    timeline = parsed.timeline,
                    activeTurn = parsed.activeTurnId != null,
                    pendingApproval = firstPendingApproval(parsed.summary.id, parsed.timeline),
                    pendingQuestion = firstPendingQuestion(parsed.summary.id),
                    error = null,
                )
            }
        }
    }

    override suspend fun send(text: String) {
        val message = text.trim()
        if (message.isEmpty()) return
        runUserAction("Unable to send the message") {
            val current = requireReadyConnection()
            val threadId = mutableState.value.selectedThreadId
                ?: throw IllegalStateException("Open a task before sending a message")
            val turnId = activeTurns[threadId]
            val method = if (turnId == null) "turn/start" else "turn/steer"
            val response = current.request(
                method = method,
                params = buildJsonObject {
                    put("threadId", threadId)
                    put("clientUserMessageId", UUID.randomUUID().toString())
                    put(
                        "input",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", message)
                                },
                            )
                        },
                    )
                    turnId?.let { put("expectedTurnId", it) }
                },
            )
            val result = response.resultOrThrow(method).objectOrNull()
            val responseTurnId = if (turnId == null) {
                result?.get("turn").objectOrNull()?.string("id")
            } else {
                result?.string("turnId")
            }
            if (responseTurnId != null && !consumeRecentlyCompleted(responseTurnId)) {
                activeTurns[threadId] = responseTurnId
            }
            mutableState.update { state ->
                if (state.selectedThreadId == threadId) {
                    state.copy(
                        activeTurn = activeTurns[threadId] != null,
                        error = null,
                    )
                } else {
                    state
                }
            }
        }
    }

    override suspend fun interrupt() {
        runUserAction("Unable to stop this turn") {
            val current = requireReadyConnection()
            val threadId = mutableState.value.selectedThreadId
                ?: throw IllegalStateException("No task is open")
            val turnId = activeTurns[threadId] ?: throw IllegalStateException("No turn is running")
            current.request(
                method = "turn/interrupt",
                params = buildJsonObject {
                    put("threadId", threadId)
                    put("turnId", turnId)
                },
            ).resultOrThrow("turn/interrupt")
        }
    }

    override suspend fun answerApproval(requestId: String, decision: String) {
        runUserAction("Unable to answer the approval") {
            val pending = pendingRequests[requestId]
                ?: throw IllegalStateException("This approval is no longer pending")
            require(pending.approval != null) { "The pending request is not an approval" }
            require(pending.threadId == mutableState.value.selectedThreadId) {
                "Open the task that requested this approval before answering"
            }
            val result = approvalResult(pending, decision)
            val sent = requireReadyConnection().respond(id = pending.id, result = result)
            if (!sent) throw IOException("The connection closed before the approval was sent")
            pendingRequests.remove(requestId)
            refreshPendingPrompt()
        }
    }

    override suspend fun answerQuestion(requestId: String, answers: Map<String, String>) {
        runUserAction("Unable to answer Codex") {
            val pending = pendingRequests[requestId]
                ?: throw IllegalStateException("This question is no longer pending")
            val question = pending.question
                ?: throw IllegalStateException("The pending request is not a question")
            require(pending.threadId == mutableState.value.selectedThreadId) {
                "Open the task that asked this question before answering"
            }
            val expectedIds = question.questions.map { it.id }.toSet()
            require(expectedIds.all { answers[it]?.isNotBlank() == true }) {
                "Every question needs an answer"
            }
            val result = buildJsonObject {
                put(
                    "answers",
                    buildJsonObject {
                        expectedIds.forEach { questionId ->
                            put(
                                questionId,
                                buildJsonObject {
                                    put(
                                        "answers",
                                        buildJsonArray { add(JsonPrimitive(answers.getValue(questionId))) },
                                    )
                                },
                            )
                        }
                    },
                )
            }
            val sent = requireReadyConnection().respond(id = pending.id, result = result)
            if (!sent) throw IOException("The connection closed before the answer was sent")
            pendingRequests.remove(requestId)
            refreshPendingPrompt()
        }
    }

    override suspend fun disconnect(forgetDevice: Boolean) {
        closeConnection()
        if (forgetDevice) credentialStore.clear()
        mutableState.value = CodexUiState(
            connectivity = Connectivity.Disconnected,
            connectionMessage = if (forgetDevice) "Connection removed." else "Disconnected.",
            environmentName = if (forgetDevice) null else profile?.displayName,
        )
        if (forgetDevice) profile = null
    }

    private suspend fun connectProfile(requestedProfile: ConnectionProfile) {
        validateProfile(requestedProfile)
        if (requestedProfile.mode == TransportMode.ManagedRelay) {
            closeConnection()
            profile = requestedProfile
            mutableState.update {
                it.copy(
                    connectivity = Connectivity.Incompatible,
                    connectionMessage = MANAGED_REMOTE_GATE_MESSAGE,
                    environmentName = requestedProfile.displayName,
                    loading = false,
                    error = MANAGED_REMOTE_GATE_DETAIL,
                )
            }
            return
        }

        lifecycleMutex.withLock {
            val existing = connection
            if (
                profile == requestedProfile && existing != null &&
                !protocolHandshakeFailed &&
                existing.state.value !is ConnectionState.Disconnected &&
                existing.state.value !is ConnectionState.Failed
            ) {
                return
            }
            closeConnectionLocked()
            profile = requestedProfile
            initializedSessionId = null
            protocolHandshakeFailed = false
            activeTurns.clear()
            pendingRequests.clear()
            clearPendingDeltas()
            val created = connectionFactory.create(requestedProfile)
            connection = created
            observeConnection(created)
            mutableState.update {
                it.copy(
                    connectivity = Connectivity.Connecting,
                    connectionMessage = "Connecting to ${requestedProfile.displayName}…",
                    environmentName = requestedProfile.displayName,
                    loading = false,
                    error = null,
                )
            }
            try {
                created.connect()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showError("Connection attempt failed: ${safeMessage(error)}")
            }
        }
    }

    private fun observeConnection(observed: CodexConnection) {
        connectionStateJob = scope.launch {
            observed.state.collectLatest { connectionState ->
                if (connection !== observed) return@collectLatest
                when (connectionState) {
                    is ConnectionState.Disconnected -> mutableState.update {
                        if (protocolHandshakeFailed) {
                            it.copy(activeTurn = false, loading = false)
                        } else {
                            it.copy(
                                connectivity = Connectivity.Disconnected,
                                connectionMessage = "Disconnected.",
                                activeTurn = false,
                                loading = false,
                            )
                        }
                    }
                    is ConnectionState.Connecting -> {
                        initializedSessionId = null
                        mutableState.update {
                            it.copy(
                                connectivity = Connectivity.Connecting,
                                connectionMessage = "Connecting (attempt ${connectionState.attempt + 1})…",
                                loading = false,
                            )
                        }
                    }
                    is ConnectionState.Connected -> initializeConnectedSession(
                        observed,
                        connectionState.sessionId ?: "connected",
                    )
                    is ConnectionState.Reconnecting -> {
                        initializedSessionId = null
                        clearTransientSessionState()
                        mutableState.update {
                            it.copy(
                                connectivity = Connectivity.Reconnecting,
                                connectionMessage = "Connection lost; retrying in ${connectionState.delayMillis / 1_000 + 1}s…",
                                activeTurn = false,
                                loading = false,
                                error = connectionState.reason,
                            )
                        }
                    }
                    ConnectionState.Closing -> mutableState.update {
                        if (protocolHandshakeFailed) {
                            it.copy(activeTurn = false, loading = false)
                        } else {
                            it.copy(
                                connectivity = Connectivity.Disconnected,
                                connectionMessage = "Disconnecting…",
                                loading = false,
                            )
                        }
                    }
                    is ConnectionState.Failed -> {
                        initializedSessionId = null
                        mutableState.update {
                            it.copy(
                                connectivity = Connectivity.Failed,
                                connectionMessage = "Connection failed.",
                                activeTurn = false,
                                loading = false,
                                error = ProtocolRedactor.redact(connectionState.reason),
                            )
                        }
                    }
                }
            }
        }
        connectionEventsJob = scope.launch {
            observed.events.collect { event ->
                if (connection !== observed) return@collect
                when (event) {
                    is ConnectionEvent.MessageReceived -> handleMessage(observed, event.message)
                    is ConnectionEvent.ProtocolError -> showError(
                        "Protocol error: ${ProtocolRedactor.redact(event.summary)}",
                    )
                    is ConnectionEvent.TransportClosed -> showError(
                        "Host closed the connection (${event.code}).",
                    )
                }
            }
        }
    }

    private suspend fun initializeConnectedSession(current: CodexConnection, sessionId: String) {
        if (initializedSessionId == sessionId) return
        mutableState.update {
            it.copy(
                connectivity = Connectivity.Connecting,
                connectionMessage = "Initializing Codex protocol…",
                loading = false,
                error = null,
            )
        }
        try {
            current.request(
                method = "initialize",
                params = buildJsonObject {
                    put(
                        "clientInfo",
                        buildJsonObject {
                            put("name", "codex_eink")
                            put("title", "Codex Eink")
                            put("version", BuildConfig.VERSION_NAME)
                        },
                    )
                    put(
                        "capabilities",
                        buildJsonObject { put("experimentalApi", true) },
                    )
                },
            ).resultOrThrow("initialize")
            if (!current.notify("initialized")) {
                throw IOException("The host closed during initialization")
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (connection === current) {
                initializedSessionId = null
                protocolHandshakeFailed = true
                mutableState.update {
                    it.copy(
                        connectivity = Connectivity.Failed,
                        connectionMessage = "Codex protocol initialization failed.",
                        activeTurn = false,
                        loading = false,
                        error = safeMessage(error),
                    )
                }
                scope.launch { runCatching { current.disconnect(reason = "protocol initialization failed") } }
            }
            return
        }

        initializedSessionId = sessionId
        protocolHandshakeFailed = false
        mutableState.update {
            it.copy(
                connectivity = Connectivity.Connected,
                connectionMessage = "Connected.",
                environmentName = profile?.displayName,
                error = null,
            )
        }

        try {
            refreshThreadsInternal(current)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            showError("Connected, but task history could not be refreshed: ${safeMessage(error)}")
        }
        mutableState.value.selectedThreadId?.let { selectedId ->
            try {
                resumeAfterReconnect(current, selectedId)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showError("Connected, but the selected task could not be resumed: ${safeMessage(error)}")
            }
        }
    }

    private suspend fun resumeAfterReconnect(current: CodexConnection, threadId: String) {
        val parsed = ProtocolMapper.resumedThread(
            current.request(
                method = "thread/resume",
                params = buildJsonObject { put("threadId", threadId) },
            ).resultOrThrow("thread/resume"),
        ) ?: return
        setActiveTurn(parsed.summary.id, parsed.activeTurnId)
        mutableState.update { state ->
            if (state.selectedThreadId == threadId) {
                state.copy(
                    selectedThreadId = parsed.summary.id,
                    timeline = parsed.timeline,
                    activeTurn = parsed.activeTurnId != null,
                    pendingApproval = firstPendingApproval(parsed.summary.id, parsed.timeline),
                    pendingQuestion = firstPendingQuestion(parsed.summary.id),
                )
            } else {
                state
            }
        }
    }

    private suspend fun refreshThreadsInternal(current: CodexConnection) {
        val threads = mutableListOf<ThreadUi>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var truncated = false
        var pageCount = 0
        do {
            val response = current.request(
                method = "thread/list",
                params = buildJsonObject {
                    put("limit", THREAD_PAGE_SIZE)
                    put("sortKey", "updated_at")
                    put("sortDirection", "desc")
                    cursor?.let { put("cursor", it) }
                },
            )
            val result = response.resultOrThrow("thread/list")
            threads += ProtocolMapper.threads(result)
            val nextCursor = result.objectOrNull()?.string("nextCursor")
            pageCount += 1
            cursor = when {
                nextCursor == null -> null
                nextCursor in seenCursors -> {
                    truncated = true
                    null
                }
                pageCount >= MAX_THREAD_PAGES -> {
                    truncated = true
                    null
                }
                else -> nextCursor.also(seenCursors::add)
            }
        } while (cursor != null)
        val uniqueThreads = threads.distinctBy(ThreadUi::id)
        mutableState.update { existing ->
            existing.copy(
                threads = uniqueThreads,
                selectedThreadId = existing.selectedThreadId,
                error = if (truncated) {
                    "Task history was truncated after ${THREAD_PAGE_SIZE * MAX_THREAD_PAGES} entries."
                } else {
                    null
                },
            )
        }
    }

    private suspend fun handleMessage(current: CodexConnection, message: JsonRpcMessage) {
        when (message) {
            is JsonRpcRequest -> handleServerRequest(current, message)
            is JsonRpcNotification -> handleNotification(message)
            is JsonRpcResponse -> Unit
        }
    }

    private suspend fun handleServerRequest(current: CodexConnection, request: JsonRpcRequest) {
        when (request.method) {
            "item/commandExecution/requestApproval",
            "item/fileChange/requestApproval",
            "item/permissions/requestApproval",
            -> {
                val params = request.params.objectOrNull()
                var approval = ProtocolMapper.approval(request.id, request.method, request.params)
                if (approval == null || params == null) {
                    rejectUnsupportedRequest(current, request, "Malformed approval request")
                    return
                }
                if (approval.availableDecisions.isEmpty()) {
                    rejectUnsupportedRequest(current, request, "The host offered no valid approval decisions")
                    return
                }
                val itemId = params.string("itemId")
                val currentState = mutableState.value
                val matchingItem = if (params.string("threadId") == currentState.selectedThreadId) {
                    itemId?.let { id -> currentState.timeline.firstOrNull { it.id == id } }
                } else {
                    null
                }
                if (request.method == "item/fileChange/requestApproval" && matchingItem != null) {
                    approval = approval.copy(
                        commandOrDiff = listOfNotNull(
                            matchingItem.body.takeIf(String::isNotBlank),
                            matchingItem.detail?.takeIf(String::isNotBlank),
                        ).joinToString("\n").ifBlank { approval.commandOrDiff },
                    )
                }
                val pending = PendingServerRequest(
                    id = request.id,
                    method = request.method,
                    params = params,
                    threadId = params.string("threadId"),
                    approval = approval,
                )
                pendingRequests[approval.requestId] = pending
                refreshPendingPrompt()
            }
            "item/tool/requestUserInput" -> {
                val params = request.params.objectOrNull()
                val question = ProtocolMapper.question(request.id, request.params)
                if (question == null || params == null) {
                    rejectUnsupportedRequest(current, request, "Malformed user-input request")
                    return
                }
                pendingRequests[question.requestId] = PendingServerRequest(
                    id = request.id,
                    method = request.method,
                    params = params,
                    threadId = params.string("threadId"),
                    question = question,
                )
                refreshPendingPrompt()
            }
            "currentTime/read" -> {
                current.respond(
                    id = request.id,
                    result = buildJsonObject { put("currentTimeAt", System.currentTimeMillis() / 1_000) },
                )
            }
            "mcpServer/elicitation/request" -> {
                // This client does not yet render arbitrary JSON-schema forms. Decline explicitly
                // instead of leaving the turn hung or pretending that an empty form was accepted.
                current.respond(
                    id = request.id,
                    result = buildJsonObject {
                        put("action", "decline")
                        put("content", JsonNull)
                    },
                )
                showError("An MCP server requested a form that Codex Eink cannot render; it was declined.")
            }
            else -> rejectUnsupportedRequest(
                current,
                request,
                "Codex Eink does not advertise support for ${request.method}",
            )
        }
    }

    private suspend fun rejectUnsupportedRequest(
        current: CodexConnection,
        request: JsonRpcRequest,
        reason: String,
    ) {
        current.respond(
            id = request.id,
            error = JsonRpcError(code = METHOD_NOT_FOUND, message = reason),
        )
        showError(reason)
    }

    private fun handleNotification(notification: JsonRpcNotification) {
        val params = notification.params.objectOrNull()
        when (notification.method) {
            "thread/started" -> params?.get("thread").objectOrNull()?.let { thread ->
                ProtocolMapper.thread(thread)?.let(::upsertThread)
            }
            "thread/status/changed" -> {
                val threadId = params?.string("threadId") ?: return
                val status = ProtocolMapper.status(params["status"])
                mutableState.update { state ->
                    state.copy(
                        threads = state.threads.map { thread ->
                            if (thread.id == threadId) {
                                thread.copy(active = status == "active")
                            } else {
                                thread
                            }
                        },
                    )
                }
            }
            "thread/archived", "thread/deleted" -> {
                val threadId = params?.string("threadId") ?: return
                activeTurns.remove(threadId)
                pendingRequests.entries.removeIf { it.value.threadId == threadId }
                discardPendingDeltas(threadId = threadId)
                mutableState.update { state ->
                    if (state.selectedThreadId == threadId) {
                        state.copy(
                            threads = state.threads.filterNot { it.id == threadId },
                            selectedThreadId = null,
                            timeline = emptyList(),
                            activeTurn = false,
                            pendingApproval = null,
                            pendingQuestion = null,
                        )
                    } else {
                        state.copy(threads = state.threads.filterNot { it.id == threadId })
                    }
                }
            }
            "turn/started" -> handleTurnStarted(params)
            "turn/completed" -> handleTurnCompleted(params)
            "item/started", "item/completed" -> {
                val threadId = params?.string("threadId") ?: return
                val item = params["item"]?.let(ProtocolMapper::timelineItem) ?: return
                if (notification.method == "item/completed") {
                    discardPendingDeltas(threadId = threadId, itemId = item.id)
                }
                upsertTimeline(item, threadId)
            }
            "item/agentMessage/delta" -> appendDelta(params, TimelineKind.Agent, "Codex", toDetail = false)
            "item/plan/delta" -> appendDelta(params, TimelineKind.Plan, "Plan", toDetail = false)
            "item/reasoning/summaryTextDelta" -> appendDelta(
                params,
                TimelineKind.Reasoning,
                "Reasoning summary",
                toDetail = false,
            )
            "item/reasoning/textDelta" -> appendDelta(
                params,
                TimelineKind.Reasoning,
                "Reasoning summary",
                toDetail = true,
            )
            "item/commandExecution/outputDelta" -> appendDelta(
                params,
                TimelineKind.Command,
                "Command",
                toDetail = true,
            )
            "turn/diff/updated" -> handleTurnDiff(params)
            "turn/plan/updated" -> handleTurnPlan(params)
            "serverRequest/resolved" -> handleRequestResolved(params)
            "error" -> handleTurnError(params)
        }
    }

    private fun handleTurnStarted(params: JsonObject?) {
        val threadId = params?.string("threadId") ?: return
        val turn = params["turn"].objectOrNull() ?: return
        val turnId = turn.string("id") ?: return
        synchronized(completedTurnMonitor) { recentlyCompletedTurnIds.remove(turnId) }
        activeTurns[threadId] = turnId
        turn["items"].arrayOrNull().orEmpty().mapNotNull(ProtocolMapper::timelineItem)
            .forEach { upsertTimeline(it, threadId) }
        mutableState.update { state ->
            if (state.selectedThreadId == threadId) {
                state.copy(activeTurn = true, error = null)
            } else {
                state
            }
        }
    }

    private fun handleTurnCompleted(params: JsonObject?) {
        val threadId = params?.string("threadId") ?: return
        val turn = params["turn"].objectOrNull() ?: return
        val completedId = turn.string("id")
        if (completedId != null) {
            recordRecentlyCompleted(completedId)
            activeTurns.remove(threadId, completedId)
        } else {
            activeTurns.remove(threadId)
        }
        flushPendingDeltas()
        val status = ProtocolMapper.status(turn["status"])
        val error = turn["error"].objectOrNull()?.string("message")
        if (!error.isNullOrBlank()) {
            upsertTimeline(
                TimelineUi(
                    id = "turn-error:${completedId ?: System.nanoTime()}",
                    kind = TimelineKind.Error,
                    title = "Turn failed",
                    body = error,
                    status = status,
                ),
                threadId,
            )
        }
        mutableState.update { state ->
            if (state.selectedThreadId == threadId) {
                state.copy(
                    activeTurn = activeTurns[threadId] != null,
                    error = error ?: state.error,
                )
            } else {
                state
            }
        }
    }

    private fun appendDelta(
        params: JsonObject?,
        kind: TimelineKind,
        title: String,
        toDetail: Boolean,
    ) {
        val threadId = params?.string("threadId") ?: return
        val itemId = params.string("itemId") ?: return
        val delta = params.string("delta") ?: return
        if (mutableState.value.selectedThreadId != threadId) return
        synchronized(deltaMonitor) {
            val key = DeltaKey(threadId = threadId, itemId = itemId)
            val existing = pendingDeltas[key] ?: PendingDelta(kind = kind, title = title)
            pendingDeltas[key] = if (toDetail) {
                existing.copy(detail = existing.detail + delta)
            } else {
                existing.copy(body = existing.body + delta)
            }
            if (deltaFlushJob?.isActive != true) {
                deltaFlushJob = scope.launch {
                    delay(DELTA_FLUSH_INTERVAL_MILLIS)
                    flushPendingDeltas()
                }
            }
        }
    }

    private fun handleTurnDiff(params: JsonObject?) {
        val threadId = params?.string("threadId") ?: return
        val turnId = params.string("turnId") ?: return
        val diff = params.string("diff") ?: return
        upsertTimeline(
            TimelineUi(
                id = "turn-diff:$turnId",
                kind = TimelineKind.FileChange,
                title = "Turn changes",
                body = diff,
                status = "inProgress",
            ),
            threadId,
        )
    }

    private fun handleTurnPlan(params: JsonObject?) {
        val turnId = params?.string("turnId") ?: return
        val threadId = params.string("threadId")
            ?: activeTurns.entries.firstOrNull { it.value == turnId }?.key
            ?: return
        val plan = params["plan"].arrayOrNull().orEmpty().joinToString("\n") { entry ->
            val value = entry.objectOrNull()
            val status = value?.string("status") ?: "pending"
            val step = value?.string("step") ?: entry.toString()
            "[$status] $step"
        }
        val explanation = params.string("explanation")
        upsertTimeline(
            TimelineUi(
                id = "turn-plan:$turnId",
                kind = TimelineKind.Plan,
                title = "Plan",
                body = listOfNotNull(explanation, plan.takeIf(String::isNotBlank)).joinToString("\n"),
                status = "inProgress",
            ),
            threadId,
        )
    }

    private fun handleTurnError(params: JsonObject?) {
        val threadId = params?.string("threadId") ?: return
        val message = params["error"].objectOrNull()?.string("message") ?: "Codex reported an error."
        val turnId = params.string("turnId") ?: "unknown"
        upsertTimeline(
            TimelineUi(
                id = "error:$turnId:${System.nanoTime()}",
                kind = TimelineKind.Error,
                title = "Codex error",
                body = message,
                status = if (params.boolean("willRetry") == true) "retrying" else "failed",
            ),
            threadId,
        )
        mutableState.update { state ->
            if (state.selectedThreadId == threadId) state.copy(error = message) else state
        }
    }

    private fun handleRequestResolved(params: JsonObject?) {
        val id = params?.get("requestId").toJsonRpcId() ?: return
        pendingRequests.remove(ProtocolMapper.run { id.key() })
        refreshPendingPrompt()
    }

    private fun upsertThread(thread: ThreadUi) {
        mutableState.update { state ->
            state.copy(threads = state.threads.upsert(thread).sortedByDescending { it.updatedAt ?: 0L })
        }
    }

    private fun upsertTimeline(item: TimelineUi, threadId: String) {
        mutableState.update { state ->
            if (state.selectedThreadId == threadId) {
                state.copy(timeline = state.timeline.upsert(item))
            } else {
                state
            }
        }
    }

    private fun flushPendingDeltas() {
        synchronized(deltaMonitor) {
            deltaFlushJob = null
            if (pendingDeltas.isEmpty()) return
            val deltas = pendingDeltas.toMap().also { pendingDeltas.clear() }
            mutableState.update { state ->
                val threadId = state.selectedThreadId ?: return@update state
                val relevant = deltas.filterKeys { it.threadId == threadId }
                if (relevant.isEmpty()) return@update state
                val timeline = relevant.entries.fold(state.timeline) { items, (key, delta) ->
                    val existing = items.firstOrNull { it.id == key.itemId }
                        ?: TimelineUi(
                            id = key.itemId,
                            kind = delta.kind,
                            title = delta.title,
                            body = "",
                            status = "inProgress",
                        )
                    items.upsert(
                        existing.copy(
                            body = existing.body + delta.body,
                            detail = (existing.detail.orEmpty() + delta.detail).takeIf(String::isNotEmpty),
                        ),
                    )
                }
                state.copy(timeline = timeline)
            }
        }
    }

    private fun discardPendingDeltas(threadId: String, itemId: String? = null) {
        synchronized(deltaMonitor) {
            pendingDeltas.keys.removeAll { key ->
                key.threadId == threadId && (itemId == null || key.itemId == itemId)
            }
        }
    }

    private fun clearPendingDeltas() {
        synchronized(deltaMonitor) { pendingDeltas.clear() }
        deltaFlushJob?.cancel()
        deltaFlushJob = null
    }

    private fun approvalResult(pending: PendingServerRequest, decision: String): JsonObject {
        if (pending.method == "item/permissions/requestApproval") {
            val granted = when (decision) {
                "allowForTurn", "allowForSession", "accept", "acceptForSession" ->
                    pending.params["permissions"] ?: JsonObject(emptyMap())
                "deny", "decline", "cancel" -> JsonObject(emptyMap())
                else -> throw IllegalArgumentException("Unsupported permission decision")
            }
            return buildJsonObject {
                put("permissions", granted)
                put(
                    "scope",
                    if (decision == "allowForSession" || decision == "acceptForSession") "session" else "turn",
                )
            }
        }

        val offered = pending.approval?.availableDecisions.orEmpty()
        require(decision in offered) { "This decision was not offered by the host" }
        val wireDecision: JsonElement = when (decision) {
            "acceptWithExecpolicyAmendment" -> {
                val amendment = pending.params["proposedExecpolicyAmendment"]
                    ?: throw IllegalStateException("The host did not supply an exec-policy amendment")
                buildJsonObject {
                    put(
                        decision,
                        buildJsonObject { put("execpolicy_amendment", amendment) },
                    )
                }
            }
            "applyNetworkPolicyAmendment" -> {
                val amendment = pending.params["proposedNetworkPolicyAmendments"]
                    .arrayOrNull()
                    ?.firstOrNull()
                    ?: throw IllegalStateException("The host did not supply a network-policy amendment")
                buildJsonObject {
                    put(
                        decision,
                        buildJsonObject { put("network_policy_amendment", amendment) },
                    )
                }
            }
            else -> JsonPrimitive(decision)
        }
        return buildJsonObject { put("decision", wireDecision) }
    }

    private fun refreshPendingPrompt() {
        mutableState.update { state ->
            state.copy(
                pendingApproval = state.selectedThreadId?.let { threadId ->
                    firstPendingApproval(threadId, state.timeline)
                },
                pendingQuestion = state.selectedThreadId?.let(::firstPendingQuestion),
            )
        }
    }

    private fun firstPendingApproval(threadId: String, timeline: List<TimelineUi>): ApprovalUi? {
        val pending = pendingRequests.values
            .firstOrNull { it.threadId == threadId && it.approval != null }
            ?: return null
        val approval = pending.approval ?: return null
        if (pending.method != "item/fileChange/requestApproval") return approval
        val itemId = pending.params.string("itemId") ?: return approval
        val matchingItem = timeline.firstOrNull { it.id == itemId } ?: return approval
        return approval.copy(
            commandOrDiff = listOfNotNull(
                matchingItem.body.takeIf(String::isNotBlank),
                matchingItem.detail?.takeIf(String::isNotBlank),
            ).joinToString("\n").ifBlank { approval.commandOrDiff },
        )
    }

    private fun firstPendingQuestion(threadId: String): QuestionUi? = pendingRequests.values
        .firstOrNull { it.threadId == threadId && it.question != null }
        ?.question

    private fun setActiveTurn(threadId: String, turnId: String?) {
        if (turnId == null) activeTurns.remove(threadId) else activeTurns[threadId] = turnId
    }

    private fun recordRecentlyCompleted(turnId: String) {
        synchronized(completedTurnMonitor) {
            recentlyCompletedTurnIds += turnId
            while (recentlyCompletedTurnIds.size > COMPLETED_TURN_CACHE_SIZE) {
                val oldest = recentlyCompletedTurnIds.iterator()
                if (oldest.hasNext()) {
                    oldest.next()
                    oldest.remove()
                }
            }
        }
    }

    private fun consumeRecentlyCompleted(turnId: String): Boolean = synchronized(completedTurnMonitor) {
        recentlyCompletedTurnIds.remove(turnId)
    }

    private fun clearTransientSessionState() {
        activeTurns.clear()
        pendingRequests.clear()
        clearPendingDeltas()
        mutableState.update {
            it.copy(
                activeTurn = false,
                pendingApproval = null,
                pendingQuestion = null,
            )
        }
    }

    private fun requireReadyConnection(): CodexConnection {
        val current = connection ?: throw IllegalStateException("Connect to a host first")
        if (current.state.value !is ConnectionState.Connected || initializedSessionId == null) {
            throw IllegalStateException("The Codex connection is not ready yet")
        }
        return current
    }

    private suspend fun closeConnection() {
        lifecycleMutex.withLock { closeConnectionLocked() }
    }

    private suspend fun closeConnectionLocked() {
        val previous = connection
        connection = null
        initializedSessionId = null
        protocolHandshakeFailed = false
        activeTurns.clear()
        connectionStateJob?.cancel()
        connectionEventsJob?.cancel()
        connectionStateJob = null
        connectionEventsJob = null
        pendingRequests.clear()
        clearPendingDeltas()
        synchronized(completedTurnMonitor) { recentlyCompletedTurnIds.clear() }
        if (previous != null) {
            runCatching { previous.disconnect() }
        }
    }

    private suspend fun runLoadingAction(label: String, block: suspend () -> Unit) {
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            block()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            showError("$label: ${safeMessage(error)}")
        } finally {
            mutableState.update { it.copy(loading = false) }
        }
    }

    private suspend fun runUserAction(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            showError("$label: ${safeMessage(error)}")
        }
    }

    private fun showError(message: String) {
        mutableState.update { it.copy(error = ProtocolRedactor.redact(message)) }
    }

    private fun safeMessage(error: Throwable): String = ProtocolRedactor.redact(
        error.message?.takeIf(String::isNotBlank) ?: error::class.java.simpleName,
    )

    private fun validateProfile(profile: ConnectionProfile) {
        require(profile.displayName.isNotBlank()) { "Host name is required" }
        require(profile.credential.isNotBlank()) { "A capability token is required" }
        require(profile.mode == TransportMode.DirectDiagnostic) {
            MANAGED_REMOTE_GATE_DETAIL
        }
        val endpoint = runCatching { URI(profile.endpoint.trim()) }
            .getOrElse { throw IllegalArgumentException("Enter a valid WebSocket endpoint") }
        val scheme = endpoint.scheme?.lowercase()
        require(scheme == "ws" || scheme == "wss") { "Endpoint must use ws:// or wss://" }
        require(!endpoint.host.isNullOrBlank()) { "Endpoint must include a host" }
        require(endpoint.userInfo == null && endpoint.fragment == null) {
            "Endpoint must not contain credentials or a fragment"
        }
        if (scheme == "ws") {
            require(BuildConfig.DEBUG) {
                "Release builds require wss://; private ws:// is available only in diagnostic builds"
            }
            require(isPrivateHost(endpoint.host)) {
                "Cleartext ws:// is allowed only for loopback or Tailscale hosts; use wss:// otherwise"
            }
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        val normalized = host.trim('[', ']').lowercase()
        if (normalized == "localhost" || normalized.endsWith(".localhost") || normalized.endsWith(".ts.net")) {
            return true
        }
        if (':' in normalized) {
            return normalized == "::1" || normalized.startsWith("fd7a:115c:a1e0:")
        }
        val octets = normalized.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != IPV4_BYTES || octets.any { it !in 0..UNSIGNED_BYTE }) return false
        return octets[0] == 127 ||
            (octets[0] == TAILSCALE_FIRST_OCTET && octets[1] in TAILSCALE_SECOND_OCTET_RANGE)
    }

    private fun JsonRpcResponse.resultOrThrow(method: String): JsonElement? {
        error?.let { failure ->
            throw IOException("$method failed (${failure.code}): ${failure.message}")
        }
        return result
    }

    private data class PendingServerRequest(
        val id: JsonRpcId,
        val method: String,
        val params: JsonObject,
        val threadId: String?,
        val approval: ApprovalUi? = null,
        val question: QuestionUi? = null,
    )

    private data class DeltaKey(
        val threadId: String,
        val itemId: String,
    )

    private data class PendingDelta(
        val kind: TimelineKind,
        val title: String,
        val body: String = "",
        val detail: String = "",
    )

    private companion object {
        const val THREAD_PAGE_SIZE = 50
        const val MAX_THREAD_PAGES = 20
        const val DELTA_FLUSH_INTERVAL_MILLIS = 500L
        const val COMPLETED_TURN_CACHE_SIZE = 256
        const val METHOD_NOT_FOUND = -32601
        const val IPV4_BYTES = 4
        const val UNSIGNED_BYTE = 0xff
        const val TAILSCALE_FIRST_OCTET = 100
        val TAILSCALE_SECOND_OCTET_RANGE = 64..127
        const val MANAGED_REMOTE_GATE_MESSAGE = "Managed Remote compatibility is not verified."
        const val MANAGED_REMOTE_GATE_DETAIL =
            "Independent Codex Remote controller enrollment and authentication are not publicly documented. " +
                "The compatibility gate stays closed until clean-room evidence proves a legitimate flow."
    }
}

fun interface CodexConnectionFactory {
    fun create(profile: ConnectionProfile): CodexConnection
}

private object DefaultCodexConnectionFactory : CodexConnectionFactory {
    override fun create(profile: ConnectionProfile): CodexConnection {
        require(profile.mode == TransportMode.DirectDiagnostic) {
            "Managed Remote must pass its compatibility gate before creating a transport"
        }
        val endpoint = profile.endpoint.trim()
        return DirectWebSocketConnection(
            DirectWebSocketConfig(
                endpoint = endpoint,
                headersProvider = ConnectionHeadersProvider {
                    mapOf("Authorization" to "Bearer ${profile.credential.trim()}")
                },
                allowCleartext = BuildConfig.DEBUG && endpoint.startsWith("ws://", ignoreCase = true),
            ),
        )
    }
}

private fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.arrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

private fun JsonElement?.toJsonRpcId(): JsonRpcId? = when (this) {
    is JsonPrimitive -> if (isString) {
        contentOrNull?.let(JsonRpcId::StringId)
    } else {
        longOrNull?.let(JsonRpcId::NumberId)
    }
    JsonNull -> JsonRpcId.NullId
    else -> null
}

private fun <T> List<T>.upsert(value: T, id: (T) -> String): List<T> {
    val index = indexOfFirst { id(it) == id(value) }
    if (index < 0) return this + value
    return toMutableList().also { it[index] = value }
}

private fun List<ThreadUi>.upsert(value: ThreadUi): List<ThreadUi> = upsert(value, ThreadUi::id)

private fun List<TimelineUi>.upsert(value: TimelineUi): List<TimelineUi> = upsert(value, TimelineUi::id)
