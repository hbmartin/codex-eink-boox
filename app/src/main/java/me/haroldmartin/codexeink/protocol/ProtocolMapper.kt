package me.haroldmartin.codexeink.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.haroldmartin.codexeink.ApprovalUi
import me.haroldmartin.codexeink.QuestionFieldUi
import me.haroldmartin.codexeink.QuestionUi
import me.haroldmartin.codexeink.ThreadUi
import me.haroldmartin.codexeink.TimelineKind
import me.haroldmartin.codexeink.TimelineUi
import me.haroldmartin.codexeink.protocol.JsonRpcId.NumberId
import me.haroldmartin.codexeink.protocol.JsonRpcId.StringId

internal object ProtocolMapper {
    fun threads(result: JsonElement?): List<ThreadUi> = result.objectOrNull()
        ?.get("data")
        .arrayOrNull()
        .orEmpty()
        .mapNotNull { it.objectOrNull()?.let(::thread) }

    fun thread(objectValue: JsonObject): ThreadUi? {
        val id = objectValue.string("id") ?: return null
        val preview = objectValue.string("name")
            ?: objectValue.string("title")
            ?: objectValue.string("preview")
        val cwd = objectValue.string("cwd")
        val status = status(objectValue["status"])
        return ThreadUi(
            id = id,
            title = preview?.takeIf(String::isNotBlank) ?: cwd?.substringAfterLast('/') ?: "New task",
            subtitle = listOfNotNull(cwd, status).joinToString(" · ").ifBlank { "Stored task" },
            active = status.equals("active", ignoreCase = true),
            updatedAt = objectValue.long("updatedAt")?.times(SECONDS_TO_MILLIS),
        )
    }

    fun resumedThread(result: JsonElement?): ParsedThread? {
        val thread = result.objectOrNull()?.get("thread").objectOrNull() ?: return null
        val summary = thread(thread) ?: return null
        val turns = thread["turns"].arrayOrNull().orEmpty()
        val activeTurnId = turns.lastOrNull { turn ->
            status(turn.objectOrNull()?.get("status")) == "inProgress"
        }?.objectOrNull()?.string("id")
        return ParsedThread(
            summary = summary,
            timeline = turns.flatMap { turn ->
                turn.objectOrNull()?.get("items").arrayOrNull().orEmpty().mapNotNull(::timelineItem)
            },
            activeTurnId = activeTurnId,
        )
    }

    fun timelineItem(element: JsonElement): TimelineUi? {
        val item = element.objectOrNull() ?: return null
        val id = item.string("id") ?: return null
        val type = item.string("type") ?: "unknown"
        val itemStatus = status(item["status"])
        return when (type) {
            "userMessage" -> TimelineUi(
                id = id,
                kind = TimelineKind.User,
                title = "You",
                body = contentText(item["content"]),
                status = itemStatus,
            )
            "agentMessage" -> TimelineUi(
                id = id,
                kind = TimelineKind.Agent,
                title = "Codex",
                body = item.string("text").orEmpty(),
                status = itemStatus,
            )
            "plan" -> TimelineUi(
                id = id,
                kind = TimelineKind.Plan,
                title = "Plan",
                body = item.string("text") ?: item["plan"].renderCompact(),
                status = itemStatus,
            )
            "reasoning" -> TimelineUi(
                id = id,
                kind = TimelineKind.Reasoning,
                title = "Reasoning summary",
                body = textBlocks(item["summary"]),
                status = itemStatus,
                detail = textBlocks(item["content"]).takeIf(String::isNotBlank),
            )
            "commandExecution" -> TimelineUi(
                id = id,
                kind = TimelineKind.Command,
                title = item.string("cwd")?.let { "Command in $it" } ?: "Command",
                body = item.string("command").orEmpty(),
                status = itemStatus,
                detail = item.string("aggregatedOutput")
                    ?: item["output"].renderCompact().takeIf(String::isNotBlank),
            )
            "fileChange" -> {
                val changes = item["changes"].arrayOrNull().orEmpty().mapNotNull { it.objectOrNull() }
                val names = changes.mapNotNull { it.string("path") }
                val diff = changes.mapNotNull { it.string("diff") }.joinToString("\n")
                TimelineUi(
                    id = id,
                    kind = TimelineKind.FileChange,
                    title = names.joinToString().ifBlank { "File changes" },
                    body = diff.ifBlank { item.string("diff").orEmpty() },
                    status = itemStatus,
                )
            }
            "mcpToolCall", "dynamicToolCall" -> TimelineUi(
                id = id,
                kind = TimelineKind.Tool,
                title = listOfNotNull(item.string("server"), item.string("tool")).joinToString(" / ")
                    .ifBlank { "Tool call" },
                body = item["arguments"].renderCompact(),
                status = itemStatus,
                detail = (item["result"] ?: item["contentItems"] ?: item["error"])
                    .renderCompact()
                    .takeIf(String::isNotBlank),
            )
            "collabToolCall", "collabAgentToolCall" -> TimelineUi(
                id = id,
                kind = TimelineKind.Collaboration,
                title = item.string("tool") ?: "Agent collaboration",
                body = listOfNotNull(item.string("agentStatus"), item.string("prompt")).joinToString("\n"),
                status = itemStatus,
            )
            "webSearch" -> TimelineUi(
                id = id,
                kind = TimelineKind.Web,
                title = "Web search",
                body = item.string("query") ?: item["action"].renderCompact(),
                status = itemStatus,
                detail = item["results"].renderCompact().takeIf(String::isNotBlank),
            )
            "error" -> TimelineUi(
                id = id,
                kind = TimelineKind.Error,
                title = "Error",
                body = item.string("message") ?: item.renderCompact(),
                status = itemStatus,
            )
            else -> TimelineUi(
                id = id,
                kind = TimelineKind.Unknown,
                title = type,
                body = item.renderCompact(),
                status = itemStatus,
            )
        }
    }

