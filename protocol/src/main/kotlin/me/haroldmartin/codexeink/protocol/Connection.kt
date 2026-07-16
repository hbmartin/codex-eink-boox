package me.haroldmartin.codexeink.protocol

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val attempt: Int = 0) : ConnectionState
    data class Connected(val sessionId: String? = null) : ConnectionState
    data class Reconnecting(
        val attempt: Int,
        val delayMillis: Long,
        val reason: String? = null,
    ) : ConnectionState
    data object Closing : ConnectionState
    data class Failed(val reason: String, val recoverable: Boolean) : ConnectionState
}

sealed interface ConnectionEvent {
    data class MessageReceived(val message: JsonRpcMessage) : ConnectionEvent
    data class ProtocolError(val summary: String) : ConnectionEvent
    data class TransportClosed(val code: Int, val reason: String) : ConnectionEvent
}

interface CodexConnection {
    val state: StateFlow<ConnectionState>
    val events: SharedFlow<ConnectionEvent>

    suspend fun connect()
    suspend fun disconnect(code: Int = 1000, reason: String = "client disconnect")
    suspend fun send(message: JsonRpcMessage): Boolean

    suspend fun request(
        method: String,
        params: JsonElement? = null,
        timeoutMillis: Long = 30_000,
    ): JsonRpcResponse

    suspend fun notify(method: String, params: JsonElement? = null): Boolean =
        send(JsonRpcNotification(method = method, params = params))

    suspend fun respond(
        id: JsonRpcId,
        result: JsonElement? = null,
        error: JsonRpcError? = null,
    ): Boolean = send(JsonRpcResponse(id = id, result = result, error = error))
}

abstract class BaseCodexConnection : CodexConnection {
    protected val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    protected val mutableEvents = MutableSharedFlow<ConnectionEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()
    override val events: SharedFlow<ConnectionEvent> = mutableEvents.asSharedFlow()

    private val nextRequestId = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<JsonRpcId, CompletableDeferred<JsonRpcResponse>>()

    override suspend fun request(
        method: String,
        params: JsonElement?,
        timeoutMillis: Long,
    ): JsonRpcResponse {
        val id = JsonRpcId.NumberId(nextRequestId.getAndIncrement())
        val pending = CompletableDeferred<JsonRpcResponse>()
        pendingRequests[id] = pending
        if (!send(JsonRpcRequest(id = id, method = method, params = params))) {
            pendingRequests.remove(id)
            throw IOException("Transport rejected JSON-RPC request")
        }
        return try {
            withTimeout(timeoutMillis) { pending.await() }
        } finally {
            pendingRequests.remove(id)
        }
    }

    protected fun receive(message: JsonRpcMessage) {
        if (message is JsonRpcResponse && pendingRequests.remove(message.id)?.complete(message) == true) return
        mutableEvents.tryEmit(ConnectionEvent.MessageReceived(message))
    }

    protected fun protocolError(summary: String) {
        mutableEvents.tryEmit(ConnectionEvent.ProtocolError(summary))
    }

    protected fun transportClosed(code: Int, reason: String) {
        mutableEvents.tryEmit(ConnectionEvent.TransportClosed(code, reason))
    }

    protected fun failPendingRequests(cause: Throwable) {
        pendingRequests.values.forEach { it.completeExceptionally(cause) }
        pendingRequests.clear()
    }
}
