package me.haroldmartin.codexeink.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRpcCodecTest {
    private val codec = JsonRpcCodec()

    @Test
    fun `encodes Codex request without jsonrpc header`() {
        val encoded = codec.encode(
            JsonRpcRequest(
                id = JsonRpcId.NumberId(7),
                method = "turn/interrupt",
                params = buildJsonObject {
                    put("threadId", "thread-1")
                    put("turnId", "turn-1")
                },
            ),
        )

        assertEquals(
            "{\"id\":7,\"method\":\"turn/interrupt\",\"params\":{\"threadId\":\"thread-1\",\"turnId\":\"turn-1\"}}",
            encoded,
        )
        assertFalse(encoded.contains("jsonrpc"))
    }

    @Test
    fun `decodes server request and preserves unknown fields`() {
        val decoded = codec.decode(
            """{"method":"item/commandExecution/requestApproval","id":"approval-1","params":{"reason":"test"},"future":42}""",
        ) as JsonRpcRequest

        assertEquals(JsonRpcId.StringId("approval-1"), decoded.id)
        assertEquals("item/commandExecution/requestApproval", decoded.method)
        assertEquals(JsonPrimitive(42), decoded.raw?.get("future"))
        assertTrue(codec.encode(decoded).contains("\"future\":42"))
    }

    @Test
    fun `re-encoding preserves extensions but replaces stale canonical fields`() {
        val decoded = codec.decode(
            """{"jsonrpc":"2.0","id":7,"result":{"ok":true},"future":{"enabled":true}}""",
        ) as JsonRpcResponse

        val encoded = codec.encode(decoded.copy(error = JsonRpcError(-32000, "failed"), result = null))

        assertTrue(encoded.contains("\"future\":{\"enabled\":true}"))
        assertTrue(encoded.contains("\"error\""))
        assertFalse(encoded.contains("\"result\""))
        assertFalse(encoded.contains("\"jsonrpc\""))
    }

    @Test
    fun `decodes notification and standard version header`() {
        val decoded = codec.decode(
            """{"jsonrpc":"2.0","method":"turn/completed","params":{"threadId":"t"}}""",
        ) as JsonRpcNotification

        assertEquals("turn/completed", decoded.method)
    }

    @Test
    fun `round trips success and error responses`() {
        val success = JsonRpcResponse(
            id = JsonRpcId.NumberId(3),
            result = buildJsonObject { put("claimed", true) },
        )
        assertEquals(success.copy(raw = null), (codec.decode(codec.encode(success)) as JsonRpcResponse).copy(raw = null))

        val error = JsonRpcResponse(
            id = JsonRpcId.StringId("request"),
            error = JsonRpcError(
                code = -32001,
                message = "Server overloaded; retry later.",
                data = buildJsonObject { put("retryable", true) },
            ),
        )
        val decoded = codec.decode(codec.encode(error)) as JsonRpcResponse
        assertFalse(decoded.isSuccess)
        assertEquals(-32001, decoded.error?.code)
        assertNull(decoded.result)
    }

    @Test
    fun `supports null response id and explicit null result`() {
        val decoded = codec.decode("""{"id":null,"result":null}""") as JsonRpcResponse

        assertEquals(JsonRpcId.NullId, decoded.id)
        assertEquals(JsonNull, decoded.result)
        assertTrue(decoded.isSuccess)
    }

    @Test
    fun `rejects fractional ids invalid versions and ambiguous objects`() {
        assertThrows(JsonRpcCodecException::class.java) {
            codec.decode("""{"id":1.5,"method":"test"}""")
        }
        assertThrows(JsonRpcCodecException::class.java) {
            codec.decode("""{"jsonrpc":"1.0","method":"test"}""")
        }
        assertThrows(JsonRpcCodecException::class.java) {
            codec.decode("""{"id":1}""")
        }
    }

    @Test
    fun `rejects response containing both result and error as a codec failure`() {
        assertThrows(JsonRpcCodecException::class.java) {
            codec.decode(
                """{"id":1,"result":{"ok":true},"error":{"code":-32603,"message":"broken"}}""",
            )
        }
    }

    @Test
    fun `rejects error codes outside the Int range`() {
        assertThrows(JsonRpcCodecException::class.java) {
            codec.decode("""{"id":1,"error":{"code":2147483648,"message":"too large"}}""")
        }
        assertThrows(JsonRpcCodecException::class.java) {
            codec.decode("""{"id":1,"error":{"code":-2147483649,"message":"too small"}}""")
        }
    }

    @Test
    fun `standard codec can include version`() {
        val encoded = JsonRpcCodec(includeVersion = true).encode(
            JsonRpcNotification(method = "initialized"),
        )
        assertTrue(encoded.contains("\"jsonrpc\":\"2.0\""))
    }
}
