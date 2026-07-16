package me.haroldmartin.codexeink.protocol

import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Process-scoped resources shared by short-lived connection generations. */
private object WebSocketTransportResources {
    val client: OkHttpClient = OkHttpClient.Builder()
        .pingInterval(WEBSOCKET_PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val WEBSOCKET_PING_INTERVAL_SECONDS = 30L
}

@RequiresOptIn(
    message = "Managed relay support is an experimental, compatibility-gated wire primitive; it is not an enrolled production connection path.",
    level = RequiresOptIn.Level.ERROR,
)
annotation class ExperimentalManagedRelayApi

internal data class FrameSendResult(
    val acceptedFrames: Int,
    val totalFrames: Int,
) {
    val complete: Boolean get() = acceptedFrames == totalFrames
    val partial: Boolean get() = acceptedFrames in 1 until totalFrames
}

internal fun sendFrames(frames: List<String>, send: (String) -> Boolean): FrameSendResult {
    var accepted = 0
    for (frame in frames) {
        if (!send(frame)) break
        accepted++
    }
    return FrameSendResult(acceptedFrames = accepted, totalFrames = frames.size)
}

internal fun RelayFrame.isInScope(clientId: String, streamId: String): Boolean =
    this.clientId == clientId && this.streamId == streamId

fun interface ConnectionHeadersProvider {
    /** Returns fresh headers for every connection attempt. Never persist or log the returned values. */
    suspend fun headers(): Map<String, String>
}

data class DirectWebSocketConfig(
    val endpoint: String,
    val headersProvider: ConnectionHeadersProvider,
    val allowCleartext: Boolean = false,
    val connectTimeoutMillis: Long = 15_000,
    val reconnectPolicy: ReconnectPolicy? = ReconnectPolicy(),
)

/**
 * Diagnostic transport for `codex app-server --listen ws://...`.
 *
 * Codex documents this WebSocket listener as experimental and unsupported. It is useful over an
 * authenticated private network, but it is not a substitute for Desktop's managed remote control.
 */
class DirectWebSocketConnection internal constructor(
    private val config: DirectWebSocketConfig,
    client: OkHttpClient,
    scope: CoroutineScope,
) : OkHttpCodexConnection(
    endpoint = config.endpoint,
    headersProvider = config.headersProvider,
    allowCleartext = config.allowCleartext,
    connectTimeoutMillis = config.connectTimeoutMillis,
    reconnectPolicy = config.reconnectPolicy,
    client = client,
    scope = scope,
) {
    constructor(config: DirectWebSocketConfig) : this(
        config = config,
        client = WebSocketTransportResources.client,
        scope = WebSocketTransportResources.scope,
    )

    private val codec = JsonRpcCodec()

    override suspend fun send(message: JsonRpcMessage): Boolean = sendRawFrames(listOf(codec.encode(message)))

    override fun onSocketText(text: String) {
        try {
            receive(codec.decode(text))
        } catch (error: JsonRpcCodecException) {
            protocolError(error.message ?: "Malformed JSON-RPC frame")
        }
    }
}

sealed interface RelayCompatibility {
    /**
     * Evidence that the caller has independently verified the controller wire/auth contract.
     * [evidence] should identify a checked-in compatibility manifest or test fixture, not a secret.
     */
    data class Verified(
        val protocolVersion: String,
        val testedHostVersion: String,
        val evidence: String,
    ) : RelayCompatibility {
        init {
            require(protocolVersion.isNotBlank())
            require(testedHostVersion.isNotBlank())
            require(evidence.isNotBlank())
        }
    }

