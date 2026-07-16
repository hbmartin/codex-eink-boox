package me.haroldmartin.einkui

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodexPresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun timelineAndConnectionStateRemainVisibleWithoutColor() {
        composeRule.setContent {
            EinkTheme {
                EinkTimelineCard(
                    marker = "AGENT",
                    title = "Implemented pairing",
                    status = "DONE",
                ) {
                    Text("Three files changed")
                }
            }
        }

        composeRule.onNodeWithText("AGENT").assertIsDisplayed()
        composeRule.onNodeWithText("DONE").assertIsDisplayed()
        composeRule.onNodeWithText("Three files changed").assertIsDisplayed()
    }

    @Test
    fun approvalActionsKeepMinimumTouchTargets() {
        composeRule.setContent {
            EinkTheme {
                EinkApprovalPanelShell(
                    title = "Run command?",
                    cautionText = "The command can modify files.",
                    decisionActions = {
                        EinkButton(onClick = {}) { Text("Decline") }
                        EinkButton(onClick = {}, emphasis = EinkButtonEmphasis.Strong) { Text("Approve") }
                    },
                )
            }
        }

        composeRule.onNodeWithText("Decline").assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithText("Approve").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun terminalDisclosureIsControlledAndUsesAFullTouchTarget() {
        composeRule.setContent {
            val expanded = remember { mutableStateOf(false) }
            EinkTheme {
                EinkTerminalDisclosure(
                    command = "./gradlew test",
                    output = "BUILD SUCCESSFUL",
                    expanded = expanded.value,
                    onExpandedChange = { expanded.value = it },
                )
            }
        }

        composeRule.onNodeWithText("./gradlew test")
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        composeRule.onNodeWithText("BUILD SUCCESSFUL").assertIsDisplayed()
    }

    @Test
    fun diffUsesTextMarkersForAddedAndRemovedLines() {
        composeRule.setContent {
            EinkTheme {
                EinkDiffBlock(
                    fileName = "Connection.kt",
                    lines = listOf(
                        EinkDiffLine("old", EinkDiffLineKind.Removed, oldLineNumber = 1),
                        EinkDiffLine("new", EinkDiffLineKind.Added, newLineNumber = 1),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("-old", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("+new", substring = true).assertIsDisplayed()
    }
}
