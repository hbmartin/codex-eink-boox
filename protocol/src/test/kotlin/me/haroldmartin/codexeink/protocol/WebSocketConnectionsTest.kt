package me.haroldmartin.codexeink.protocol

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSocketConnectionsTest {
    @Test
    fun `disconnect cancels a pending connection attempt immediately`() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { serverSocket ->
            val acceptedSocket = CompletableDeferred<Socket>()
            val serverJob = launch(Dispatchers.IO) {
                try {
                    serverSocket.accept().use { socket ->
                        acceptedSocket.complete(socket)
                        awaitCancellation()
                    }
                } catch (error: Throwable) {
                    if (!acceptedSocket.isCompleted) acceptedSocket.completeExceptionally(error)
                }
            }
            val client = OkHttpClient.Builder().build()
            val transportJob = SupervisorJob()
            val connection = DirectWebSocketConnection(
                config = DirectWebSocketConfig(
                    endpoint = "ws://${serverSocket.inetAddress.hostAddress}:${serverSocket.localPort}",
                    headersProvider = ConnectionHeadersProvider { emptyMap() },
                    allowCleartext = true,
                    connectTimeoutMillis = 10_000,
                    reconnectPolicy = null,
                ),
                client = client,
                scope = CoroutineScope(transportJob + Dispatchers.IO),
            )

            try {
                val connectionAttempt = async { runCatching { connection.connect() } }
                withTimeout(2_000) { acceptedSocket.await() }

                connection.disconnect()

                val failure = withTimeout(1_000) { connectionAttempt.await() }.exceptionOrNull()
                assertTrue(failure is CancellationException)
                assertEquals(ConnectionState.Disconnected, connection.state.value)
            } finally {
                serverJob.cancelAndJoin()
                transportJob.cancel()
                client.dispatcher.executorService.shutdownNow()
                client.connectionPool.evictAll()
            }
        }
    }
}
