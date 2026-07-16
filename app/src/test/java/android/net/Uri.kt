package android.net

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Narrow JVM stand-in for the Android methods used by PairingCodeParser.
 *
 * Android's local-unit-test jar throws for every Uri method. Keeping this fake in the test source
 * set lets the parser's URI branch be tested without adding Robolectric to the application.
 */
class Uri private constructor(private val delegate: URI) {
    val scheme: String?
        get() = delegate.scheme

    val lastPathSegment: String?
        get() = delegate.rawPath
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotEmpty)
            ?.let(::decode)

    fun getQueryParameter(key: String): String? = delegate.rawQuery
        ?.split('&')
        ?.asSequence()
        ?.map { pair -> pair.substringBefore('=') to pair.substringAfter('=', missingDelimiterValue = "") }
        ?.firstOrNull { (rawKey, _) -> decode(rawKey) == key }
        ?.second
        ?.let(::decode)

    companion object {
        @JvmStatic
        fun parse(value: String): Uri = Uri(URI(value))

        private fun decode(value: String): String = URLDecoder.decode(
            value,
            StandardCharsets.UTF_8.name(),
        )
    }
}
