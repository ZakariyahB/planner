package app.planner.assistant.tools

import kotlinx.serialization.json.JsonObject

/** What a tool returned. [content] is JSON text the model reads; [proposalId] is set when a ChangeSet was proposed. */
data class ToolOutcome(val content: String, val isError: Boolean = false, val proposalId: String? = null)

/** Runs the tools the model asks for. */
fun interface ToolExecutor {
    suspend fun execute(name: String, input: JsonObject): ToolOutcome
}