    fun approval(id: JsonRpcId, method: String, paramsElement: JsonElement?): ApprovalUi? {
        val params = paramsElement.objectOrNull() ?: return null
        val kind = when (method) {
            "item/commandExecution/requestApproval" -> "Command approval"
            "item/fileChange/requestApproval" -> "File change approval"
            "item/permissions/requestApproval" -> "Permission approval"
            else -> "Codex approval"
        }
        val details = listOfNotNull(
            params.string("command"),
            params.string("cwd")?.let { "Working directory: $it" },
            params.string("grantRoot")?.let { "Write root: $it" },
            params["networkApprovalContext"]?.renderCompact()?.takeIf(String::isNotBlank),
            params["additionalPermissions"]?.renderCompact()?.takeIf(String::isNotBlank)?.let {
                "Requested additional permissions: $it"
            },
            params["permissions"]?.renderCompact()?.takeIf(String::isNotBlank),
        ).joinToString("\n")
        val decisions = if (method == "item/permissions/requestApproval") {
            PERMISSION_DECISIONS
        } else {
            val provided = params["availableDecisions"]
            if (provided is JsonArray) {
                provided.mapNotNull { decision ->
                    decision.primitiveString() ?: decision.objectOrNull()?.keys?.firstOrNull()
                }
            } else if (method == "item/commandExecution/requestApproval") {
                DEFAULT_COMMAND_DECISIONS
            } else {
                DEFAULT_FILE_DECISIONS
            }
        }
        return ApprovalUi(
            requestId = id.key(),
            title = kind,
            reason = params.string("reason"),
            commandOrDiff = details.ifBlank { params.renderCompact() },
            availableDecisions = decisions,
        )
    }

    fun question(id: JsonRpcId, paramsElement: JsonElement?): QuestionUi? {
        val questions = paramsElement.objectOrNull()
            ?.get("questions")
            .arrayOrNull()
            .orEmpty()
            .mapNotNull { element ->
                val value = element.objectOrNull() ?: return@mapNotNull null
                val optionValues = value["options"].arrayOrNull().orEmpty().mapNotNull { option ->
                    val optionObject = option.objectOrNull()
                    val label = optionObject?.string("label") ?: option.primitiveString()
                    label?.let { it to optionObject?.string("description") }
                }
                QuestionFieldUi(
                    id = value.string("id") ?: return@mapNotNull null,
                    prompt = value.string("question") ?: value.string("prompt") ?: "Answer Codex",
                    options = optionValues.map(Pair<String, String?>::first),
                    optionDescriptions = optionValues.mapNotNull { (label, description) ->
                        description?.let { label to it }
                    }.toMap(),
                    secret = value["isSecret"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true,
                )
            }
        return QuestionUi(id.key(), questions).takeIf { questions.isNotEmpty() }
    }

    fun status(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonObject -> element.string("type")
        else -> null
    }

    fun JsonRpcId.key(): String = when (this) {
        is StringId -> "s:$value"
        is NumberId -> "n:$value"
        JsonRpcId.NullId -> "z:"
    }

    data class ParsedThread(
        val summary: ThreadUi,
        val timeline: List<TimelineUi>,
        val activeTurnId: String?,
    )

    private fun contentText(element: JsonElement?): String = element.arrayOrNull().orEmpty()
        .joinToString("\n") { part ->
            val value = part.objectOrNull()
            value?.string("text") ?: value?.string("path") ?: part.renderCompact()
        }

    private fun textBlocks(element: JsonElement?): String = when (element) {
        is JsonArray -> element.joinToString("\n") { block ->
            block.objectOrNull()?.string("text") ?: block.primitiveString() ?: block.renderCompact()
        }
        else -> element.primitiveString() ?: element.renderCompact()
    }

    private fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement?.arrayOrNull(): JsonArray? = this as? JsonArray

    private fun JsonObject.string(key: String): String? = this[key].primitiveString()

    private fun JsonObject.long(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull

    private fun JsonElement?.primitiveString(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.renderCompact(): String = when (this) {
        null, JsonNull -> ""
        is JsonPrimitive -> contentOrNull.orEmpty()
        else -> toString()
    }

    private const val SECONDS_TO_MILLIS = 1_000L
    private val DEFAULT_COMMAND_DECISIONS = listOf("decline", "cancel", "accept", "acceptForSession")
    private val DEFAULT_FILE_DECISIONS = listOf("decline", "cancel", "accept", "acceptForSession")
    private val PERMISSION_DECISIONS = listOf("deny", "allowForTurn", "allowForSession")
}
