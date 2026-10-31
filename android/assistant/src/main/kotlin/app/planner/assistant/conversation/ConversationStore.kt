package app.planner.assistant.conversation

import app.planner.assistant.llm.Message
import app.planner.assistant.llm.MessagesJson
import app.planner.engine.ports.ChatRole
import app.planner.engine.ports.ChatTurn
import java.time.Instant
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * What the chat remembers: [turns] for the screen, [history] for the model (the exact messages sent, including
 * tool traffic and thinking blocks, which must be replayed unchanged).
 */
data class Conversation(val turns: List<ChatTurn> = emptyList(), val history: List<Message> = emptyList())

/** Where the conversation is kept. The app keeps it in a file; tests and previews keep it in memory. */
interface ConversationStore {
    suspend fun load(): Conversation
    suspend fun save(conversation: Conversation)
}

class InMemoryConversationStore(private var value: Conversation = Conversation()) : ConversationStore {
    override suspend fun load(): Conversation = value
    override suspend fun save(conversation: Conversation) { value = conversation }
}

/** JSON for [Conversation]. Unreadable input decodes to an empty conversation rather than failing the chat. */
object ConversationCodec {
    private const val VERSION = 1

    fun encode(c: Conversation): String = buildJsonObject {
        put("v", VERSION)
        put(
            "turns",
            buildJsonArray {
                c.turns.forEach { t ->
                    add(
                        buildJsonObject {
                            put("id", t.id)
                            put("role", t.role.name)
                            put("text", t.text)
                            put("at", t.at.toEpochMilli())
                            t.proposalId?.let { put("proposalId", it) }
                        },
                    )
                }
            },
        )
        put("history", JsonArray(c.history.map(MessagesJson::encodeMessage)))
    }.toString()

    fun decode(text: String?): Conversation {
        if (text.isNullOrBlank()) return Conversation()
        return try {
            val root = Json.parseToJsonElement(text) as? JsonObject ?: return Conversation()
            val turns = (root["turns"] as? JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                ChatTurn(
                    id = o.str("id") ?: return@mapNotNull null,
                    role = o.str("role")?.let { r -> ChatRole.entries.firstOrNull { it.name == r } } ?: return@mapNotNull null,
                    text = o.str("text").orEmpty(),
                    at = Instant.ofEpochMilli((o["at"] as? JsonPrimitive)?.longOrNull ?: 0L),
                    proposalId = o.str("proposalId"),
                )
            }
            // The model's history is all or nothing: a partly readable one would be an edited history.
            val history = try {
                (root["history"] as? JsonArray).orEmpty().map { MessagesJson.decodeMessage(it as JsonObject) }
            } catch (e: RuntimeException) {
                emptyList()
            }
            Conversation(turns, history)
        } catch (e: SerializationException) {
            Conversation()
        } catch (e: IllegalArgumentException) {
            Conversation()
        }
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
}
