package me.haroldmartin.codexeink.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.haroldmartin.codexeink.TimelineKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolMapperTest {
    @Test
    fun `thread list maps metadata and filters entries without an id`() {
        val threads = ProtocolMapper.threads(
            json(
                """
                {
                  "data": [
                    {
                      "id": "thread-1",
                      "name": "Named task",
                      "cwd": "/work/project",
                      "status": { "type": "active" },
                      "updatedAt": 1725000123
                    },
                    {
                      "id": "thread-2",
                      "cwd": "/work/fallback-name",
                      "status": "idle"
                    },
                    { "title": "Missing id" }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(2, threads.size)
        assertEquals("thread-1", threads[0].id)
        assertEquals("Named task", threads[0].title)
        assertEquals("/work/project · active", threads[0].subtitle)
        assertTrue(threads[0].active)
        assertEquals(1_725_000_123_000L, threads[0].updatedAt)

        assertEquals("fallback-name", threads[1].title)
        assertEquals("/work/fallback-name · idle", threads[1].subtitle)
        assertFalse(threads[1].active)
        assertNull(threads[1].updatedAt)
    }

    @Test
    fun `resumed thread flattens turn items and finds the in-progress turn`() {
        val parsed = ProtocolMapper.resumedThread(
            json(
                """
                {
                  "thread": {
                    "id": "thread-7",
                    "preview": "Repair the build",
                    "cwd": "/work/codex",
                    "status": "active",
                    "turns": [
                      {
                        "id": "turn-done",
                        "status": "completed",
                        "items": [
                          {
                            "id": "user-1",
                            "type": "userMessage",
                            "content": [
                              { "type": "text", "text": "Run the tests" },
                              { "type": "localImage", "path": "/tmp/failure.png" }
                            ]
                          }
                        ]
                      },
                      {
                        "id": "turn-active",
                        "status": { "type": "inProgress" },
                        "items": [
                          {
                            "id": "agent-1",
                            "type": "agentMessage",
                            "text": "I am checking now.",
                            "status": "inProgress"
                          }
                        ]
                      }
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )

        requireNotNull(parsed)
        assertEquals("thread-7", parsed.summary.id)
        assertEquals("Repair the build", parsed.summary.title)
        assertEquals("turn-active", parsed.activeTurnId)
        assertEquals(2, parsed.timeline.size)
        assertEquals(TimelineKind.User, parsed.timeline[0].kind)
        assertEquals("Run the tests\n/tmp/failure.png", parsed.timeline[0].body)
        assertEquals(TimelineKind.Agent, parsed.timeline[1].kind)
        assertEquals("I am checking now.", parsed.timeline[1].body)
    }

    @Test
    fun `timeline maps execution file tool and reasoning details`() {
        val command = ProtocolMapper.timelineItem(
            json(
                """
                {
                  "id": "command-1",
                  "type": "commandExecution",
                  "cwd": "/work/app",
                  "command": "./gradlew test",
                  "aggregatedOutput": "BUILD SUCCESSFUL",
                  "status": "completed"
                }
                """.trimIndent(),
            ),
        )
        val fileChange = ProtocolMapper.timelineItem(
            json(
                """
                {
                  "id": "change-1",
                  "type": "fileChange",
                  "changes": [
                    { "path": "app.kt", "diff": "+first" },
                    { "path": "test.kt", "diff": "+second" }
                  ]
                }
                """.trimIndent(),
            ),
        )
        val tool = ProtocolMapper.timelineItem(
            json(
                """
                {
                  "id": "tool-1",
                  "type": "mcpToolCall",
                  "server": "docs",
                  "tool": "search",
                  "arguments": { "query": "Codex" },
                  "result": { "count": 2 }
                }
                """.trimIndent(),
            ),
        )
        val reasoning = ProtocolMapper.timelineItem(
            json(
                """
                {
                  "id": "reason-1",
                  "type": "reasoning",
                  "summary": [{ "text": "Inspect state" }, { "text": "Choose fix" }],
                  "content": [{ "text": "internal detail" }]
                }
                """.trimIndent(),
            ),
        )

        requireNotNull(command)
        assertEquals(TimelineKind.Command, command.kind)
        assertEquals("Command in /work/app", command.title)
        assertEquals("./gradlew test", command.body)
        assertEquals("BUILD SUCCESSFUL", command.detail)

        requireNotNull(fileChange)
        assertEquals(TimelineKind.FileChange, fileChange.kind)
        assertEquals("app.kt, test.kt", fileChange.title)
        assertEquals("+first\n+second", fileChange.body)

        requireNotNull(tool)
        assertEquals(TimelineKind.Tool, tool.kind)
        assertEquals("docs / search", tool.title)
        assertEquals("{\"query\":\"Codex\"}", tool.body)
        assertEquals("{\"count\":2}", tool.detail)

        requireNotNull(reasoning)
        assertEquals(TimelineKind.Reasoning, reasoning.kind)
        assertEquals("Inspect state\nChoose fix", reasoning.body)
        assertEquals("internal detail", reasoning.detail)
    }

    @Test
    fun `permission approval exposes only permission decisions and request details`() {
        val approval = ProtocolMapper.approval(
            id = JsonRpcId.NumberId(42),
            method = "item/permissions/requestApproval",
            paramsElement = json(
                """
                {
                  "reason": "The task needs a new write root",
                  "grantRoot": "/work/generated",
                  "permissions": {
                    "fileSystem": { "write": ["/work/generated"] },
                    "network": false
                  },
                  "availableDecisions": ["accept"]
                }
                """.trimIndent(),
            ),
        )

        requireNotNull(approval)
        assertEquals("n:42", approval.requestId)
        assertEquals("Permission approval", approval.title)
        assertEquals("The task needs a new write root", approval.reason)
        assertTrue(approval.commandOrDiff.contains("Write root: /work/generated"))
        assertTrue(approval.commandOrDiff.contains("fileSystem"))
        assertEquals(listOf("deny", "allowForTurn", "allowForSession"), approval.availableDecisions)
    }

    @Test
    fun `command approval accepts primitive and structured decision names`() {
        val approval = ProtocolMapper.approval(
            id = JsonRpcId.StringId("approval-a"),
            method = "item/commandExecution/requestApproval",
            paramsElement = json(
                """
                {
                  "command": "rm generated.tmp",
                  "cwd": "/work/safe",
                  "availableDecisions": [
                    "decline",
                    { "acceptWithExecpolicyAmendment": { "command": ["rm"] } }
                  ]
                }
                """.trimIndent(),
            ),
        )

        requireNotNull(approval)
        assertEquals("s:approval-a", approval.requestId)
        assertEquals(
            listOf("decline", "acceptWithExecpolicyAmendment"),
            approval.availableDecisions,
        )
        assertTrue(approval.commandOrDiff.contains("rm generated.tmp"))
        assertTrue(approval.commandOrDiff.contains("Working directory: /work/safe"))
    }

    @Test
    fun `question maps labels primitive options and prompt fallback`() {
        val question = ProtocolMapper.question(
            id = JsonRpcId.StringId("question-9"),
            paramsElement = json(
                """
                {
                  "questions": [
                    {
                      "id": "scope",
                      "question": "Which scope?",
                      "options": [{ "label": "App only" }, { "label": "Everything" }]
                    },
                    {
                      "id": "format",
                      "prompt": "Which format?",
                      "options": ["Text", "JSON"]
                    },
                    { "prompt": "Missing id is ignored" }
                  ]
                }
                """.trimIndent(),
            ),
        )

        requireNotNull(question)
        assertEquals("s:question-9", question.requestId)
        assertEquals(2, question.questions.size)
        assertEquals("Which scope?", question.questions[0].prompt)
        assertEquals(listOf("App only", "Everything"), question.questions[0].options)
        assertEquals("Which format?", question.questions[1].prompt)
        assertEquals(listOf("Text", "JSON"), question.questions[1].options)
    }

    private fun json(value: String): JsonElement = Json.parseToJsonElement(value)
}
