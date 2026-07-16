package me.haroldmartin.codexeink.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

sealed interface JsonRpcId {
    data class StringId(val value: String) : JsonRpcId
    data class NumberId(val value: Long) : JsonRpcId
    data object NullId : JsonRpcId
}

sealed interface JsonRpcMessage {
    /** Original object when decoded, retained so newly added fields are not destroyed by inspection. */
    val raw: JsonObject?
}

data class JsonRpcRequest(
    val id: JsonRpcId,
    val method: String,
    val params: JsonElement? = null,
    override val raw: JsonObject? = null,
) : JsonRpcMessage

data class JsonRpcNotification(
    val method: String,
    val params: JsonElement? = null,
    override val raw: JsonObject? = null,
) : JsonRpcMessage

data class JsonRpcResponse(
    val id: JsonRpcId,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
    override val raw: JsonObject? = null,
) : JsonRpcMessage {
    init {
        require(result == null || error == null) { "A JSON-RPC response cannot contain both result and error" }
    }

    val isSuccess: Boolean get() = error == null
}

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

class JsonRpcCodecException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/**
 * Codec for Codex app-server JSON-RPC.
 *
 * App-server intentionally omits the `jsonrpc: "2.0"` member on the wire. The decoder accepts both
 * standard JSON-RPC and the Codex form; [includeVersion] is available for generic JSON-RPC peers.
 */
class JsonRpcCodec(
    private val includeVersion: Boolean = false,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    },
) {
    fun decode(text: String): JsonRpcMessage {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
                ?: throw JsonRpcCodecException("JSON-RPC frame must be an object")
        } catch (error: JsonRpcCodecException) {
            throw error
        } catch (error: Exception) {
            throw JsonRpcCodecException("Malformed JSON-RPC frame", error)
        }

        root["jsonrpc"]?.let { version ->
            if (version.jsonPrimitive.contentOrNull != "2.0") {
                throw JsonRpcCodecException("Unsupported JSON-RPC version")
            }
        }

        val method = root["method"]?.jsonPrimitive?.contentOrNull
        val idElement = root["id"]

        if (method != null) {
            return if (idElement == null) {
                JsonRpcNotification(method = method, params = root["params"], raw = root)
            } else {
                JsonRpcRequest(id = decodeId(idElement), method = method, params = root["params"], raw = root)
            }
        }

        if (idElement != null && (root.containsKey("result") || root.containsKey("error"))) {
            val error = root["error"]?.takeUnless { it is JsonNull }?.let(::decodeError)
            return JsonRpcResponse(
                id = decodeId(idElement),
                result = root["result"],
                error = error,
                raw = root,
            )
        }

        throw JsonRpcCodecException("Object is not a JSON-RPC request, notification, or response")
    }

    fun encode(message: JsonRpcMessage): String = json.encodeToString(
        JsonObject.serializer(),
        when (message) {
            is JsonRpcRequest -> buildBase {
                put("id", encodeId(message.id))
                put("method", message.method)
                message.params?.let { put("params", it) }
            }
            is JsonRpcNotification -> buildBase {
                put("method", message.method)
                message.params?.let { put("params", it) }
            }
            is JsonRpcResponse -> buildBase {
                put("id", encodeId(message.id))
                if (message.error != null) {
                    put("error", encodeError(message.error))
                } else {
                    put("result", message.result ?: JsonNull)
                }
            }
        },
    )

    private fun buildBase(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        buildJsonObject {
            if (includeVersion) put("jsonrpc", "2.0")
            block()
        }

    private fun decodeId(element: JsonElement): JsonRpcId {
        if (element is JsonNull) return JsonRpcId.NullId
        val primitive = element as? JsonPrimitive
            ?: throw JsonRpcCodecException("JSON-RPC id must be a string, integer, or null")
        if (primitive.isString) return JsonRpcId.StringId(primitive.content)
        return primitive.longOrNull?.let(JsonRpcId::NumberId)
            ?: throw JsonRpcCodecException("JSON-RPC numeric id must be an integer")
    }

    private fun encodeId(id: JsonRpcId): JsonElement = when (id) {
        is JsonRpcId.StringId -> JsonPrimitive(id.value)
        is JsonRpcId.NumberId -> JsonPrimitive(id.value)
        JsonRpcId.NullId -> JsonNull
    }

    private fun decodeError(element: JsonElement): JsonRpcError {
        val error = element as? JsonObject ?: throw JsonRpcCodecException("JSON-RPC error must be an object")
        val code = error["code"]?.jsonPrimitive?.longOrNull?.toInt()
            ?: throw JsonRpcCodecException("JSON-RPC error code must be an integer")
        val message = error["message"]?.jsonPrimitive?.contentOrNull
            ?: throw JsonRpcCodecException("JSON-RPC error message must be a string")
        return JsonRpcError(code = code, message = message, data = error["data"])
    }

    private fun encodeError(error: JsonRpcError): JsonObject = buildJsonObject {
        put("code", error.code)
        put("message", error.message)
        error.data?.let { put("data", it) }
    }
}
