package me.haroldmartin.codexeink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.haroldmartin.codexeink.ApprovalUi
import me.haroldmartin.codexeink.ApprovalScope
import me.haroldmartin.codexeink.CodexUiState
import me.haroldmartin.codexeink.Connectivity
import me.haroldmartin.codexeink.QuestionUi
import me.haroldmartin.codexeink.R
import me.haroldmartin.codexeink.ThreadUi
import me.haroldmartin.codexeink.TimelineKind
import me.haroldmartin.codexeink.TimelineUi
import me.haroldmartin.codexeink.labelResource
import me.haroldmartin.codexeink.data.ConnectionProfile
import me.haroldmartin.codexeink.data.TransportMode
import me.haroldmartin.einkui.EinkAdaptivePaneLayout
import me.haroldmartin.einkui.EinkButton
import me.haroldmartin.einkui.EinkButtonEmphasis
import me.haroldmartin.einkui.EinkApprovalPanelShell
import me.haroldmartin.einkui.EinkApprovalDecision
import me.haroldmartin.einkui.EinkApprovalScope
import me.haroldmartin.einkui.EinkCheckboxRow
import me.haroldmartin.einkui.EinkConnectionBanner
import me.haroldmartin.einkui.EinkConfirmDialog
import me.haroldmartin.einkui.EinkDiffBlock
import me.haroldmartin.einkui.EinkDiffLine
import me.haroldmartin.einkui.EinkDiffLineKind
import me.haroldmartin.einkui.EinkPane
import me.haroldmartin.einkui.EinkSurface
import me.haroldmartin.einkui.EinkTerminalDisclosure
import me.haroldmartin.einkui.EinkTextField
import me.haroldmartin.einkui.EinkTheme
import me.haroldmartin.einkui.EinkTimelineCard

@Composable
@Suppress("LongParameterList")
fun CodexEinkRoot(
    state: CodexUiState,
    hasStoredProfile: Boolean,
    alwaysConnected: Boolean,
    onSaveProfile: (ConnectionProfile) -> Unit,
    onAlwaysConnectedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSelectThread: (String) -> Unit,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onApproval: (String, String) -> Unit,
    onQuestion: (String, Map<String, String>) -> Unit,
    onDisconnect: (Boolean) -> Unit,
) {
    var showSetup by remember(hasStoredProfile) { mutableStateOf(!hasStoredProfile) }
    if (showSetup) {
        SetupScreen(
            state = state,
            alwaysConnected = alwaysConnected,
            canEnableAlwaysConnected = hasStoredProfile,
            onSaveProfile = onSaveProfile,
            onAlwaysConnectedChange = onAlwaysConnectedChange,
            onCancel = if (hasStoredProfile) ({ showSetup = false }) else null,
        )
        return
    }

    MainTaskScreen(
        state = state,
        alwaysConnected = alwaysConnected,
        onAlwaysConnectedChange = onAlwaysConnectedChange,
        onRefresh = onRefresh,
        onSelectThread = onSelectThread,
        onSend = onSend,
        onInterrupt = onInterrupt,
        onApproval = onApproval,
        onQuestion = onQuestion,
        onSetup = { showSetup = true },
        onDisconnect = onDisconnect,
    )
}

@Composable
@Suppress("LongMethod", "LongParameterList")
private fun SetupScreen(
    state: CodexUiState,
    alwaysConnected: Boolean,
    canEnableAlwaysConnected: Boolean,
    onSaveProfile: (ConnectionProfile) -> Unit,
    onAlwaysConnectedChange: (Boolean) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var managedMode by remember { mutableStateOf(false) }
    val defaultHostName = stringResource(R.string.default_host_name)
    var displayName by remember { mutableStateOf(defaultHostName) }
    var endpoint by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(EinkTheme.layout.screenPadding),
        verticalArrangement = Arrangement.spacedBy(EinkTheme.spacing.medium),
    ) {
        Text(stringResource(R.string.app_name), style = EinkTheme.typography.title)
        Text(
            stringResource(R.string.setup_intro),
            style = EinkTheme.typography.body,
        )
        ConnectionBanner(state.connectivity, state.connectionMessage)
        Row(horizontalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small)) {
            EinkButton(
                onClick = { managedMode = true },
                emphasis = if (managedMode) EinkButtonEmphasis.Strong else EinkButtonEmphasis.Standard,
            ) { Text(stringResource(R.string.managed_remote)) }
            EinkButton(
                onClick = { managedMode = false },
                emphasis = if (!managedMode) EinkButtonEmphasis.Strong else EinkButtonEmphasis.Standard,
            ) { Text(stringResource(R.string.direct_diagnostic)) }
        }
        if (managedMode) {
            Text(
                stringResource(R.string.managed_remote_unavailable),
                style = EinkTheme.typography.body,
            )
        } else {
            EinkTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = stringResource(R.string.host_name),
                singleLine = true,
            )
            EinkTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = stringResource(R.string.websocket_endpoint),
                supportingText = stringResource(R.string.websocket_endpoint_help),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
            )
            EinkTextField(
                value = token,
                onValueChange = { token = it },
                label = stringResource(R.string.capability_token),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            EinkButton(
                enabled = displayName.isNotBlank() && endpoint.isNotBlank() && token.isNotBlank(),
                emphasis = EinkButtonEmphasis.Strong,
                onClick = {
                    onSaveProfile(
                        ConnectionProfile(
                            displayName = displayName.trim(),
                            endpoint = endpoint.trim(),
                            credential = token,
                            mode = TransportMode.DirectDiagnostic,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.save_and_connect)) }
        }
        EinkCheckboxRow(
            checked = alwaysConnected,
            onCheckedChange = onAlwaysConnectedChange,
            enabled = canEnableAlwaysConnected,
        ) { Text(stringResource(R.string.keep_connected_background)) }
        if (!canEnableAlwaysConnected) {
            Text(stringResource(R.string.background_requires_host), style = EinkTheme.typography.supporting)
        }
        Text(
            stringResource(R.string.credentials_encrypted),
            style = EinkTheme.typography.supporting,
        )
        onCancel?.let { EinkButton(onClick = it) { Text(stringResource(R.string.cancel)) } }
    }
}

