package me.haroldmartin.codexeink.protocol

data class CodexState(
    val connectionState: ConnectionState = ConnectionState.Disconnected,
    val environments: Map<String, RemoteEnvironment> = emptyMap(),
    val threads: Map<String, ThreadDetail> = emptyMap(),
    val selectedEnvironmentId: String? = null,
    val selectedThreadId: String? = null,
)

sealed interface CodexEvent {
    data class ConnectionChanged(val state: ConnectionState) : CodexEvent
    data class EnvironmentUpserted(val environment: RemoteEnvironment) : CodexEvent
    data class EnvironmentRemoved(val environmentId: String) : CodexEvent
    data class EnvironmentSelected(val environmentId: String?) : CodexEvent
    data class ThreadUpserted(val thread: ThreadDetail) : CodexEvent
    data class ThreadRemoved(val threadId: String) : CodexEvent
    data class ThreadSelected(val threadId: String?) : CodexEvent
    data class TurnUpserted(val turn: Turn) : CodexEvent
    data class TimelineItemUpserted(
        val threadId: String,
        val turnId: String,
        val item: TimelineItem,
    ) : CodexEvent

    data class TimelineItemRemoved(
        val threadId: String,
        val turnId: String,
        val itemId: String,
    ) : CodexEvent

    data class ApprovalUpserted(val approval: PendingApproval) : CodexEvent
    data class ApprovalResolved(val threadId: String, val requestId: JsonRpcId) : CodexEvent
    data class UserInputUpserted(val input: PendingUserInput) : CodexEvent
    data class UserInputResolved(val threadId: String, val requestId: JsonRpcId) : CodexEvent
    data class Snapshot(
        val environments: List<RemoteEnvironment>,
        val threads: List<ThreadDetail>,
    ) : CodexEvent
}

/**
 * Experimental domain reducer used by protocol model tests.
 *
 * The running Android controller maps active direct app-server traffic through its own adapter; this
 * reducer must not be treated as evidence that managed relay traffic is integrated end to end.
 */
internal object CodexStateReducer {
    fun reduce(state: CodexState, event: CodexEvent): CodexState = when (event) {
        is CodexEvent.ConnectionChanged -> state.copy(connectionState = event.state)
        is CodexEvent.EnvironmentUpserted -> state.copy(
            environments = state.environments + (event.environment.id to event.environment),
        )
        is CodexEvent.EnvironmentRemoved -> state.copy(
            environments = state.environments - event.environmentId,
            selectedEnvironmentId = state.selectedEnvironmentId.takeUnless { it == event.environmentId },
        )
        is CodexEvent.EnvironmentSelected -> state.copy(selectedEnvironmentId = event.environmentId)
        is CodexEvent.ThreadUpserted -> state.copy(
            threads = state.threads + (event.thread.summary.id to event.thread),
        )
        is CodexEvent.ThreadRemoved -> state.copy(
            threads = state.threads - event.threadId,
            selectedThreadId = state.selectedThreadId.takeUnless { it == event.threadId },
        )
        is CodexEvent.ThreadSelected -> state.copy(selectedThreadId = event.threadId)
        is CodexEvent.TurnUpserted -> state.updateThread(event.turn.threadId) { thread ->
            thread.copy(turns = thread.turns.upsertBy({ it.id }, event.turn))
        }
        is CodexEvent.TimelineItemUpserted -> state.updateTurn(event.threadId, event.turnId) { turn ->
            turn.copy(items = turn.items.upsertBy({ it.id }, event.item))
        }
        is CodexEvent.TimelineItemRemoved -> state.updateTurn(event.threadId, event.turnId) { turn ->
            turn.copy(items = turn.items.filterNot { it.id == event.itemId })
        }
        is CodexEvent.ApprovalUpserted -> state.updateThread(event.approval.threadId) { thread ->
            thread.copy(
                pendingApprovals = thread.pendingApprovals.upsertBy(
                    key = { it.requestId },
                    value = event.approval,
                ),
            )
        }
        is CodexEvent.ApprovalResolved -> state.updateThread(event.threadId) { thread ->
            thread.copy(pendingApprovals = thread.pendingApprovals.filterNot { it.requestId == event.requestId })
        }
        is CodexEvent.UserInputUpserted -> state.updateThread(event.input.threadId) { thread ->
            thread.copy(
                pendingUserInputs = thread.pendingUserInputs.upsertBy(
                    key = { it.requestId },
                    value = event.input,
                ),
            )
        }
        is CodexEvent.UserInputResolved -> state.updateThread(event.threadId) { thread ->
            thread.copy(pendingUserInputs = thread.pendingUserInputs.filterNot { it.requestId == event.requestId })
        }
        is CodexEvent.Snapshot -> state.copy(
            environments = event.environments.associateBy(RemoteEnvironment::id),
            threads = event.threads.associateBy { it.summary.id },
            selectedEnvironmentId = state.selectedEnvironmentId?.takeIf { id -> event.environments.any { it.id == id } },
            selectedThreadId = state.selectedThreadId?.takeIf { id -> event.threads.any { it.summary.id == id } },
        )
    }

    private fun CodexState.updateThread(
        threadId: String,
        transform: (ThreadDetail) -> ThreadDetail,
    ): CodexState {
        val thread = threads[threadId] ?: return this
        return copy(threads = threads + (threadId to transform(thread)))
    }

    private fun CodexState.updateTurn(
        threadId: String,
        turnId: String,
        transform: (Turn) -> Turn,
    ): CodexState = updateThread(threadId) { thread ->
        val turn = thread.turns.firstOrNull { it.id == turnId } ?: return@updateThread thread
        thread.copy(turns = thread.turns.upsertBy({ it.id }, transform(turn)))
    }

    private fun <T, K> List<T>.upsertBy(key: (T) -> K, value: T): List<T> {
        val index = indexOfFirst { key(it) == key(value) }
        if (index < 0) return this + value
        return toMutableList().also { it[index] = value }
    }
}
