package me.haroldmartin.einkui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexPresentationModelTest {
    @Test
    fun `diff text includes stable line columns and a monochrome marker`() {
        val added = EinkDiffLine(
            text = "val connected = true",
            kind = EinkDiffLineKind.Added,
            oldLineNumber = null,
            newLineNumber = 12,
        )

        assertEquals("       12 +val connected = true", added.renderedText())
    }

    @Test
    fun `layout defaults satisfy BOOX touch and adaptive pane requirements`() {
        assertEquals(48.dp, EinkThemeDefaults.layout.minimumTouchTarget)
        assertEquals(840.dp, EinkThemeDefaults.layout.twoPaneBreakpoint)
    }
}