@Composable
@Suppress("LongParameterList")
private fun MainTaskScreen(
    state: CodexUiState,
    alwaysConnected: Boolean,
    onAlwaysConnectedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSelectThread: (String) -> Unit,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onApproval: (String, String) -> Unit,
    onQuestion: (String, Map<String, String>) -> Unit,
    onSetup: () -> Unit,
    onDisconnect: (Boolean) -> Unit,
) {
    var activePane by remember { mutableStateOf(EinkPane.Primary) }
    Column(modifier = Modifier.fillMaxSize().padding(EinkTheme.layout.screenPadding)) {
        Header(
            state = state,
            alwaysConnected = alwaysConnected,
            onAlwaysConnectedChange = onAlwaysConnectedChange,
            onRefresh = onRefresh,
            onSetup = onSetup,
            onDisconnect = onDisconnect,
        )
        EinkAdaptivePaneLayout(
            modifier = Modifier.fillMaxSize().padding(top = EinkTheme.spacing.medium),
            activePane = activePane,
            primaryPane = {
                ThreadList(
                    threads = state.threads,
                    selectedThreadId = state.selectedThreadId,
                    onSelect = {
                        onSelectThread(it)
                        activePane = EinkPane.Secondary
                    },
                )
            },
            secondaryPane = {
                ThreadDetail(
                    state = state,
                    onBack = { activePane = EinkPane.Primary },
                    onSend = onSend,
                    onInterrupt = onInterrupt,
                    onApproval = onApproval,
                    onQuestion = onQuestion,
                )
            },
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun Header(
    state: CodexUiState,
    alwaysConnected: Boolean,
    onAlwaysConnectedChange: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSetup: () -> Unit,
    onDisconnect: (Boolean) -> Unit,
) {
    var confirmForget by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.app_name), style = EinkTheme.typography.title)
                Text(state.environmentName ?: stringResource(R.string.no_host), style = EinkTheme.typography.supporting)
            }
            EinkButton(onClick = onRefresh, enabled = !state.loading) { Text(stringResource(R.string.refresh)) }
            EinkButton(onClick = onSetup) { Text(stringResource(R.string.connection)) }
        }
        ConnectionBanner(state.connectivity, state.connectionMessage)
        Row(horizontalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small)) {
            EinkCheckboxRow(
                checked = alwaysConnected,
                onCheckedChange = onAlwaysConnectedChange,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.always_connected)) }
            EinkButton(onClick = { onDisconnect(false) }) { Text(stringResource(R.string.disconnect)) }
            EinkButton(onClick = { confirmForget = true }) { Text(stringResource(R.string.forget_host)) }
        }
        state.error?.let { Text(it, style = EinkTheme.typography.supporting) }
    }
    if (confirmForget) {
        EinkConfirmDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.forget_host_title)) },
            text = { Text(stringResource(R.string.forget_host_message)) },
            confirmButton = {
                EinkButton(
                    onClick = {
                        confirmForget = false
                        onDisconnect(true)
                    },
                ) { Text(stringResource(R.string.forget)) }
            },
            dismissButton = {
                EinkButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun ConnectionBanner(connectivity: Connectivity, message: String) {
    EinkConnectionBanner(
        title = stringResource(connectivity.labelResource),
        message = message,
        modifier = Modifier.fillMaxWidth(),
        emphasized = connectivity != Connectivity.Connected,
    )
}

@Composable
private fun ThreadList(
    threads: List<ThreadUi>,
    selectedThreadId: String?,
    onSelect: (String) -> Unit,
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small)) {
        if (threads.isEmpty()) {
            item { Text(stringResource(R.string.no_tasks), style = EinkTheme.typography.body) }
        }
        items(threads, key = ThreadUi::id) { thread ->
            EinkButton(
                modifier = Modifier.fillMaxWidth(),
                emphasis = if (thread.id == selectedThreadId) {
                    EinkButtonEmphasis.Strong
                } else {
                    EinkButtonEmphasis.Standard
                },
                onClick = { onSelect(thread.id) },
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(thread.title, style = EinkTheme.typography.label)
                    Text(thread.subtitle, style = EinkTheme.typography.supporting)
                    if (thread.active) Text(stringResource(R.string.active), style = EinkTheme.typography.supporting)
                }
            }
        }
    }
}

