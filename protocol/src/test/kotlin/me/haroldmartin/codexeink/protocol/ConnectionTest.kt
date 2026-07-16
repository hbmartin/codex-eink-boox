package me.haroldmartin.codexeink.protocol

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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
