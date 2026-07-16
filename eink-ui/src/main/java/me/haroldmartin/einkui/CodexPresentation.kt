package me.haroldmartin.einkui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow

/** A compact, non-interactive state label that never relies on color. */
@Composable
fun EinkStatusBadge(
    label: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
) {
    val shape = EinkTheme.shapes.control
    Text(
        text = label,
        modifier = modifier
            .background(EinkTheme.colors.surface, shape)
            .border(
                width = if (emphasized) EinkTheme.borders.strong else EinkTheme.borders.standard,
                color = EinkTheme.colors.outline,
                shape = shape,
            )
            .padding(
                horizontal = EinkTheme.spacing.small,
                vertical = EinkTheme.spacing.extraSmall,
            ),
        color = EinkTheme.colors.content,
        fontWeight = if (emphasized) FontWeight.Bold else FontWeight.Medium,
        style = EinkTheme.typography.supporting,
    )
}

/** A timeline item without an outer border, suitable for callers that own list separators. */
@Composable
@Suppress("LongParameterList")
fun EinkTimelineRow(
    marker: String,
    title: String,
    modifier: Modifier = Modifier,
    status: String? = null,
    metadata: String? = null,
    emphasized: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EinkStatusBadge(label = marker, emphasized = emphasized)
            Text(
                text = title,
                modifier = Modifier.weight(1f).semantics { heading() },
                color = EinkTheme.colors.content,
                fontWeight = FontWeight.Bold,
                style = EinkTheme.typography.label,
            )
            if (status != null) {
                EinkStatusBadge(label = status, emphasized = emphasized)
            }
        }
        if (metadata != null) {
            Text(
                text = metadata,
                color = EinkTheme.colors.content,
                style = EinkTheme.typography.supporting,
            )
        }
        content()
    }
}

/** A bordered timeline item for mixed agent, user, command, and tool events. */
@Composable
@Suppress("LongParameterList")
fun EinkTimelineCard(
    marker: String,
    title: String,
    modifier: Modifier = Modifier,
    status: String? = null,
    metadata: String? = null,
    emphasized: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    EinkCard(
        modifier = modifier.fillMaxWidth(),
        borderWidth = if (emphasized) EinkTheme.borders.strong else EinkTheme.borders.standard,
    ) {
        EinkTimelineRow(
            marker = marker,
            title = title,
            status = status,
            metadata = metadata,
            emphasized = emphasized,
            modifier = Modifier.padding(EinkTheme.spacing.medium),
            content = content,
        )
    }
}

/** A live connection-state announcement with an optional caller-provided action. */
@Composable
fun EinkConnectionBanner(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    emphasized: Boolean = false,
    action: (@Composable () -> Unit)? = null,
) {
    EinkSurface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        borderWidth = if (emphasized) EinkTheme.borders.strong else EinkTheme.borders.standard,
    ) {
        Column(
            modifier = Modifier.padding(EinkTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = EinkTheme.colors.content,
                    fontWeight = FontWeight.Bold,
                    style = EinkTheme.typography.label,
                )
                if (action != null) {
                    action()
                }
            }
            if (message != null) {
                Text(
                    text = message,
                    color = EinkTheme.colors.content,
                    style = EinkTheme.typography.supporting,
                )
            }
        }
    }
}

enum class EinkApprovalScope {
    OneShot,
    Session,
    Persistent,
}

@Immutable
data class EinkApprovalDecision(
    val id: String,
    val label: String,
    val scope: EinkApprovalScope,
    val preferred: Boolean = false,
)

