package me.haroldmartin.codexeink.protocol

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolRedactorTest {
    @Test
    fun `redacts bearer headers credentials identifiers and command content`() {
        val input = """
            Authorization: Bearer secret-token.123
            {"refresh_token":"refresh-secret","client_id":"client-123","threadId":"thread-1","command":"rm -rf /tmp/x","safe":"visible"}
        """.trimIndent()

        val output = ProtocolRedactor.redact(input)

        assertFalse(output.contains("secret-token"))
        assertFalse(output.contains("refresh-secret"))
        assertFalse(output.contains("client-123"))
        assertFalse(output.contains("thread-1"))
        assertFalse(output.contains("rm -rf"))
        assertTrue(output.contains("visible"))
    }

    @Test
    fun `redacts sensitive header map case insensitively`() {
        val redacted = ProtocolRedactor.redactHeaders(
            mapOf(
                "AUTHORIZATION" to "Bearer token",
                "Accept" to "application/json",
            ),
        )

        assertEquals(ProtocolRedactor.REDACTED, redacted["AUTHORIZATION"])
        assertEquals("application/json", redacted["Accept"])
    }

    @Test
    fun `summary never includes params result or string ids`() {
        val request = JsonRpcRequest(
            id = JsonRpcId.StringId("secret-id"),
            method = "turn/start",
            params = buildJsonObject { put("command", "private") },
        )
        val summary = ProtocolRedactor.summarize(request)

        assertEquals("request method=turn/start id=<redacted>", summary)
        assertFalse(summary.contains("private"))
        assertFalse(summary.contains("secret-id"))
    }
}
