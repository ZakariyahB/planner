package app.planner.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The prompt and tool definitions are shipped in the app from docs/prompts; keep them consistent with the code. */
class PromptFilesTest {
    // Gradle runs tests from the module directory: android/core/scheduler.
    private val promptDir = File("../../../docs/prompts")

    private val tools = Json.parseToJsonElement(File(promptDir, "tools.json").readText()).jsonArray.map { it.jsonObject }

    @Test
    fun `tools json defines exactly the tools the app can run`() {
        assertEquals(AiToolNames.all.sorted(), tools.map { it["name"]!!.jsonPrimitive.content }.sorted())
    }

    @Test
    fun `every tool says what it does and takes an object`() {
        for (tool in tools) {
            val name = tool["name"]!!.jsonPrimitive.content
            assertTrue(tool["description"]!!.jsonPrimitive.content.length >= 30, "$name needs a real description")
            val schema = tool["input_schema"]!!.jsonObject
            assertEquals("object", schema["type"]!!.jsonPrimitive.content, name)
            assertTrue(schema["properties"] is JsonObject, "$name needs properties")
        }
    }

    @Test
    fun `required inputs are all declared as properties`() {
        for (tool in tools) {
            val schema = tool["input_schema"]!!.jsonObject
            val declared = schema["properties"]!!.jsonObject.keys
            val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            assertTrue(declared.containsAll(required), "${tool["name"]} requires something it does not declare")
        }
    }

    @Test
    fun `the proposal ops in the schema match what the parser accepts`() {
        val propose = tools.first { it["name"]!!.jsonPrimitive.content == AiToolNames.PROPOSE_CHANGES }
        val ops = propose["input_schema"]!!.jsonObject["properties"]!!.jsonObject["ops"]!!.jsonObject
        val kinds = ops["items"]!!.jsonObject["properties"]!!.jsonObject["op"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("move_block", "remove_block", "drop_task"), kinds)
    }

    @Test
    fun `the system prompt carries the rules the app relies on`() {
        val prompt = File(promptDir, "system.md").readText()
        for (phrase in listOf("propose_changes", "apply_changes", "not medical advice", "Undo", "Sunday")) {
            assertTrue(phrase in prompt, "system.md should mention \"$phrase\"")
        }
        assertTrue(prompt.length < 8_000, "keep the system prompt short: it is sent on every call")
    }
}
