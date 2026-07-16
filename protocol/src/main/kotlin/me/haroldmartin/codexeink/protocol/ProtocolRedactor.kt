package me.haroldmartin.codexeink.protocol

object ProtocolRedactor {
    const val REDACTED = "<redacted>"

    private val sensitiveHeaderNames = setOf(
        "authorization",
        "cookie",
        "set-cookie",
        "x-api-key",
        "x-codex-token",
        "x-codex-client-token",
    )

    private val sensitiveJsonKeys = listOf(
        "access_token",
        "refresh_token",
        "remote_control_token",
        "pairing_code",
        "manual_pairing_code",
        "installation_id",
        "client_id",
        "stream_id",
        "environment_id",
        "thread_id",
        "turn_id",
        "clientId",
        "streamId",
        "environmentId",
        "threadId",
        "turnId",
        "command",
        "aggregatedOutput",
        "outputDelta",
        "unifiedDiff",
        "message_chunk_base64",
    )

    private val bearer = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")
    private val header = Regex(
        "(?im)^(authorization|cookie|set-cookie|x-api-key|x-codex-token|x-codex-client-token)\\s*:\\s*.*$",
    )
    private val jsonValuePatterns = sensitiveJsonKeys.map { key ->
        Regex(
            pattern = "(\\\"${Regex.escape(key)}\\\"\\s*:\\s*)(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|-?\\d+(?:\\.\\d+)?|true|false|null)",
            option = RegexOption.IGNORE_CASE,
        )
    }
    private val queryValuePatterns = sensitiveJsonKeys.map { key ->
        Regex(
            pattern = "([?&;]|\\b)(${Regex.escape(key)}=)([^&#;\\s]*)",
            option = RegexOption.IGNORE_CASE,
        )
    }

    fun redact(text: String): String {
        var redacted = bearer.replace(text, "Bearer $REDACTED")
        redacted = header.replace(redacted) { match ->
            "${match.groupValues[1]}: $REDACTED"
        }
        jsonValuePatterns.forEach { pattern ->
            redacted = pattern.replace(redacted) { match ->
                "${match.groupValues[1]}\"$REDACTED\""
            }
        }
        queryValuePatterns.forEach { pattern ->
            redacted = pattern.replace(redacted) { match ->
                "${match.groupValues[1]}${match.groupValues[2]}$REDACTED"
            }
        }
        return redacted
    }

    fun redactHeaders(headers: Map<String, String>): Map<String, String> = headers.mapValues { (name, value) ->
        if (name.lowercase() in sensitiveHeaderNames) REDACTED else redact(value)
    }

    /** Returns metadata useful in logs without serializing params, results, command output, or diffs. */
    fun summarize(message: JsonRpcMessage): String = when (message) {
        is JsonRpcRequest -> "request method=${message.method} id=${summarizeId(message.id)}"
        is JsonRpcNotification -> "notification method=${message.method}"
        is JsonRpcResponse -> "response id=${summarizeId(message.id)} success=${message.isSuccess} code=${message.error?.code}"
    }

    private fun summarizeId(id: JsonRpcId): String = when (id) {
        is JsonRpcId.NumberId -> id.value.toString()
        is JsonRpcId.StringId -> REDACTED
        JsonRpcId.NullId -> "null"
    }
}