@Composable
@Suppress("LongParameterList")
private fun ThreadDetail(
    state: CodexUiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onApproval: (String, String) -> Unit,
    onQuestion: (String, Map<String, String>) -> Unit,
) {
    var composer by remember(state.selectedThreadId) { mutableStateOf("") }
    var pendingSendSequence by remember(state.selectedThreadId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(state.sentMessageSequence) {
        val pending = pendingSendSequence
        if (pending != null && state.sentMessageSequence > pending) {
            composer = ""
            pendingSendSequence = null
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        EinkButton(onClick = onBack) { Text(stringResource(R.string.back_to_tasks)) }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small),
        ) {
            items(state.timeline, key = TimelineUi::id) { item -> TimelineRow(item) }
        }
        state.pendingApproval?.let { approval ->
            ApprovalPanel(approval = approval, onDecision = onApproval)
        }
        state.pendingQuestion?.let { question ->
            QuestionPanel(question = question, onAnswer = onQuestion)
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = EinkTheme.spacing.small),
            horizontalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small),
        ) {
            EinkTextField(
                value = composer,
                onValueChange = { composer = it },
                modifier = Modifier.weight(1f),
                label = stringResource(
                    if (state.activeTurn) R.string.steer_active_task else R.string.message_codex,
                ),
            )
            EinkButton(
                enabled = composer.isNotBlank() && !state.sendingMessage,
                emphasis = EinkButtonEmphasis.Strong,
                onClick = {
                    pendingSendSequence = state.sentMessageSequence
                    onSend(composer.trim())
                },
            ) { Text(stringResource(R.string.send)) }
            if (state.activeTurn) {
                EinkButton(onClick = onInterrupt) { Text(stringResource(R.string.stop)) }
            }
        }
    }
}

