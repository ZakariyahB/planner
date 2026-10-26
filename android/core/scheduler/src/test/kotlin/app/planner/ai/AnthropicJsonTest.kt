package app.planner.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnthropicJsonTest {
    private val tools = buildJsonArray { add(buildJsonObject { put("name", "get_day") }) }

    private fun encode(messages: List<Message>) =
        Json.parseToJsonElement(AnthropicJson.encodeRequest(LlmRequest("Be brief.", tools, messages))).jsonObject

    @Test
    fun `the system prompt is sent as a cacheable block`() {
        val system = encode(listOf(Message.user("hi")))["system"]!!.jsonArray.single().jsonObject
        assertEquals("text", system["type"]!!.jsonPrimitive.content)
        assertEquals("Be brief.", system["text"]!!.jsonPrimitive.content)
        assertEquals("ephemeral", system["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tools and a token limit are sent, but no model - the server chooses it`() {
        val body = encode(listOf(Message.user("hi")))
        assertEquals(tools, body["tools"])
        assertTrue("model" !in body)
        assertTrue(body["max_tokens"]!!.jsonPrimitive.int in 256..2048)
    }

    @Test
    fun `text, tool use and tool results are encoded as Anthropic content blocks`() {
        val input = buildJsonObject { put("date", "2026-10-28") }
        val body = encode(
            listOf(
                Message.user("what's today?"),
                Message(Role.ASSISTANT, listOf(ContentBlock.Text("Checking."), ContentBlock.ToolUse("toolu_1", "get_day", input))),
                Message(Role.USER, listOf(ContentBlock.ToolResult("toolu_1", """{"blocks":[]}""", isError = false))),
                Message(Role.USER, listOf(ContentBlock.ToolResult("toolu_2", "nope", isError = true))),
            ),
        )
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant", "user", "user"), messages.map { it["role"]!!.jsonPrimitive.content })

        val assistant = messages[1]["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("text", assistant[0]["type"]!!.jsonPrimitive.content)
        assertEquals("tool_use", assistant[1]["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", assistant[1]["id"]!!.jsonPrimitive.content)
        assertEquals(input, assistant[1]["input"])

        val ok = messages[2]["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_result", ok["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_1", ok["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("""{"blocks":[]}""", ok["content"]!!.jsonPrimitive.content)
        assertNull(ok["is_error"])

        val failed = messages[3]["content"]!!.jsonArray.single().jsonObject
        assertEquals(true, failed["is_error"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `a reply with text and a tool call is parsed with its usage`() {
        val response = AnthropicJson.parseResponse(
            """
            {"id":"msg_1","type":"message","role":"assistant","stop_reason":"tool_use",
             "content":[{"type":"text","text":"Let me look."},
                        {"type":"tool_use","id":"toolu_9","name":"get_day","input":{"date":"2026-10-28"}}],
             "usage":{"input_tokens":120,"output_tokens":30,"cache_creation_input_tokens":500,"cache_read_input_tokens":2000}}
            """.trimIndent(),
        )
        assertEquals("tool_use", response.stopReason)
        assertEquals(Role.ASSISTANT, response.message.role)
        val text = response.message.content[0] as ContentBlock.Text
        assertEquals("Let me look.", text.text)
        val call = response.message.content[1] as ContentBlock.ToolUse
        assertEquals("toolu_9", call.id)
        assertEquals("get_day", call.name)
        assertEquals("2026-10-28", call.input["date"]!!.jsonPrimitive.content)
        assertEquals(TokenUsage(input = 120, output = 30, cacheWrite = 500, cacheRead = 2000), response.usage)
    }

    @Test
    fun `usage fields that are missing count as zero, and unknown block types are skipped`() {
        val response = AnthropicJson.parseResponse(
            """{"stop_reason":"end_turn","content":[{"type":"thinking","thinking":"..."},{"type":"text","text":"Done."}],"usage":{"input_tokens":5,"output_tokens":2}}""",
        )
        assertEquals(1, response.message.content.size)
        assertEquals(TokenUsage(5, 2, 0, 0), response.usage)
    }

    @Test
    fun `the text of a reply joins its text blocks`() {
        val message = Message(Role.ASSISTANT, listOf(ContentBlock.Text("One."), ContentBlock.ToolUse("t", "x", JsonObject(emptyMap())), ContentBlock.Text("Two.")))
        assertEquals("One.\nTwo.", message.text)
    }

    @Test
    fun `a body that is not a message is rejected`() {
        assertFailsWith<IllegalArgumentException> { AnthropicJson.parseResponse("""{"error":{"type":"overloaded_error"}}""") }
        assertFailsWith<IllegalArgumentException> { AnthropicJson.parseResponse("not json") }
    }

    @Test
    fun `token usage adds up`() {
        assertEquals(TokenUsage(3, 4, 5, 6), TokenUsage(1, 1, 1, 1) + TokenUsage(2, 3, 4, 5))
    }
}
