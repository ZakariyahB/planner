package app.planner.assistant

import app.planner.assistant.llm.ContentBlock
import app.planner.assistant.llm.LlmRequest
import app.planner.assistant.llm.Message
import app.planner.assistant.llm.MessagesJson
import app.planner.assistant.llm.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MessagesJsonTest {
    private val thinking = """{"type":"thinking","thinking":"","signature":"sig-abc=="}"""

    @Test
    fun `encodes model, limits, cached system, effort, tools and messages`() {
        val req = LlmRequest(
            system = "rules",
            tools = JsonArray(listOf(json("""{"name":"get_week"}"""))),
            messages = listOf(
                Message.user("hi"),
                Message(Role.ASSISTANT, listOf(ContentBlock.ToolUse("t1", "get_week", json("{}")))),
                Message(Role.USER, listOf(ContentBlock.ToolResult("t1", """{"error":"x"}""", isError = true))),
            ),
            model = "claude-sonnet-5-5",
            maxTokens = 4096,
            effort = "medium",
        )
        val body = json(MessagesJson.encodeRequest(req))
        assertEquals("claude-sonnet-5-5", body["model"]!!.jsonPrimitive.content)
        assertEquals(4096, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        val system = body["system"]!!.jsonArray.single().jsonObject
        assertEquals("rules", system["text"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", system["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", body["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("get_week", body["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        val result = body["messages"]!!.jsonArray[2].jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_result", result["type"]!!.jsonPrimitive.content)
        assertEquals("t1", result["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("true", result["is_error"]!!.jsonPrimitive.content)
        // No thinking config is sent: current models think adaptively by default, and forcing it off is a 400.
        assertNull(body["thinking"])
        assertNull(body["tool_choice"])
    }

    @Test
    fun `leaves the model and effort out when not set`() {
        val body = json(MessagesJson.encodeRequest(LlmRequest("s", JsonArray(emptyList()), listOf(Message.user("x")), model = null, effort = null)))
        assertNull(body["model"])
        assertNull(body["output_config"])
    }

    @Test
    fun `parses text, tool calls, stop reason, usage and model`() {
        val r = MessagesJson.parseResponse(
            """{"type":"message","model":"claude-sonnet-5-5","stop_reason":"tool_use",
               "content":[$thinking,{"type":"text","text":"Looking"},{"type":"tool_use","id":"tu1","name":"find_events","input":{"query":"Halaqa"}}],
               "usage":{"input_tokens":10,"output_tokens":5,"cache_creation_input_tokens":3,"cache_read_input_tokens":2}}""",
        )
        assertEquals("tool_use", r.stopReason)
        assertEquals("claude-sonnet-5-5", r.model)
        assertEquals("Looking", r.message.text)
        assertEquals("Halaqa", r.message.toolUses.single().input["query"]!!.jsonPrimitive.content)
        assertEquals(listOf(10, 5, 3, 2), listOf(r.usage.input, r.usage.output, r.usage.cacheWrite, r.usage.cacheRead))
    }

    @Test
    fun `thinking blocks are kept and sent back byte for byte`() {
        val r = MessagesJson.parseResponse("""{"content":[$thinking,{"type":"text","text":"ok"}],"stop_reason":"end_turn"}""")
        assertIs<ContentBlock.Opaque>(r.message.content.first())
        val again = MessagesJson.encodeMessage(r.message)["content"]!!.jsonArray.first().toString()
        assertEquals(json(thinking).toString(), again)
    }

    @Test
    fun `after a server-side fallback, only text survives from before the switch`() {
        val r = MessagesJson.parseResponse(
            """{"stop_reason":"end_turn","content":[
                {"type":"thinking","thinking":"","signature":"a"},
                {"type":"text","text":"Partial"},
                {"type":"tool_use","id":"x","name":"replan","input":{}},
                {"type":"fallback","from":{"model":"claude-sonnet-5-5"},"to":{"model":"claude-sonnet-5"}},
                {"type":"text","text":"Answer"}]}""",
        )
        assertEquals(listOf("Partial", "Answer"), r.message.content.map { (it as ContentBlock.Text).text })
        assertEquals(emptyList(), r.message.toolUses)
    }

    @Test
    fun `rejects bodies that are not messages`() {
        assertFailsWith<IllegalArgumentException> { MessagesJson.parseResponse("nope") }
        assertFailsWith<IllegalArgumentException> { MessagesJson.parseResponse("""{"type":"error","error":{"type":"overloaded_error"}}""") }
        assertFailsWith<IllegalArgumentException> { MessagesJson.parseResponse("""{"id":"m"}""") }
    }

    @Test
    fun `messages round-trip through the saved form`() {
        val m = Message(
            Role.ASSISTANT,
            listOf(
                ContentBlock.Opaque(json(thinking)),
                ContentBlock.Text("t"),
                ContentBlock.ToolUse("id", "get_tasks", json("""{"status":"all"}""")),
            ),
        )
        val u = Message(Role.USER, listOf(ContentBlock.ToolResult("id", """{"tasks":[]}"""), ContentBlock.ToolResult("id2", "e", isError = true)))
        assertEquals(m, MessagesJson.decodeMessage(MessagesJson.encodeMessage(m)))
        assertEquals(u, MessagesJson.decodeMessage(MessagesJson.encodeMessage(u)))
    }
}