@Composable
private fun TimelineRow(item: TimelineUi) {
    when (item.kind) {
        TimelineKind.Command -> {
            var expanded by remember(item.id) { mutableStateOf(false) }
            EinkTerminalDisclosure(
                command = item.body,
                output = item.detail.orEmpty(),
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth(),
                status = item.status,
            )
        }
        TimelineKind.FileChange -> EinkDiffBlock(
            fileName = item.title,
            lines = parseDiffLines(item.body + item.detail.orEmpty()),
            modifier = Modifier.fillMaxWidth(),
            status = item.status,
        )
        else -> EinkTimelineCard(
            marker = timelineMarker(item.kind),
            title = item.title,
            status = item.status,
            emphasized = item.kind == TimelineKind.Error,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                item.body,
                style = EinkTheme.typography.body,
            )
            item.detail?.let {
                Text(it, style = EinkTheme.typography.supporting.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}

@Composable
private fun ApprovalPanel(approval: ApprovalUi, onDecision: (String, String) -> Unit) {
    val detailsScroll = rememberScrollState()
    val decisions = approval.availableDecisions
        .sortedBy(::decisionRisk)
        .map { decision ->
            EinkApprovalDecision(
                id = decision.value,
                label = decisionLabel(decision.value),
                scope = when (decision.scope) {
                    ApprovalScope.OneShot -> EinkApprovalScope.OneShot
                    ApprovalScope.Session -> EinkApprovalScope.Session
                    ApprovalScope.Persistent -> EinkApprovalScope.Persistent
                },
                preferred = decisionRisk(decision) == 0,
            )
        }
    EinkApprovalPanelShell(
        title = approval.title,
        decisions = decisions,
        onDecision = { decision -> onDecision(approval.requestId, decision) },
        confirmationTitle = stringResource(R.string.confirm_durable_approval),
        confirmationText = stringResource(R.string.confirm_durable_approval_message),
        confirmLabel = stringResource(R.string.confirm),
        cancelLabel = stringResource(R.string.cancel),
        badgeLabel = stringResource(R.string.approval),
        description = approval.reason,
        cautionText = stringResource(R.string.approval_caution),
        details = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .verticalScroll(detailsScroll),
            ) {
                Text(
                    approval.commandOrDiff,
                    style = EinkTheme.typography.body.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
    )
}

@Composable
private fun QuestionPanel(question: QuestionUi, onAnswer: (String, Map<String, String>) -> Unit) {
    val answers = remember(question.requestId) { mutableStateMapOf<String, String>() }
    EinkSurface(modifier = Modifier.fillMaxWidth(), borderWidth = EinkTheme.borders.strong) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .padding(EinkTheme.spacing.medium),
            verticalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small),
        ) {
            Text(stringResource(R.string.codex_needs_input), style = EinkTheme.typography.title)
            question.questions.forEach { field ->
                Text(field.prompt, style = EinkTheme.typography.label)
                if (field.options.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small)) {
                        field.options.forEach { option ->
                            EinkButton(
                                modifier = Modifier.fillMaxWidth(),
                                emphasis = if (answers[field.id] == option) {
                                    EinkButtonEmphasis.Strong
                                } else {
                                    EinkButtonEmphasis.Standard
                                },
                                onClick = { answers[field.id] = option },
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(option)
                                    field.optionDescriptions[option]?.let { description ->
                                        Text(description, style = EinkTheme.typography.supporting)
                                    }
                                }
                            }
                        }
                    }
                }
                EinkTextField(
                    value = answers[field.id].orEmpty(),
                    onValueChange = { answers[field.id] = it },
                    label = stringResource(R.string.answer),
                    visualTransformation = if (field.secret) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                )
            }
            EinkButton(
                enabled = question.questions.all { answers[it.id].orEmpty().isNotBlank() },
                emphasis = EinkButtonEmphasis.Strong,
                onClick = { onAnswer(question.requestId, answers.toMap()) },
            ) { Text(stringResource(R.string.submit_answers)) }
        }
    }
}

private fun decisionRisk(decision: me.haroldmartin.codexeink.ApprovalDecisionUi): Int = when {
    decision.value == "deny" || decision.value == "decline" || decision.value == "cancel" -> 0
    decision.scope != ApprovalScope.OneShot -> 2
    else -> 1
}

private fun decisionLabel(decision: String): String = decision
    .replace(Regex("([a-z])([A-Z])"), "$1 $2")
    .replaceFirstChar(Char::uppercase)

private fun timelineMarker(kind: TimelineKind): String = when (kind) {
    TimelineKind.User -> "YOU"
    TimelineKind.Agent -> "AI"
    TimelineKind.Plan -> "PLAN"
    TimelineKind.Reasoning -> "WHY"
    TimelineKind.Tool -> "TOOL"
    TimelineKind.Web -> "WEB"
    TimelineKind.Collaboration -> "TEAM"
    TimelineKind.Error -> "ERR"
    TimelineKind.Unknown -> "?"
    TimelineKind.Command -> "CMD"
    TimelineKind.FileChange -> "DIFF"
}

internal fun parseDiffLines(diff: String): List<EinkDiffLine> {
    if (diff.isBlank()) return listOf(EinkDiffLine("No diff content was supplied."))
    val lines = diff.lineSequence().iterator()
    val rendered = ArrayList<EinkDiffLine>(MAX_RENDERED_DIFF_LINES + 1)
    repeat(MAX_RENDERED_DIFF_LINES) {
        if (!lines.hasNext()) return rendered
        rendered += parseDiffLine(lines.next())
    }
    if (lines.hasNext()) {
        rendered += EinkDiffLine(
            "Additional diff lines are not rendered on this device.",
            EinkDiffLineKind.Header,
        )
    }
    return rendered
}

private fun parseDiffLine(rawLine: String): EinkDiffLine {
    val line = if (rawLine.length <= MAX_RENDERED_DIFF_LINE_CHARS) {
        rawLine
    } else {
        rawLine.take(MAX_RENDERED_DIFF_LINE_CHARS) + "…"
    }
    return when {
        line.startsWith("@@") || line.startsWith("diff ") ||
            line.startsWith("---") || line.startsWith("+++") ->
            EinkDiffLine(line, EinkDiffLineKind.Header)
        line.startsWith("+") -> EinkDiffLine(line.drop(1), EinkDiffLineKind.Added)
        line.startsWith("-") -> EinkDiffLine(line.drop(1), EinkDiffLineKind.Removed)
        else -> EinkDiffLine(line.removePrefix(" "), EinkDiffLineKind.Context)
    }
}

private const val MAX_RENDERED_DIFF_LINES = 400
private const val MAX_RENDERED_DIFF_LINE_CHARS = 4_000
