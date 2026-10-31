package app.planner.assistant.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// The Messages API, as far as the assistant needs it. Copied in spirit from the legacy core/.../ai/Messages.kt,
// with one difference that matters: blocks the app does not use (thinking, redacted_thinking, fallback markers)
// are kept byte-for-byte as [ContentBlock.Opaque] and sent back unchanged, because current models reject a
// replayed history whose earlier turns were edited.

enum class Role { USER, ASSISTANT }

sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock

    /** A request from the model to run one of our tools. */
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : ContentBlock

    /** Our answer to a [ToolUse]; [content] is JSON text for the model to read. */
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean = false) : ContentBlock

    /** Any other block the model sent (thinking, redacted_thinking, fallback). Echoed back exactly as received. */
    data class Opaque(val json: JsonObject) : ContentBlock {
        val type: String? get() = (json["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    }
}

data class Message(val role: Role, val content: List<ContentBlock>) {
    /** The words in the message, ignoring tool traffic and thinking. */
    val text: String get() = content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }.trim()

    val toolUses: List<ContentBlock.ToolUse> get() = content.filterIsInstance<ContentBlock.ToolUse>()

    companion object {
        fun user(vararg text: String) = Message(Role.USER, text.map { ContentBlock.Text(it) })
    }
}

data class TokenUsage(val input: Int = 0, val output: Int = 0, val cacheWrite: Int = 0, val cacheRead: Int = 0) {
    operator fun plus(o: TokenUsage) = TokenUsage(input + o.input, output + o.output, cacheWrite + o.cacheWrite, cacheRead + o.cacheRead)
}

/** Why the call is made. The Worker uses it to pick a model when the app does not name one. */
enum class Purpose(val header: String) { CHAT("chat"), REPLAN("replan"), SCHEDULED("scheduled") }

data class LlmRequest(
    /** Frozen for the whole conversation: changing it would invalidate the model's earlier thinking. */
    val system: String,
    val tools: JsonArray,
    val messages: List<Message>,
    /** Null lets the Worker choose from [purpose]. */
    val model: String? = null,
    val purpose: Purpose = Purpose.CHAT,
    /** Thinking tokens count against this, so it is generous. The Worker clamps it. */
    val maxTokens: Int = 4096,
    /** output_config.effort; null leaves the model's default. */
    val effort: String? = "medium",
)

data class LlmResponse(
    val message: Message,
    val stopReason: String?,
    val usage: TokenUsage = TokenUsage(),
    /** The model that actually answered (may be a fallback model). */
    val model: String? = null,
    /** What the call cost in pounds, as reported by the Worker; 0 when unknown. */
    val costGbp: Double = 0.0,
)

enum class LlmFailure { SIGN_IN, CAP_REACHED, BUSY, TOO_LONG, NOT_SET_UP, OFFLINE, OTHER }

/** A call that did not produce a message. [message] is plain wording for the chat. */
class LlmException(val failure: LlmFailure, message: String) : Exception(message)

fun interface LlmClient {
    /** Throws [LlmException] on failure. */
    suspend fun send(request: LlmRequest): LlmResponse
}
