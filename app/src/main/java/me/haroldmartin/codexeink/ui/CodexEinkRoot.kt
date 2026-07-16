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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.haroldmartin.codexeink.ApprovalUi
import me.haroldmartin.codexeink.CodexUiState
import me.haroldmartin.codexeink.Connectivity
import me.haroldmartin.codexeink.QuestionUi
import me.haroldmartin.codexeink.ThreadUi
import me.haroldmartin.codexeink.TimelineKind
import me.haroldmartin.codexeink.TimelineUi
import me.haroldmartin.codexeink.data.ConnectionProfile
import me.haroldmartin.codexeink.data.TransportMode
import me.haroldmartin.codexeink.pairing.PairingCodeParser
import me.haroldmartin.einkui.EinkAdaptivePaneLayout
import me.haroldmartin.einkui.EinkButton
import me.haroldmartin.einkui.EinkButtonEmphasis
import me.haroldmartin.einkui.EinkApprovalPanelShell
import me.haroldmartin.einkui.EinkCheckboxRow
import me.haroldmartin.einkui.EinkConnectionBanner
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
    onPair: (String) -> Unit,
    onScanQr: () -> Unit,
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
            onSaveProfile = {
                onSaveProfile(it)
                showSetup = false
            },
            onPair = {
                onPair(it)
                showSetup = false
            },
            onScanQr = onScanQr,
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
    onPair: (String) -> Unit,
    onScanQr: () -> Unit,
    onAlwaysConnectedChange: (Boolean) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var managedMode by remember { mutableStateOf(true) }
    var pairingCode by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("My Codex host") }
    var endpoint by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(EinkTheme.layout.screenPadding),
        verticalArrangement = Arrangement.spacedBy(EinkTheme.spacing.medium),
    ) {
        Text("Codex Eink", style = EinkTheme.typography.title)
        Text(
            "Pair with Codex Remote, or use the authenticated direct transport for diagnostics.",
            style = EinkTheme.typography.body,
        )
        ConnectionBanner(state.connectivity, state.connectionMessage)
        Row(horizontalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small)) {
            EinkButton(
                onClick = { managedMode = true },
                emphasis = if (managedMode) EinkButtonEmphasis.Strong else EinkButtonEmphasis.Standard,
            ) { Text("Managed Remote") }
            EinkButton(
                onClick = { managedMode = false },
                emphasis = if (!managedMode) EinkButtonEmphasis.Strong else EinkButtonEmphasis.Standard,
            ) { Text("Direct diagnostic") }
        }
        if (managedMode) {
            EinkTextField(
                value = pairingCode,
                onValueChange = { pairingCode = it },
                label = "Pairing code or QR contents",
                supportingText = "Generate this from Set up Remote on the Codex host.",
                singleLine = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small)) {
                EinkButton(onClick = onScanQr) { Text("Scan QR") }
                EinkButton(
                    enabled = PairingCodeParser.parse(pairingCode) != null,
                    emphasis = EinkButtonEmphasis.Strong,
                    onClick = { PairingCodeParser.parse(pairingCode)?.let(onPair) },
                ) { Text("Pair") }
            }
        } else {
            EinkTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = "Host name",
                singleLine = true,
            )
            EinkTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = "Authenticated WebSocket endpoint",
                supportingText = "Use wss://, or a Tailscale/loopback ws:// address in debug builds.",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
            )
            EinkTextField(
                value = token,
                onValueChange = { token = it },
                label = "Capability token",
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
            ) { Text("Save and connect") }
        }
        EinkCheckboxRow(
            checked = alwaysConnected,
            onCheckedChange = onAlwaysConnectedChange,
            enabled = canEnableAlwaysConnected,
        ) { Text("Keep connected in the background") }
        if (!canEnableAlwaysConnected) {
            Text("Save or pair a host before enabling background connection.", style = EinkTheme.typography.supporting)
        }
        Text(
            "Credentials are encrypted with Android Keystore and excluded from backup.",
            style = EinkTheme.typography.supporting,
        )
        onCancel?.let { EinkButton(onClick = it) { Text("Cancel") } }
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
                Text("Codex Eink", style = EinkTheme.typography.title)
                Text(state.environmentName ?: "No host", style = EinkTheme.typography.supporting)
            }
            EinkButton(onClick = onRefresh, enabled = !state.loading) { Text("Refresh") }
            EinkButton(onClick = onSetup) { Text("Connection") }
        }
        ConnectionBanner(state.connectivity, state.connectionMessage)
        Row(horizontalArrangement = Arrangement.spacedBy(EinkTheme.spacing.small)) {
            EinkCheckboxRow(
                checked = alwaysConnected,
                onCheckedChange = onAlwaysConnectedChange,
                modifier = Modifier.weight(1f),
            ) { Text("Always connected") }
            EinkButton(onClick = { onDisconnect(false) }) { Text("Disconnect") }
            EinkButton(onClick = { confirmForget = true }) { Text("Forget host") }
        }
        state.error?.let { Text(it, style = EinkTheme.typography.supporting) }
    }
    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text("Forget this host?") },
            text = { Text("The encrypted endpoint and capability token will be removed from this device.") },
            confirmButton = {
                EinkButton(
                    onClick = {
                        confirmForget = false
                        onDisconnect(true)
                    },
                ) { Text("Forget") }
            },
            dismissButton = {
                EinkButton(onClick = { confirmForget = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ConnectionBanner(connectivity: Connectivity, message: String) {
    EinkConnectionBanner(
        title = connectivity.name.replaceFirstChar(Char::uppercase),
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
            item { Text("No tasks available.", style = EinkTheme.typography.body) }
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
                    if (thread.active) Text("Active", style = EinkTheme.typography.supporting)
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
    Column(modifier = Modifier.fillMaxSize()) {
        EinkButton(onClick = onBack) { Text("Back to tasks") }
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
                label = if (state.activeTurn) "Steer active task" else "Message Codex",
            )
            EinkButton(
                enabled = composer.isNotBlank(),
                emphasis = EinkButtonEmphasis.Strong,
                onClick = {
                    onSend(composer.trim())
                    composer = ""
                },
            ) { Text("Send") }
            if (state.activeTurn) {
                EinkButton(onClick = onInterrupt) { Text("Stop") }
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
    var confirmDecision by remember(approval.requestId) { mutableStateOf<String?>(null) }
    val detailsScroll = rememberScrollState()
    EinkApprovalPanelShell(
        title = approval.title,
        badgeLabel = "Approval",
        description = approval.reason,
        cautionText = "Review the exact command, paths, network host, and persistence before approving.",
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
        decisionActions = {
            approval.availableDecisions.sortedBy(::decisionRisk).forEach { decision ->
                EinkButton(
                    emphasis = if (decisionRisk(decision) == 0) {
                        EinkButtonEmphasis.Strong
                    } else {
                        EinkButtonEmphasis.Standard
                    },
                    onClick = {
                        if (requiresConfirmation(decision)) confirmDecision = decision
                        else onDecision(approval.requestId, decision)
                    },
                ) { Text(decisionLabel(decision)) }
            }
        },
    )
    confirmDecision?.let { decision ->
        AlertDialog(
            onDismissRequest = { confirmDecision = null },
            title = { Text("Confirm persistent approval") },
            text = { Text("This decision can grant access beyond this single action: ${decisionLabel(decision)}") },
            confirmButton = {
                EinkButton(
                    onClick = {
                        onDecision(approval.requestId, decision)
                        confirmDecision = null
                    },
                ) { Text("Confirm") }
            },
            dismissButton = {
                EinkButton(onClick = { confirmDecision = null }) { Text("Cancel") }
            },
        )
    }
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
            Text("Codex needs input", style = EinkTheme.typography.title)
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
                    label = "Answer",
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
            ) { Text("Submit answers") }
        }
    }
}

private fun decisionRisk(decision: String): Int = when {
    decision.contains("decline", ignoreCase = true) ||
        decision.contains("cancel", ignoreCase = true) -> 0
    requiresConfirmation(decision) -> 2
    else -> 1
}

private fun requiresConfirmation(decision: String): Boolean =
    decision.contains("session", ignoreCase = true) ||
        decision.contains("amendment", ignoreCase = true) ||
        decision.contains("permission", ignoreCase = true)

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

private fun parseDiffLines(diff: String): List<EinkDiffLine> {
    if (diff.isBlank()) return listOf(EinkDiffLine("No diff content was supplied."))
    return diff.lineSequence().map { line ->
        when {
            line.startsWith("@@") || line.startsWith("diff ") ||
                line.startsWith("---") || line.startsWith("+++") ->
                EinkDiffLine(line, EinkDiffLineKind.Header)
            line.startsWith("+") -> EinkDiffLine(line.drop(1), EinkDiffLineKind.Added)
            line.startsWith("-") -> EinkDiffLine(line.drop(1), EinkDiffLineKind.Removed)
            else -> EinkDiffLine(line.removePrefix(" "), EinkDiffLineKind.Context)
        }
    }.toList()
}
