package app.planner.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What a tool returned. [content] is JSON text the model reads; [isError] tells it the call failed. */
data class ToolOutcome(val content: String, val isError: Boolean = false)

/** Runs the tools the model asks for. The phone implements this against its own data. */
interface ToolExecutor {
    suspend fun execute(name: String, input: JsonObject): ToolOutcome
}

data class ToolCall(val name: String, val input: JsonObject, val outcome: ToolOutcome)

data class AgentResult(
    val reply: String,
    /** The user's message and everything that followed, in order, ready to append to the conversation. */
    val newMessages: List<Message>,
    val toolCalls: List<ToolCall>,
    val usage: TokenUsage,
    val costGbp: Double,
    /** True if the model was still asking for tools when the round limit stopped it. */
    val hitLimit: Boolean,
)

/**
 * Sends the conversation to the model and runs the tools it asks for until it gives a final answer.
 * The model never changes the plan itself: every change goes through a tool that the phone validates.
 */
class Agent(
    private val llm: LlmClient,
    private val tools: ToolExecutor,
    private val system: String,
    private val toolSchemas: JsonArray,
    private val maxRounds: Int = 8,
) {
    suspend fun run(history: List<Message>, userText: String, purpose: Purpose = Purpose.CHAT): AgentResult {
        val added = mutableListOf(Message.user(userText))
        val calls = mutableListOf<ToolCall>()
        var usage = TokenUsage()
        var cost = 0.0

        repeat(maxRounds) { round ->
            val response = llm.send(LlmRequest(system, toolSchemas, history + added, purpose))
            usage += response.usage
            cost += response.costGbp
            added += response.message

            val uses = response.message.content.filterIsInstance<ContentBlock.ToolUse>()
            if (uses.isEmpty()) return AgentResult(response.message.text, added, calls, usage, cost, hitLimit = false)

            val lastRound = round == maxRounds - 1
            val results = uses.map { use ->
                if (lastRound) {
                    ContentBlock.ToolResult(use.id, errorJson("Stopped: too many steps"), isError = true)
                } else {
                    val outcome = runTool(use)
                    calls += ToolCall(use.name, use.input, outcome)
                    ContentBlock.ToolResult(use.id, outcome.content, outcome.isError)
                }
            }
            added += Message(Role.USER, results)

            if (lastRound) {
                val reply = response.message.text.ifBlank { GAVE_UP }
                added += Message.assistant(reply)
                return AgentResult(reply, added, calls, usage, cost, hitLimit = true)
            }
        }
        error("maxRounds must be at least 1")
    }

    private suspend fun runTool(use: ContentBlock.ToolUse): ToolOutcome = try {
        tools.execute(use.name, use.input)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolOutcome(errorJson("Tool failed: ${e.message ?: e::class.simpleName}"), isError = true)
    }

    private fun errorJson(message: String) = buildJsonObject { put("error", message) }.toString()

    private companion object {
        const val GAVE_UP = "I couldn't finish that in a few steps. Try a smaller request."
    }
}
