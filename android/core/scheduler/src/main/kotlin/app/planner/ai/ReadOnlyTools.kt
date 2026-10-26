package app.planner.ai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Lets a run look at the day but never change it: scheduled runs that nobody is watching, such as the
 * 23:30 note on tomorrow's plan, must not move tasks or add anything on their own.
 */
class ReadOnlyTools(private val inner: ToolExecutor) : ToolExecutor {
    override suspend fun execute(name: String, input: JsonObject): ToolOutcome =
        if (name in AiToolNames.readOnly) {
            inner.execute(name, input)
        } else {
            ToolOutcome(
                buildJsonObject { put("error", "This is a read-only run: $name is not available. Describe the idea in words instead.") }.toString(),
                isError = true,
            )
        }
}
