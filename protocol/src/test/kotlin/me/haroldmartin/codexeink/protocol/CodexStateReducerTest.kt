package me.haroldmartin.codexeink.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexStateReducerTest {
    private val summary = ThreadSummary(id = "thread", title = "Test")
    private val initialTurn = Turn(id = "turn", threadId = "thread", status = TurnStatus.IN_PROGRESS)
    private val initial = CodexState(
        threads = mapOf("thread" to ThreadDetail(summary = summary, turns = listOf(initialTurn))),
        selectedThreadId = "thread",
    )

    @Test
    fun `upserts turn and timeline items without changing order`() {
        val updatedTurn = initialTurn.copy(status = TurnStatus.COMPLETED)
        var state = CodexStateReducer.reduce(initial, CodexEvent.TurnUpserted(updatedTurn))
        val first = MessageItem(id = "one", role = MessageRole.USER, text = "hello")
        val second = MessageItem(id = "two", role = MessageRole.AGENT, text = "working")
        state = CodexStateReducer.reduce(state, CodexEvent.TimelineItemUpserted("thread", "turn", first))
        state = CodexStateReducer.reduce(state, CodexEvent.TimelineItemUpserted("thread", "turn", second))
        state = CodexStateReducer.reduce(
            state,
            CodexEvent.TimelineItemUpserted("thread", "turn", second.copy(text = "done")),
        )

        val turn = state.threads.getValue("thread").turns.single()
        assertEquals(TurnStatus.COMPLETED, turn.status)
        assertEquals(listOf("one", "two"), turn.items.map(TimelineItem::id))
        assertEquals("done", (turn.items[1] as MessageItem).text)
    }

    @Test
    fun `tracks and resolves approvals and user input by request id`() {
        val approval = PendingApproval(
            requestId = JsonRpcId.NumberId(4),
            threadId = "thread",
            turnId = "turn",
            kind = ApprovalKind.COMMAND_EXECUTION,
        )
        val input = PendingUserInput(
            requestId = JsonRpcId.StringId("question"),
            threadId = "thread",
            turnId = "turn",
            questions = listOf(UserInputQuestion("q", prompt = "Choose")),
        )
        var state = CodexStateReducer.reduce(initial, CodexEvent.ApprovalUpserted(approval))
        state = CodexStateReducer.reduce(state, CodexEvent.UserInputUpserted(input))
        assertEquals(1, state.threads.getValue("thread").pendingApprovals.size)
        assertEquals(1, state.threads.getValue("thread").pendingUserInputs.size)

        state = CodexStateReducer.reduce(state, CodexEvent.ApprovalResolved("thread", approval.requestId))
        state = CodexStateReducer.reduce(state, CodexEvent.UserInputResolved("thread", input.requestId))
        assertTrue(state.threads.getValue("thread").pendingApprovals.isEmpty())
        assertTrue(state.threads.getValue("thread").pendingUserInputs.isEmpty())
    }

    @Test
    fun `snapshot clears selections that no longer exist`() {
        val state = CodexStateReducer.reduce(
            initial.copy(
                environments = mapOf("env" to RemoteEnvironment("env", "Mac")),
                selectedEnvironmentId = "env",
            ),
            CodexEvent.Snapshot(environments = emptyList(), threads = emptyList()),
        )

        assertEquals(null, state.selectedEnvironmentId)
        assertEquals(null, state.selectedThreadId)
        assertTrue(state.threads.isEmpty())
    }

    @Test
    fun `events for missing parents are safe no ops`() {
        val state = CodexStateReducer.reduce(
            initial,
            CodexEvent.TimelineItemUpserted(
                threadId = "missing",
                turnId = "missing",
                item = UnknownTimelineItem("future", "futureType", "{}"),
            ),
        )
        assertSame(initial, state)
    }

    @Test
    fun `unknown timeline item remains lossless`() {
        val unknown = UnknownTimelineItem("future", "newType", "{\"future\":true}")
        val state = CodexStateReducer.reduce(
            initial,
            CodexEvent.TimelineItemUpserted("thread", "turn", unknown),
        )
        val stored = state.threads.getValue("thread").turns.single().items.single() as UnknownTimelineItem
        assertEquals("{\"future\":true}", stored.rawJson)
        assertFalse(stored.rawJson.isBlank())
    }
}