/** Approval panel that enforces secondary confirmation for every durable decision. */
@Composable
@Suppress("LongParameterList")
fun EinkApprovalPanelShell(
    title: String,
    decisions: List<EinkApprovalDecision>,
    onDecision: (String) -> Unit,
    confirmationTitle: String,
    confirmationText: String,
    confirmLabel: String,
    cancelLabel: String,
    modifier: Modifier = Modifier,
    badgeLabel: String? = null,
    description: String? = null,
    cautionText: String? = null,
    details: @Composable ColumnScope.() -> Unit = {},
) {
    var pendingDecision by remember(decisions) { mutableStateOf<EinkApprovalDecision?>(null) }
    EinkSurface(
        modifier = modifier.fillMaxWidth(),
        borderWidth = EinkTheme.borders.strong,
    ) {
        Column(
            modifier = Modifier.padding(EinkTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(EinkTheme.spacing.medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f).semantics { heading() },
                    color = EinkTheme.colors.content,
                    fontWeight = FontWeight.Bold,
                    style = EinkTheme.typography.title,
                )
                if (badgeLabel != null) {
                    EinkStatusBadge(label = badgeLabel, emphasized = true)
                }
            }
            if (description != null) {
                Text(
                    text = description,
                    color = EinkTheme.colors.content,
                    style = EinkTheme.typography.body,
                )
            }
            details()
            if (cautionText != null) {
                EinkSurface(modifier = Modifier.fillMaxWidth(), borderWidth = EinkTheme.borders.strong) {
                    Text(
                        text = cautionText,
                        modifier = Modifier.padding(EinkTheme.spacing.small),
                        color = EinkTheme.colors.content,
                        fontWeight = FontWeight.Bold,
                        style = EinkTheme.typography.supporting,
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small, Alignment.End),
                verticalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small),
            ) {
                decisions.forEach { decision ->
                    EinkButton(
                        emphasis = if (decision.preferred) {
                            EinkButtonEmphasis.Strong
                        } else {
                            EinkButtonEmphasis.Standard
                        },
                        onClick = {
                            if (decision.scope == EinkApprovalScope.OneShot) {
                                onDecision(decision.id)
                            } else {
                                pendingDecision = decision
                            }
                        },
                    ) { Text(decision.label) }
                }
            }
        }
    }
    pendingDecision?.let { decision ->
        EinkConfirmDialog(
            onDismissRequest = { pendingDecision = null },
            title = { Text(confirmationTitle) },
            text = { Text(confirmationText) },
            confirmButton = {
                EinkButton(
                    onClick = {
                        onDecision(decision.id)
                        pendingDecision = null
                    },
                ) { Text(confirmLabel) }
            },
            dismissButton = {
                EinkButton(onClick = { pendingDecision = null }) { Text(cancelLabel) }
            },
        )
    }
}

enum class EinkDiffLineKind(internal val marker: Char) {
    Context(' '),
    Added('+'),
    Removed('-'),
    Header('@'),
}

@Immutable
data class EinkDiffLine(
    val text: String,
    val kind: EinkDiffLineKind = EinkDiffLineKind.Context,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null,
)

internal fun EinkDiffLine.renderedText(lineNumberWidth: Int = DIFF_LINE_NUMBER_WIDTH): String {
    val oldNumber = oldLineNumber?.toString()?.padStart(lineNumberWidth) ?: " ".repeat(lineNumberWidth)
    val newNumber = newLineNumber?.toString()?.padStart(lineNumberWidth) ?: " ".repeat(lineNumberWidth)
    return "$oldNumber $newNumber ${kind.marker}$text"
}

internal fun diffLineNumberWidth(lines: List<EinkDiffLine>): Int = maxOf(
    DIFF_LINE_NUMBER_WIDTH,
    lines.maxOfOrNull { line ->
        maxOf(
            line.oldLineNumber?.toString()?.length ?: 0,
            line.newLineNumber?.toString()?.length ?: 0,
        )
    } ?: 0,
)

/** A selectable unified-diff block whose +, -, and @ markers carry meaning without color. */
@Composable
fun EinkDiffBlock(
    fileName: String,
    lines: List<EinkDiffLine>,
    modifier: Modifier = Modifier,
    status: String? = null,
) {
    val horizontalScroll = rememberScrollState()
    val lineNumberWidth = remember(lines) { diffLineNumberWidth(lines) }
    EinkSurface(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(EinkTheme.spacing.small),
                horizontalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = fileName,
                    modifier = Modifier.weight(1f),
                    color = EinkTheme.colors.content,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = EinkTheme.typography.label,
                )
                if (status != null) {
                    EinkStatusBadge(label = status)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(EinkTheme.colors.surface)
                    .horizontalScroll(horizontalScroll)
                    .padding(EinkTheme.spacing.small),
            ) {
                SelectionContainer {
                    Column {
                        lines.forEach { line ->
                            Text(
                                text = line.renderedText(lineNumberWidth),
                                color = EinkTheme.colors.content,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (line.kind == EinkDiffLineKind.Header) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                },
                                style = EinkTheme.typography.supporting,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Controlled terminal output disclosure; callers retain command lifecycle and expansion state. */
@Composable
fun EinkTerminalDisclosure(
    command: String,
    output: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    status: String? = null,
) {
    val horizontalScroll = rememberScrollState()
    EinkExpandableSection(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
        title = {
            Text(
                text = command,
                modifier = Modifier.weight(1f),
                color = EinkTheme.colors.content,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = EinkTheme.typography.label,
            )
            if (status != null) {
                EinkStatusBadge(label = status)
            }
        },
    ) {
        if (output.isNotEmpty()) {
            EinkSurface(modifier = Modifier.fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        text = output,
                        modifier = Modifier
                            .horizontalScroll(horizontalScroll)
                            .padding(EinkTheme.spacing.small),
                        color = EinkTheme.colors.content,
                        fontFamily = FontFamily.Monospace,
                        style = EinkTheme.typography.supporting,
                    )
                }
            }
        }
    }
}

private const val DIFF_LINE_NUMBER_WIDTH = 4