    data class Blocked(val reason: String) : RelayCompatibility
}

class RelayCompatibilityException(message: String) : IllegalStateException(message)

@ExperimentalManagedRelayApi
data class ManagedRelayConfig(
    /** No default is supplied: the authorized controller endpoint must come from verified research. */
    val endpoint: String,
    val clientId: String,
    val streamId: String,
    /** No first-party tokens are extracted or synthesized; callers supply legitimately acquired headers. */
    val headersProvider: ConnectionHeadersProvider,
    val compatibility: RelayCompatibility,
    val initialCursor: String? = null,
    val firstSequenceId: Long = 0,
    val maxInlineMessageBytes: Int = ChunkAssembler.DEFAULT_TARGET_SEGMENT_BYTES,
    val relayPingIntervalMillis: Long = 30_000,
    val connectTimeoutMillis: Long = 15_000,
    val reconnectPolicy: ReconnectPolicy? = ReconnectPolicy(),
    val allowCleartext: Boolean = false,
) {
    init {
        require(clientId.isNotBlank())
        require(streamId.isNotBlank())
        require(firstSequenceId >= 0)
        require(maxInlineMessageBytes > 0)
        require(relayPingIntervalMillis > 0)
    }
}

/**
 * Controller-side managed-relay transport primitive.
 *
 * The open-source Codex repository publishes the host envelope shape but not a public independent
 * controller enrollment/authentication flow. Therefore [connect] refuses to run until the caller
 * supplies [RelayCompatibility.Verified] and explicit endpoint/auth configuration.
 */
@ExperimentalManagedRelayApi
class ManagedRelayConnection internal constructor(
    private val config: ManagedRelayConfig,
    client: OkHttpClient,
    private val managedScope: CoroutineScope,
) : OkHttpCodexConnection(
    endpoint = config.endpoint,
    headersProvider = config.headersProvider,
    allowCleartext = config.allowCleartext,
    connectTimeoutMillis = config.connectTimeoutMillis,
    reconnectPolicy = config.reconnectPolicy,
    client = client,
    scope = managedScope,
) {
    constructor(config: ManagedRelayConfig) : this(
        config = config,
        client = WebSocketTransportResources.client,
        managedScope = WebSocketTransportResources.scope,
    )

    private val jsonRpcCodec = JsonRpcCodec()
    private val relayCodec = RelayEnvelopeCodec(
        expectedInboundDirection = RelayDirection.SERVER_TO_CLIENT,
        jsonRpcCodec = jsonRpcCodec,
    )
    private val chunkAssembler = ChunkAssembler()
    private val replayBuffer = RelayReplayBuffer(relayCodec)
    private val nextSequenceId = AtomicLong(config.firstSequenceId)
    private val lastDeliveredServerSequenceId = AtomicLong(-1)
    private val mutableCursor = MutableStateFlow(config.initialCursor)
    private var pingJob: Job? = null

    val cursor: StateFlow<String?> = mutableCursor.asStateFlow()
    protected override val preservesPendingRequestsAcrossReconnect: Boolean = true

    override suspend fun connect() {
        val compatibility = config.compatibility
        if (compatibility !is RelayCompatibility.Verified) {
            val reason = (compatibility as? RelayCompatibility.Blocked)?.reason ?: "not verified"
            mutableState.value = ConnectionState.Failed(
                reason = "Managed relay compatibility gate is blocked: $reason",
                recoverable = false,
            )
            throw RelayCompatibilityException(
                "Managed relay requires independently verified endpoint, authentication, and controller protocol: $reason",
            )
        }
        super.connect()
    }

    override suspend fun disconnect(code: Int, reason: String) {
        pingJob?.cancel()
        if (state.value is ConnectionState.Connected) {
            sendRawFrames(
                listOf(
                    relayCodec.encode(
                        RelayFrame.ClientClosed(
                            clientId = config.clientId,
                            streamId = config.streamId,
                            cursor = mutableCursor.value,
                        ),
                    ),
                ),
            )
        }
        super.disconnect(code, reason)
    }

    override suspend fun send(message: JsonRpcMessage): Boolean {
        if (state.value !is ConnectionState.Connected) return false
        val seqId = nextSequenceId.getAndIncrement()
        val frame = RelayFrame.Message(
            clientId = config.clientId,
            streamId = config.streamId,
            seqId = seqId,
            cursor = mutableCursor.value,
            direction = RelayDirection.CLIENT_TO_SERVER,
            message = message,
        )
        val encodedMessageSize = jsonRpcCodec.encode(message).toByteArray(Charsets.UTF_8).size
        val frames = if (encodedMessageSize <= config.maxInlineMessageBytes) {
            listOf(frame)
        } else {
            ChunkAssembler.segment(
                frame = frame,
                jsonRpcCodec = jsonRpcCodec,
                targetSegmentBytes = config.maxInlineMessageBytes,
            )
        }
        if (!replayBuffer.record(frames)) {
            protocolError("Managed relay replay buffer is full")
            return false
        }
        if (!sendRawFrames(frames.map(relayCodec::encode))) {
            protocolError("Managed relay send was interrupted; unacknowledged frames remain queued for replay")
        }
        // Recording in the bounded replay buffer is the managed transport's acceptance boundary.
        return true
    }

    override fun onSocketText(text: String) {
        val frame = try {
            relayCodec.decode(text)
        } catch (error: RelayEnvelopeCodecException) {
            protocolError(error.message ?: "Malformed relay frame")
            return
        } catch (error: JsonRpcCodecException) {
            protocolError(error.message ?: "Malformed nested JSON-RPC frame")
            return
        }

        if (!frame.isInScope(config.clientId, config.streamId)) {
            protocolError("Managed relay frame is outside the active client or stream")
            return
        }
        frame.cursor?.let { mutableCursor.value = it }
        when (frame) {
            is RelayFrame.Message -> {
                if (frame.direction != RelayDirection.SERVER_TO_CLIENT) {
                    protocolError("Unexpected client_message from managed relay")
                    return
                }
                if (wasAlreadyDelivered(frame.seqId)) {
                    acknowledge(frame, segmentId = null)
                    return
                }
                receive(frame.message)
                markDelivered(frame.seqId)
                acknowledge(frame, segmentId = null)
            }
            is RelayFrame.MessageChunk -> {
                if (frame.direction != RelayDirection.SERVER_TO_CLIENT) {
                    protocolError("Unexpected client_message_chunk from managed relay")
                    return
                }
                if (wasAlreadyDelivered(frame.seqId)) {
                    acknowledge(frame, segmentId = frame.segmentCount - 1)
                    return
                }
                when (val result = chunkAssembler.offer(frame)) {
                    ChunkAssemblyResult.Pending,
                    ChunkAssemblyResult.Duplicate,
                    -> chunkAssembler.highestContiguousSegment(frame)?.let { segmentId ->
                        acknowledge(frame, segmentId = segmentId)
                    }
                    is ChunkAssemblyResult.Complete -> {
                        try {
                            receive(jsonRpcCodec.decode(result.messageJson))
                            markDelivered(frame.seqId)
                            acknowledge(frame, segmentId = frame.segmentCount - 1)
                        } catch (error: JsonRpcCodecException) {
                            protocolError(error.message ?: "Malformed reassembled JSON-RPC message")
                        }
                    }
                    is ChunkAssemblyResult.Rejected -> protocolError("Rejected relay chunk: ${result.reason}")
                }
            }
            is RelayFrame.Pong -> Unit
            is RelayFrame.Ack -> replayBuffer.acknowledge(frame)
            is RelayFrame.Unknown -> protocolError("Unknown relay event type: ${frame.type}")
            is RelayFrame.Ping -> protocolError("Unexpected ping from managed relay")
            is RelayFrame.ClientClosed -> protocolError("Unexpected client_closed from managed relay")
        }
    }

    override fun onSocketOpened(generation: Long) {
        val replay = replayBuffer.replayFrames(mutableCursor.value)
        if (replay.isNotEmpty() && !sendRawFrames(replay.map(relayCodec::encode))) {
            protocolError("Managed relay could not replay unacknowledged messages")
        }
        pingJob?.cancel()
        pingJob = managedScope.launch {
            while (true) {
                delay(config.relayPingIntervalMillis)
                sendRawFrames(
                    listOf(
                        relayCodec.encode(
                            RelayFrame.Ping(
                                clientId = config.clientId,
                                streamId = config.streamId,
                                cursor = mutableCursor.value,
                            ),
                        ),
                    ),
                )
            }
        }
    }

    override fun onSocketLost() {
        pingJob?.cancel()
        pingJob = null
    }

    private fun acknowledge(frame: RelayFrame, segmentId: Int?) {
        val streamId = frame.streamId ?: return protocolError("Cannot ACK relay frame without stream_id")
        val seqId = frame.seqId ?: return protocolError("Cannot ACK relay frame without seq_id")
        sendRawFrames(
            listOf(
                relayCodec.encode(
                    RelayFrame.Ack(
                        clientId = frame.clientId,
                        streamId = streamId,
                        seqId = seqId,
                        cursor = mutableCursor.value,
                        direction = RelayDirection.CLIENT_TO_SERVER,
                        segmentId = segmentId,
                    ),
                ),
            ),
        )
    }

    private fun wasAlreadyDelivered(seqId: Long?): Boolean =
        seqId != null && seqId <= lastDeliveredServerSequenceId.get()

    private fun markDelivered(seqId: Long?) {
        if (seqId != null) lastDeliveredServerSequenceId.accumulateAndGet(seqId, ::maxOf)
    }
}

abstract class OkHttpCodexConnection internal constructor(
    private val endpoint: String,
    private val headersProvider: ConnectionHeadersProvider,
    private val allowCleartext: Boolean,
    private val connectTimeoutMillis: Long,
    reconnectPolicy: ReconnectPolicy?,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) : BaseCodexConnection() {
    private val lifecycleMutex = Mutex()
    private val monitor = Any()
    private val backoff = reconnectPolicy?.let(::ReconnectBackoff)

    @Volatile
    private var manuallyDisconnected = true
    private var generation = 0L
    private var lostGeneration = Long.MIN_VALUE
    private var socket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var stabilityJob: Job? = null

    override suspend fun connect() {
        val shouldConnect = lifecycleMutex.withLock {
            if (state.value is ConnectionState.Connected || state.value is ConnectionState.Connecting) {
                false
            } else {
                validateEndpoint()
                val scheduled = synchronized(monitor) {
                    manuallyDisconnected = false
                    reconnectJob.also { reconnectJob = null }
                }
                scheduled?.cancel()
                mutableState.value = ConnectionState.Connecting()
                true
            }
        }
        if (shouldConnect) openOnce(awaitOpen = true)
    }

    override suspend fun disconnect(code: Int, reason: String) = lifecycleMutex.withLock {
        mutableState.value = ConnectionState.Closing
        val (existing, scheduled) = synchronized(monitor) {
            manuallyDisconnected = true
            generation++
            val active = socket.also { socket = null }
            val pendingReconnect = reconnectJob.also { reconnectJob = null }
            active to pendingReconnect
        }
        scheduled?.cancel()
        stabilityJob?.cancel()
        stabilityJob = null
        existing?.close(code, reason.take(123))
        onSocketLost()
        failPendingRequests(IOException("Connection closed by client"))
        mutableState.value = ConnectionState.Disconnected
    }

    protected fun sendRawFrames(frames: List<String>): Boolean {
        if (frames.isEmpty()) return true
        val active = synchronized(monitor) { socket }
        if (active == null || state.value !is ConnectionState.Connected) {
            return false
        }
        val result = sendFrames(frames, active::send)
        if (!result.complete) active.cancel()
        return result.complete
    }

    protected open fun onSocketOpened(generation: Long) = Unit
    protected open fun onSocketLost() = Unit
    protected open val preservesPendingRequestsAcrossReconnect: Boolean = false
    protected abstract fun onSocketText(text: String)

    override fun onEventQueueOverflow() {
        super.onEventQueueOverflow()
        synchronized(monitor) { socket }?.cancel()
    }

    private suspend fun openOnce(awaitOpen: Boolean) {
        val headers = try {
            headersProvider.headers()
        } catch (error: CancellationException) {
            if (!synchronized(monitor) { manuallyDisconnected }) {
                mutableState.value = ConnectionState.Disconnected
            }
            throw error
        } catch (error: Throwable) {
            scheduleReconnect(error)
            throw IOException("Unable to obtain connection headers", error)
        }
        val requestBuilder = try {
            validateHeaders(headers)
            if (!allowCleartext && endpoint.startsWith("ws://", ignoreCase = true) && headers.isNotEmpty()) {
                throw IllegalArgumentException("Refusing to send connection headers over cleartext WebSocket")
            }
            Request.Builder().url(endpoint).also { builder -> headers.forEach(builder::header) }
        } catch (error: IllegalArgumentException) {
            synchronized(monitor) { manuallyDisconnected = true }
            mutableState.value = ConnectionState.Failed(error.message.orEmpty(), recoverable = false)
            throw error
        }
        val opened = CompletableDeferred<Unit>()
        val currentGeneration = synchronized(monitor) {
            if (manuallyDisconnected) throw CancellationException("Connection attempt was cancelled")
            generation += 1
            lostGeneration = Long.MIN_VALUE
            generation
        }
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val current = synchronized(monitor) {
                    if (
                        currentGeneration != generation ||
                        lostGeneration == currentGeneration ||
                        manuallyDisconnected
                    ) {
                        false
                    } else {
                        socket = webSocket
                        true
                    }
                }
                if (!current) {
                    opened.completeExceptionally(CancellationException("Connection attempt became stale"))
                    webSocket.close(1000, "stale connection")
                    return
                }
                mutableState.value = ConnectionState.Connected(sessionId = currentGeneration.toString())
                opened.complete(Unit)
                onSocketOpened(currentGeneration)
                stabilityJob?.cancel()
                stabilityJob = scope.launch {
                    delay(RECONNECT_STABILITY_MILLIS)
                    if (isCurrent(currentGeneration, webSocket)) backoff?.reset()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isCurrent(currentGeneration, webSocket)) onSocketText(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (isCurrent(currentGeneration, webSocket)) webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (isCurrent(currentGeneration, webSocket)) {
                    transportClosed(code, ProtocolRedactor.redact(reason))
                    handleLoss(currentGeneration, IOException("WebSocket closed ($code)"), opened)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleLoss(currentGeneration, t, opened)
            }
        }
        val createdSocket = client.newWebSocket(requestBuilder.build(), listener)
        synchronized(monitor) {
            if (currentGeneration == generation && lostGeneration != currentGeneration && socket == null) {
                socket = createdSocket
            }
        }
        if (!awaitOpen) return
        try {
            withTimeout(connectTimeoutMillis) { opened.await() }
        } catch (error: TimeoutCancellationException) {
            createdSocket.cancel()
            handleLoss(currentGeneration, error, opened)
            throw error
        } catch (error: CancellationException) {
            createdSocket.cancel()
            abandonAttempt(currentGeneration)
            throw error
        } catch (error: Throwable) {
            createdSocket.cancel()
            handleLoss(currentGeneration, error, opened)
            throw error
        }
    }

    private fun abandonAttempt(currentGeneration: Long) {
        val shouldAbandon = synchronized(monitor) {
            if (currentGeneration != generation || lostGeneration == currentGeneration) {
                false
            } else {
                lostGeneration = currentGeneration
                socket = null
                true
            }
        }
        if (!shouldAbandon) return
        stabilityJob?.cancel()
        stabilityJob = null
        onSocketLost()
        if (synchronized(monitor) { reconnectJob == null && !manuallyDisconnected }) {
            mutableState.value = ConnectionState.Disconnected
        }
    }

    private fun isCurrent(currentGeneration: Long, webSocket: WebSocket): Boolean = synchronized(monitor) {
        currentGeneration == generation && socket === webSocket
    }

    private fun handleLoss(
        currentGeneration: Long,
        cause: Throwable,
        opened: CompletableDeferred<Unit>,
    ) {
        val shouldHandle = synchronized(monitor) {
            if (currentGeneration != generation || lostGeneration == currentGeneration) {
                false
            } else {
                lostGeneration = currentGeneration
                socket = null
                true
            }
        }
        if (!shouldHandle) return
        stabilityJob?.cancel()
        stabilityJob = null
        opened.completeExceptionally(cause)
        onSocketLost()
        if (!preservesPendingRequestsAcrossReconnect) {
            failPendingRequests(IOException("Connection lost", cause))
        }
        scheduleReconnect(cause)
    }

    private fun scheduleReconnect(cause: Throwable) {
        var previousJob: Job? = null
        synchronized(monitor) {
            if (manuallyDisconnected) {
                mutableState.value = ConnectionState.Disconnected
                return
            }
            val delayMillis = backoff?.nextDelayMillis()
            if (delayMillis == null) {
                failPendingRequests(IOException("Reconnect attempts exhausted", cause))
                mutableState.value = ConnectionState.Failed(
                    reason = ProtocolRedactor.redact(cause.message ?: cause::class.java.simpleName),
                    recoverable = false,
                )
                return
            }
            val attempt = backoff.attempt
            mutableState.value = ConnectionState.Reconnecting(
                attempt = attempt,
                delayMillis = delayMillis,
                reason = ProtocolRedactor.redact(cause.message ?: cause::class.java.simpleName),
            )
            previousJob = reconnectJob
            reconnectJob = scope.launch {
                delay(delayMillis)
                val shouldReconnect = lifecycleMutex.withLock {
                    val shouldReconnect = synchronized(monitor) {
                        if (manuallyDisconnected) {
                            false
                        } else {
                            reconnectJob = null
                            true
                        }
                    }
                    if (shouldReconnect) {
                        mutableState.value = ConnectionState.Connecting(attempt = attempt)
                    }
                    shouldReconnect
                }
                if (!shouldReconnect) return@launch
                try {
                    openOnce(awaitOpen = true)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    // The listener/preparation failure schedules the next bounded attempt.
                }
            }
        }
        previousJob?.cancel()
    }

    private fun validateEndpoint() {
        val lower = endpoint.lowercase()
        require(lower.startsWith("ws://") || lower.startsWith("wss://")) {
            "WebSocket endpoint must use ws:// or wss://"
        }
    }

    private fun validateHeaders(headers: Map<String, String>) {
        headers.keys.forEach { name ->
            val lower = name.lowercase()
            require(
                lower !in RESERVED_HEADERS &&
                    !lower.startsWith("sec-websocket-"),
            ) { "Header $name is controlled by the WebSocket transport" }
        }
    }

    private companion object {
        const val RECONNECT_STABILITY_MILLIS = 30_000L
        val RESERVED_HEADERS = setOf("connection", "upgrade", "host", "origin")
    }
}
