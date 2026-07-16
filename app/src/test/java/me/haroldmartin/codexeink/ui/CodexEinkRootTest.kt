package me.haroldmartin.codexeink.ui

import me.haroldmartin.einkui.EinkDiffLineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexEinkRootTest {
    @Test
    fun `diff rendering is bounded and reports omitted lines`() {
        val diff = (0 until 500).joinToString("\n") { index -> "+line $index" }

        val rendered = parseDiffLines(diff)

        assertEquals(401, rendered.size)
        assertEquals(EinkDiffLineKind.Added, rendered.first().kind)
        assertEquals("line 0", rendered.first().text)
        assertEquals(EinkDiffLineKind.Header, rendered.last().kind)
        assertTrue(rendered.last().text.contains("not rendered"))
    }

    @Test
    fun `individual diff lines are bounded`() {
        val rendered = parseDiffLines("+" + "x".repeat(5_000)).single()

        assertEquals(EinkDiffLineKind.Added, rendered.kind)
        assertTrue(rendered.text.length <= 4_001)
        assertTrue(rendered.text.endsWith("…"))
    }
}
