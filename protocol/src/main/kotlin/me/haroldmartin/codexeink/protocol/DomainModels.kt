package me.haroldmartin.codexeink.protocol

/**
 * UI-oriented, transport-independent protocol models.
 *
 * These types deliberately keep enum fallbacks and opaque JSON strings. Codex app-server schemas are
 * versioned with the Codex binary, so a controller must continue to render newer values safely.
 */
data class RemoteEnvironment(
    val id: String,
    val name: String,
    val status: EnvironmentStatus = EnvironmentStatus.UNKNOWN,
    val host: String? = null,
    val lastSeenAtEpochMs: Long? = null,
)

enum class EnvironmentStatus {
    READY,
    PENDING,
    DISCONNECTED,
    UNKNOWN,
}

data class ThreadSummary(
    val id: String,
    val title: String? = null,
    val preview: String = "",
    val cwd: String? = null,
    val updatedAtEpochMs: Long? = null,
    val status: ThreadStatus = ThreadStatus.UNKNOWN,
    val archived: Boolean = false,
)

enum class ThreadStatus {
    NOT_LOADED,
    IDLE,
    ACTIVE,
    SYSTEM_ERROR,
    UNKNOWN,
}

data class ThreadDetail(
    val summary: ThreadSummary,
    val turns: List<Turn> = emptyList(),
    val pendingApprovals: List<PendingApproval> = emptyList(),
    val pendingUserInputs: List<PendingUserInput> = emptyList(),
)

data class Turn(
    val id: String,
    val threadId: String,
    val status: TurnStatus = TurnStatus.UNKNOWN,
    val items: List<TimelineItem> = emptyList(),
    val errorMessage: String? = null,
    val startedAtEpochMs: Long? = null,
    val completedAtEpochMs: Long? = null,
)

enum class TurnStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    INTERRUPTED,
    FAILED,
    UNKNOWN,
}

enum class TimelineItemStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    DECLINED,
    UNKNOWN,
}

sealed interface TimelineItem {
    val id: String
    val status: TimelineItemStatus
    val createdAtEpochMs: Long?
}

data class MessageItem(
    override val id: String,
    val role: MessageRole,
    val text: String,
    override val status: TimelineItemStatus = TimelineItemStatus.COMPLETED,
    override val createdAtEpochMs: Long? = null,
) : TimelineItem

enum class MessageRole {
    USER,
    AGENT,
    SYSTEM,
    UNKNOWN,
}

data class ReasoningItem(
    override val id: String,
    val summary: String,
    val rawText: String? = null,
    override val status: TimelineItemStatus = TimelineItemStatus.IN_PROGRESS,
    override val createdAtEpochMs: Long? = null,
) : TimelineItem

data class PlanItem(
    override val id: String,
    val text: String,
    override val status: TimelineItemStatus = TimelineItemStatus.IN_PROGRESS,
    override val createdAtEpochMs: Long? = null,
) : TimelineItem

data class CommandItem(
    override val id: String,
    val command: String,
    val cwd: String? = null,
    val output: String = "",
    val exitCode: Int? = null,
    val durationMs: Long? = null,
    override val status: TimelineItemStatus = TimelineItemStatus.IN_PROGRESS,
    override val createdAtEpochMs: Long? = null,
) : TimelineItem

data class FileChangeItem(
    override val id: String,
    val changes: List<FileChange> = emptyList(),
    val unifiedDiff: String? = null,
    override val status: TimelineItemStatus = TimelineItemStatus.IN_PROGRESS,
    override val createdAtEpochMs: Long? = null,
) : TimelineItem

data class FileChange(
    val path: String,
    val kind: FileChangeKind = FileChangeKind.UNKNOWN,
    val diff: String? = null,
)

enum class FileChangeKind {
    ADD,
    UPDATE,
    DELETE,
    RENAME,
    UNKNOWN,
}

data class ToolCallItem(
    override val id: String,
    val server: String? = null,
    val tool: String,
    val argumentsSummary: String? = null,
    val resultSummary: String? = null,
    override val status: TimelineItemStatus = TimelineItemStatus.IN_PROGRESS,
    override val createdAtEpochMs: Long? = null,
) : TimelineItem

data class WebActivityItem(
    override val id: String,
    val title: String,
    val url: String? = null,
    val detail: String? = null,
    override val status: TimelineItemStatus = TimelineItemStatus.IN_PROGRESS,
    override val createdAtEpochMs: Long? = null,
) : TimelineItem

data class ErrorItem(
    override val id: String,
    val message: String,
    val errorCode: String? = null,
    val retryable: Boolean = false,
    override val status: TimelineItemStatus = TimelineItemStatus.FAILED,
    override val createdAtEpochMs: Long? = null,
) : TimelineItem

/** A lossless fallback for an app-server item type this client does not understand yet. */
data class UnknownTimelineItem(
    override val id: String,
    val type: String,
    val rawJson: String,
    override val status: TimelineItemStatus = TimelineItemStatus.UNKNOWN,
    override val createdAtEpochMs: Long? = null,
) : TimelineItem

data class PendingApproval(
    val requestId: JsonRpcId,
    val threadId: String,
    val turnId: String,
    val itemId: String? = null,
    val kind: ApprovalKind,
    val reason: String? = null,
    val command: String? = null,
    val cwd: String? = null,
    val networkHost: String? = null,
    val grantRoot: String? = null,
    val availableDecisions: List<ApprovalDecision> = emptyList(),
    val rawParamsJson: String? = null,
)

enum class ApprovalKind {
    COMMAND_EXECUTION,
    FILE_CHANGE,
    PERMISSIONS,
    MCP_TOOL,
    UNKNOWN,
}

/**
 * Values currently documented by codex app-server. [wireValue] is used instead of enum.name so the
 * exact camelCase spellings survive transport.
 */
enum class ApprovalDecision(val wireValue: String, val persistent: Boolean = false) {
    ACCEPT("accept"),
    ACCEPT_FOR_SESSION("acceptForSession", persistent = true),
    ACCEPT_WITH_EXECPOLICY_AMENDMENT("acceptWithExecpolicyAmendment", persistent = true),
    APPLY_NETWORK_POLICY_AMENDMENT("applyNetworkPolicyAmendment", persistent = true),
    DECLINE("decline"),
    CANCEL("cancel"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWireValue(value: String): ApprovalDecision =
            entries.firstOrNull { it.wireValue == value } ?: UNKNOWN
    }
}

data class PendingUserInput(
    val requestId: JsonRpcId,
    val threadId: String,
    val turnId: String,
    val itemId: String? = null,
    val questions: List<UserInputQuestion>,
)

data class UserInputQuestion(
    val id: String,
    val header: String? = null,
    val prompt: String,
    val options: List<UserInputOption> = emptyList(),
)

data class UserInputOption(
    val label: String,
    val description: String? = null,
)
