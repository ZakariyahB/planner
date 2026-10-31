package app.planner.assistant

import app.planner.assistant.prompt.PromptBundle
import app.planner.assistant.tools.ToolNames
import app.planner.engine.model.CalendarCategory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** docs/prompts/system-v2.md and tools-v2.json are shipped as-is; keep them consistent with the code that runs them. */
class PromptFilesTest {
    private val bundle = loadPrompts()
    private val tools = bundle.tools.map { it.jsonObject }
    private fun tool(name: String) = tools.single { it["name"]!!.jsonPrimitive.content == name }
    private fun JsonObject.props() = this["input_schema"]!!.jsonObject["properties"]!!.jsonObject
    private fun JsonObject.enumOf(key: String) = this[key]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `tools-v2 defines exactly the six tools the assistant can run`() {
        assertEquals(ToolNames.all.sorted(), tools.map { it["name"]!!.jsonPrimitive.content }.sorted())
        assertEquals(6, tools.size)
    }

    @Test
    fun `no tool can apply, approve or write`() {
        val forbidden = listOf("apply", "approve", "write", "commit", "execute", "undo")
        for (t in tools) {
            val name = t["name"]!!.jsonPrimitive.content
            assertTrue(forbidden.none { it in name }, "$name looks like a write tool")
        }
    }

    @Test
    fun `every tool says what it does and takes an object whose required inputs are declared`() {
        for (t in tools) {
            val name = t["name"]!!.jsonPrimitive.content
            assertTrue(t["description"]!!.jsonPrimitive.content.length >= 60, "$name needs a real description")
            val schema = t["input_schema"]!!.jsonObject
            assertEquals("object", schema["type"]!!.jsonPrimitive.content, name)
            val declared = t.props().keys
            val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            assertTrue(declared.containsAll(required), "$name requires undeclared inputs")
        }
        val item = tool(ToolNames.PROPOSE_CHANGES).props()["changes"]!!.jsonObject["items"]!!.jsonObject
        assertTrue(item["properties"]!!.jsonObject.keys.containsAll(item["required"]!!.jsonArray.map { it.jsonPrimitive.content }))
    }

    @Test
    fun `enums match what the executor accepts`() {
        val item = tool(ToolNames.PROPOSE_CHANGES).props()["changes"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject
        assertEquals(ToolNames.Actions.all, item.enumOf("action"))
        assertEquals(ToolNames.Scopes.all, item.enumOf("scope"))
        assertEquals(ToolNames.afterPrayers, item.enumOf("after_prayer"))
        assertEquals(CalendarCategory.entries.map { it.calendarName }, item.enumOf("category"))
        assertEquals(ToolNames.Reasons.all, tool(ToolNames.REPLAN).props().enumOf("reason"))
    }

    @Test
    fun `the read tools are described as read-only and the proposing tools as writing nothing`() {
        for (name in ToolNames.readOnly) assertTrue(tool(name)["description"]!!.jsonPrimitive.content.startsWith("Read-only."), name)
        for (name in ToolNames.proposing) assertTrue("writes nothing" in tool(name)["description"]!!.jsonPrimitive.content, name)
    }

    @Test
    fun `the system prompt includes the categories table and every tool, and states the rules`() {
        val categories = File(promptDir, PromptBundle.CATEGORIES_FILE).readText().trim()
        assertTrue(categories in bundle.system, "the categories table is included verbatim")
        assertFalse(PromptBundle.CATEGORIES_PLACEHOLDER in bundle.system)
        for (name in ToolNames.all) assertTrue(name in bundle.system, "system-v2.md should mention $name")
        for (phrase in listOf("approval", "there is no tool for it", "Never calculate", "15-minute slot", "Tier 3", "catch-up day", "this instance", "read-only")) {
            assertTrue(phrase in bundle.system, "system-v2.md should say \"$phrase\"")
        }
        for (c in CalendarCategory.entries) assertTrue(c.calendarName in bundle.system)
    }

    @Test
    fun `the system prompt is free of the legacy apply rules`() {
        assertFalse("apply_changes" in bundle.system)
        assertFalse("add_task" in bundle.system)
    }

    @Test
    fun `the template must contain the categories placeholder`() {
        val ok = runCatching { PromptBundle.assemble("no placeholder", "x", "[]") }
        assertTrue(ok.isFailure)
    }
}
