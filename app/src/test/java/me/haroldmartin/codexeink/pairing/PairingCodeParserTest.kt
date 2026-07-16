package me.haroldmartin.codexeink.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingCodeParserTest {
    @Test
    fun `raw code is trimmed and normalized`() {
        assertEquals("ABCD-1234", PairingCodeParser.parse("  abcd-1234  "))
        assertEquals("CODE WITH SPACE", PairingCodeParser.parse("code with space"))
        assertEquals("ABC_DEF", PairingCodeParser.parse("abc_def"))
    }

    @Test
    fun `invalid raw values are rejected`() {
        assertNull(PairingCodeParser.parse(""))
        assertNull(PairingCodeParser.parse("short"))
        assertNull(PairingCodeParser.parse("bad:value"))
        assertNull(PairingCodeParser.parse("x".repeat(257)))
    }

    @Test
    fun `pairing URI reads supported query parameter names`() {
        assertEquals(
            "PAIR-1234",
            PairingCodeParser.parse("codex-eink://pair?pairing_code=pair-1234"),
        )
        assertEquals(
            "MANUAL99",
            PairingCodeParser.parse("https://example.test/pair?manual_code=manual99"),
        )
        assertEquals(
            "CAMEL777",
            PairingCodeParser.parse("codex-eink://pair?pairingCode=camel777"),
        )
    }

    @Test
    fun `pairing URI falls back to a code in the last path segment`() {
        assertEquals(
            "PATH-1234",
            PairingCodeParser.parse("codex-eink://pair/device/path-1234"),
        )
    }

    @Test
    fun `pairing URI rejects missing or invalid codes`() {
        assertNull(PairingCodeParser.parse("codex-eink://pair?code=tiny"))
        assertNull(PairingCodeParser.parse("codex-eink://pair?code=bad%3Avalue"))
        assertNull(PairingCodeParser.parse("codex-eink://pair"))
    }
}
