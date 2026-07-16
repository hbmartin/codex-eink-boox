package me.haroldmartin.codexeink

import kotlinx.coroutines.flow.StateFlow
import me.haroldmartin.codexeink.data.ConnectionProfile

interface CodexController {
    val state: StateFlow<CodexUiState>

    suspend fun connectStored()

    suspend fun saveAndConnect(profile: ConnectionProfile)

    suspend fun pair(pairingCode: String)

    suspend fun refreshThreads()

    suspend fun selectThread(threadId: String)

    suspend fun send(text: String)

    suspend fun interrupt()

    suspend fun answerApproval(requestId: String, decision: String)

    suspend fun answerQuestion(requestId: String, answers: Map<String, String>)

    suspend fun disconnect(forgetDevice: Boolean)
}

enum class Connectivity {
    Disconnected,
    Connecting,
    Connected,
    Reconnecting,
    HostOffline,
    SignedOut,
    Revoked,
    Incompatible,
    Failed,
}

data class CodexUiState(
    val connectivity: Connectivity = Connectivity.Disconnected,
    val connectionMessage: String = "Not connected",
    val environmentName: String? = null,
    val threads: List<ThreadUi> = emptyList(),
    val selectedThreadId: String? = null,
    val timeline: List<TimelineUi> = emptyList(),
    val activeTurn: Boolean = false,
    val pendingApproval: ApprovalUi? = null,
    val pendingQuestion: QuestionUi? = null,
    val loading: Boolean = false,
    val sendingMessage: Boolean = false,
    val sentMessageSequence: Long = 0,
    val error: String? = null,
)

data class ThreadUi(
    val id: String,
    val title: String,
    val subtitle: String,
    val active: Boolean,
    val updatedAt: Long?,
)

enum class TimelineKind {
    User,
    Agent,
    Plan,
    Reasoning,
    Command,
    FileChange,
    Tool,
    Web,
    Collaboration,
    Error,
    Unknown,
}

data class TimelineUi(
    val id: String,
    val kind: TimelineKind,
    val title: String,
    val body: String,
    val status: String? = null,
    val detail: String? = null,
)

data class ApprovalUi(
    val requestId: String,
    val title: String,
    val reason: String?,
    val commandOrDiff: String,
    val availableDecisions: List<ApprovalDecisionUi>,
)

enum class ApprovalScope {
    OneShot,
    Session,
    Persistent,
}

data class ApprovalDecisionUi(
    val value: String,
    val scope: ApprovalScope,
)

data class QuestionUi(
    val requestId: String,
    val questions: List<QuestionFieldUi>,
)

data class QuestionFieldUi(
    val id: String,
    val prompt: String,
    val options: List<String>,
    val optionDescriptions: Map<String, String> = emptyMap(),
    val secret: Boolean = false,
)
