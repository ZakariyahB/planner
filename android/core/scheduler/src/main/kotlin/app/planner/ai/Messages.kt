package app.planner.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

enum class Role { USER, ASSISTANT }

sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock

    /** A request from the model to run one of our tools. */
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : ContentBlock

    /** Our answer to a [ToolUse]; [content] is JSON text for the model to read. */
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean = false) : ContentBlock
}

data class Message(val role: Role, val content: List<ContentBlock>) {
    /** The words in the message, ignoring tool traffic. */
    val text: String get() = content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    companion object {
        fun user(text: String) = Message(Role.USER, listOf(ContentBlock.Text(text)))

        fun assistant(text: String) = Message(Role.ASSISTANT, listOf(ContentBlock.Text(text)))
    }
}

data class TokenUsage(val input: Int = 0, val output: Int = 0, val cacheWrite: Int = 0, val cacheRead: Int = 0) {
    operator fun plus(o: TokenUsage) = TokenUsage(input + o.input, output + o.output, cacheWrite + o.cacheWrite, cacheRead + o.cacheRead)
}

/** Why the call is made; the server picks the model from this (see worker/src/proxy.ts). */
enum class Purpose(val header: String) { CHAT("chat"), REPLAN("replan"), SCHEDULED("scheduled") }

data class LlmRequest(
    val system: String,
    val tools: JsonArray,
    val messages: List<Message>,
    val purpose: Purpose = Purpose.CHAT,
    val maxTokens: Int = 1024,
)

data class LlmResponse(
    val message: Message,
    val stopReason: String?,
    val usage: TokenUsage,
    /** What the call cost in pounds, as reported by the server; 0 when unknown. */
    val costGbp: Double = 0.0,
)

fun interface LlmClient {
    suspend fun send(request: LlmRequest): LlmResponse
}
