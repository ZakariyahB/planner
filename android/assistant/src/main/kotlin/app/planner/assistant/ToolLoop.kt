package app.planner.assistant

import app.planner.assistant.llm.ContentBlock
import app.planner.assistant.llm.LlmClient
import app.planner.assistant.llm.LlmRequest
import app.planner.assistant.llm.Message
import app.planner.assistant.llm.Purpose
import app.planner.assistant.llm.Role
import app.planner.assistant.llm.TokenUsage
import app.planner.assistant.tools.ToolExecutor
import app.planner.assistant.tools.ToolOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ToolCall(val name: String, val input: JsonObject, val outcome: ToolOutcome)

enum class LoopEnd {
    /** The model gave its answer. */
    DONE,

    /** The model declined. Nothing from this turn is kept in the history. */
    REFUSED,

    /** The model was still calling tools when the round limit stopped it. */
    ROUND_LIMIT,
}

data class LoopResult(
    val reply: String,
    /** The user's message and everything that followed, ready to append to the history. Empty when [end] is REFUSED. */
    val newMessages: List<Message>,
    val toolCalls: List<ToolCall>,
    /** ChangeSets proposed during this turn, in order. They are already waiting on the approval card. */
    val proposalIds: List<String>,
    val usage: TokenUsage,
    val costGbp: Double,
    val end: LoopEnd,
)

/**
 * The Messages API tool loop: send, run the tools the model asks for, send their results, until it answers.
 *
 * The history is append-only (nothing sent is ever edited or removed), so the model's thinking blocks stay valid
 * and the prompt cache stays warm. Tools run one at a time and all their results go back in one user message.
 */
class ToolLoop(
    private val llm: LlmClient,
    private val tools: ToolExecutor,
    private val maxRounds: Int = 8,
) {
    init {
        require(maxRounds >= 1) { "maxRounds must be at least 1" }
    }

    suspend fun run(
        system: String,
        toolSchemas: JsonArray,
        history: List<Message>,
        user: Message,
        model: String? = null,
        purpose: Purpose = Purpose.CHAT,
    ): LoopResult {
        val added = mutableListOf(user)
        val calls = mutableListOf<ToolCall>()
        val proposals = mutableListOf<String>()
        var usage = TokenUsage()
        var cost = 0.0

        for (round in 0 until maxRounds) {
            val response = llm.send(LlmRequest(system, toolSchemas, history + added, model = model, purpose = purpose))
            usage += response.usage
            cost += response.costGbp

            if (response.stopReason == "refusal") {
                return LoopResult(REFUSED_REPLY, emptyList(), calls, proposals, usage, cost, LoopEnd.REFUSED)
            }
            added += response.message

            val uses = response.message.toolUses
            if (uses.isEmpty()) {
                return LoopResult(response.message.text, added, calls, proposals, usage, cost, LoopEnd.DONE)
            }

            val lastRound = round == maxRounds - 1
            val cutOff = response.stopReason == "max_tokens"
            val results = uses.map { use ->
                when {
                    // A call cut off by max_tokens may have incomplete input: never run it.
                    cutOff && use == uses.last() -> ContentBlock.ToolResult(use.id, errorJson("This call was cut off before it was complete. Make it again, with less text."), isError = true)
                    lastRound -> ContentBlock.ToolResult(use.id, errorJson("Stopped: too many steps"), isError = true)
                    else -> {
                        val outcome = runTool(use)
                        calls += ToolCall(use.name, use.input, outcome)
                        outcome.proposalId?.let(proposals::add)
                        ContentBlock.ToolResult(use.id, outcome.content, outcome.isError)
                    }
                }
            }
            added += Message(Role.USER, results)

            if (lastRound) {
                val reply = response.message.text.ifBlank { GAVE_UP }
                added += Message(Role.ASSISTANT, listOf(ContentBlock.Text(reply)))
                return LoopResult(reply, added, calls, proposals, usage, cost, LoopEnd.ROUND_LIMIT)
            }
        }
        error("unreachable")
    }

    private suspend fun runTool(use: ContentBlock.ToolUse): ToolOutcome = try {
        tools.execute(use.name, use.input)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolOutcome(errorJson("Tool failed: ${e.message ?: e::class.simpleName}"), isError = true)
    }

    private fun errorJson(message: String) = buildJsonObject { put("error", message) }.toString()

    companion object {
        const val GAVE_UP = "I couldn't finish that in a few steps. Try a smaller request."
        const val REFUSED_REPLY = "I can't help with that one here."
    }
}
