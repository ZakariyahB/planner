package app.planner.ai

import app.planner.data.ChatDao
import app.planner.data.ChatMessageEntity
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A tool the assistant used for a reply, shown as a small chip under it. */
@Serializable
data class ToolChip(val name: String, val failed: Boolean = false) {
    /** Plain words for the chip. */
    val label: String
        get() = when (name) {
            AiToolNames.GET_DAY -> "Looked at your day"
            AiToolNames.GET_TASKS -> "Checked your tasks"
            AiToolNames.GET_SLEEP -> "Checked your sleep"
            AiToolNames.GET_READINESS -> "Checked readiness"
            AiToolNames.GET_SOCIAL_TIME -> "Checked social time"
            AiToolNames.PROPOSE_CHANGES -> "Proposed a change"
            AiToolNames.APPLY_CHANGES -> "Applied a change"
            AiToolNames.ADD_TASK -> "Added a task"
            AiToolNames.ADD_COMMITMENT -> "Added a commitment"
            AiToolNames.UPDATE_TASK -> "Changed a task"
            AiToolNames.LOG_PROGRESS -> "Logged progress"
            AiToolNames.UNDO -> "Undid a change"
            else -> name
        } + if (failed) " (failed)" else ""
}

data class ChatItem(val id: Long, val role: Role, val text: String, val tools: List<ToolChip>)

@Singleton
class ChatRepository @Inject constructor(
    private val dao: ChatDao,
    private val clock: Clock,
) {
    private val json = Json { ignoreUnknownKeys = true }

    val items: Flow<List<ChatItem>> = dao.observeAll().map { rows ->
        rows.map { ChatItem(it.id, Role.valueOf(it.role), it.content, chips(it.toolJson)) }
    }

    /** The recent conversation as the model should see it: words only, no tool traffic. */
    suspend fun history(limit: Int = 40): List<ChatEntry> =
        dao.recent(limit).map { ChatEntry(Role.valueOf(it.role), it.content) }

    suspend fun add(role: Role, text: String, toolCalls: List<ToolCall> = emptyList()) {
        val chips = toolCalls.map { ToolChip(it.name, it.outcome.isError) }.distinct()
        dao.insert(ChatMessageEntity(ts = clock.millis(), role = role.name, content = text, toolJson = chips.takeIf { it.isNotEmpty() }?.let { json.encodeToString(it) }))
    }

    suspend fun clear() = dao.clear()

    private fun chips(text: String?): List<ToolChip> = text?.let { json.decodeFromString<List<ToolChip>>(it) }.orEmpty()
}
