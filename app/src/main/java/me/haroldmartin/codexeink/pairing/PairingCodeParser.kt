package me.haroldmartin.codexeink.pairing

import android.net.Uri

object PairingCodeParser {
    private val codeKeys = listOf("pairing_code", "pairingCode", "manual_code", "code")

    fun parse(rawValue: String): String? {
        val value = rawValue.trim()
        if (value.isEmpty()) return null

        val uri = runCatching { Uri.parse(value) }.getOrNull()
        if (uri?.scheme != null) {
            codeKeys.firstNotNullOfOrNull { key -> uri.getQueryParameter(key) }
                ?.takeIf(::looksLikeCode)
                ?.let { return normalize(it) }
            uri.lastPathSegment?.takeIf(::looksLikeCode)?.let { return normalize(it) }
        }
        return value.takeIf(::looksLikeCode)?.let(::normalize)
    }

    private fun normalize(value: String): String = value.trim().uppercase()

    private fun looksLikeCode(value: String): Boolean {
        val compact = value.replace("-", "").replace(" ", "")
        return compact.length in MIN_CODE_LENGTH..MAX_CODE_LENGTH &&
            compact.all { it.isLetterOrDigit() || it == '_' }
    }

    private const val MIN_CODE_LENGTH = 6
    private const val MAX_CODE_LENGTH = 256
}
