package app.planner.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentTest {
    private val tools = JsonArray(emptyList())

    private fun reply(text: String, usage: TokenUsage = TokenUsage(10, 5, 0, 0), cost: Double = 0.01) =
        LlmResponse(Message.assistant(text), "end_turn", usage, cost)

    private fun toolCall(
        vararg calls: Pair<String, String>,
        text: String = "",
        usage: TokenUsage = TokenUsage(10, 5, 0, 0),
        cost: Double = 0.01,
    ): LlmResponse {
        val blocks = buildList<ContentBlock> {
            if (text.isNotEmpty()) add(ContentBlock.Text(text))
            calls.forEach { (id, name) -> add(ContentBlock.ToolUse(id, name, buildJsonObject { put("date", "2026-10-28") })) }
        }
        return LlmResponse(Message(Role.ASSISTANT, blocks), "tool_use", usage, cost)
    }

    private class ScriptedLlm(private val script: List<LlmResponse>) : LlmClient {
        val requests = mutableListOf<LlmRequest>()

        override suspend fun send(request: LlmRequest): LlmResponse {
            requests += request
            return script.getOrElse(requests.size - 1) { script.last() }
        }
    }

    private class RecordingTools(private val answer: (String, JsonObject) -> ToolOutcome = { _, _ -> ToolOutcome("{}") }) : ToolExecutor {
        val calls = mutableListOf<Pair<String, JsonObject>>()

        override suspend fun execute(name: String, input: JsonObject): ToolOutcome {
            calls += name to input
            return answer(name, input)
        }
    }

    private fun agent(llm: LlmClient, executor: ToolExecutor, maxRounds: Int = 8) = Agent(llm, executor, "system rules", tools, maxRounds)

    @Test
    fun `a plain answer needs no tools`() = runBlocking {
        val llm = ScriptedLlm(listOf(reply("Morning!")))
        val result = agent(llm, RecordingTools()).run(emptyList(), "hi")
        assertEquals("Morning!", result.reply)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), result.newMessages.map { it.role })
        assertTrue(result.toolCalls.isEmpty())
        assertFalse(result.hitLimit)
    }

    @Test
    fun `a tool call is run and its result is sent back before the final answer`() = runBlocking {
        val llm = ScriptedLlm(listOf(toolCall("t1" to "get_day"), reply("You have BoL at 10:15.")))
        val executor = RecordingTools { _, _ -> ToolOutcome("""{"blocks":[]}""") }
        val result = agent(llm, executor).run(emptyList(), "what's on?")

        assertEquals(listOf("get_day"), executor.calls.map { it.first })
        assertEquals("2026-10-28", executor.calls[0].second["date"]!!.jsonPrimitive.content)
        assertEquals("You have BoL at 10:15.", result.reply)

        val second = llm.requests[1].messages
        val toolResult = (second.last().content.single() as ContentBlock.ToolResult)
        assertEquals("t1", toolResult.toolUseId)
        assertEquals("""{"blocks":[]}""", toolResult.content)
        assertEquals(Role.USER, second.last().role)
        assertEquals(listOf("get_day"), result.toolCalls.map { it.name })
    }

    @Test
    fun `several tool calls in one reply are answered together, in order`() = runBlocking {
        val llm = ScriptedLlm(listOf(toolCall("a" to "get_day", "b" to "get_tasks"), reply("ok")))
        val result = agent(llm, RecordingTools { name, _ -> ToolOutcome("""{"from":"$name"}""") }).run(emptyList(), "plan")
        val results = llm.requests[1].messages.last().content.map { it as ContentBlock.ToolResult }
        assertEquals(listOf("a", "b"), results.map { it.toolUseId })
        assertEquals(listOf("""{"from":"get_day"}""", """{"from":"get_tasks"}"""), results.map { it.content })
        assertEquals(2, result.toolCalls.size)
    }

    @Test
    fun `a tool that throws is reported to the model as an error and the loop carries on`() = runBlocking {
        val llm = ScriptedLlm(listOf(toolCall("t1" to "get_day"), reply("Sorry, could not check.")))
        val result = agent(llm, RecordingTools { _, _ -> error("db locked") }).run(emptyList(), "what's on?")
        val toolResult = llm.requests[1].messages.last().content.single() as ContentBlock.ToolResult
        assertTrue(toolResult.isError)
        assertTrue("db locked" in toolResult.content)
        assertEquals("Sorry, could not check.", result.reply)
        assertTrue(result.toolCalls.single().outcome.isError)
    }

    @Test
    fun `a tool's own error outcome is passed on as an error`() = runBlocking {
        val llm = ScriptedLlm(listOf(toolCall("t1" to "apply_changes"), reply("Needs your approval.")))
        agent(llm, RecordingTools { _, _ -> ToolOutcome("""{"error":"needs approval"}""", isError = true) }).run(emptyList(), "do it")
        assertTrue((llm.requests[1].messages.last().content.single() as ContentBlock.ToolResult).isError)
    }

    @Test
    fun `cancelling the run is not swallowed as a tool error`() {
        val llm = ScriptedLlm(listOf(toolCall("t1" to "get_day"), reply("never")))
        assertFailsWith<CancellationException> {
            runBlocking { agent(llm, RecordingTools { _, _ -> throw CancellationException("stop") }).run(emptyList(), "x") }
        }
    }

    @Test
    fun `a model that keeps calling tools is stopped after the round limit`() = runBlocking {
        val llm = ScriptedLlm(listOf(toolCall("t" to "get_day", text = "Still looking.")))
        val result = agent(llm, RecordingTools(), maxRounds = 3).run(emptyList(), "loop")
        assertEquals(3, llm.requests.size)
        assertTrue(result.hitLimit)
        assertTrue(result.reply.isNotBlank())
    }

    @Test
    fun `usage and cost add up across rounds`() = runBlocking {
        val llm = ScriptedLlm(
            listOf(
                toolCall("t" to "get_day", usage = TokenUsage(100, 20, 500, 0), cost = 0.02),
                reply("done", usage = TokenUsage(150, 30, 0, 500), cost = 0.01),
            ),
        )
        val result = agent(llm, RecordingTools()).run(emptyList(), "x")
        assertEquals(TokenUsage(250, 50, 500, 500), result.usage)
        assertEquals(0.03, result.costGbp, 1e-9)
    }

    @Test
    fun `history, the system prompt, tools and purpose reach the model`() = runBlocking {
        val llm = ScriptedLlm(listOf(reply("ok")))
        val history = listOf(Message.user("earlier"), Message.assistant("reply"))
        agent(llm, RecordingTools()).run(history, "now", Purpose.REPLAN)
        val request = llm.requests.single()
        assertEquals("system rules", request.system)
        assertEquals(tools, request.tools)
        assertEquals(Purpose.REPLAN, request.purpose)
        assertEquals(listOf("earlier", "reply", "now"), request.messages.map { it.text })
    }

    @Test
    fun `the new messages contain the whole exchange, ready to keep`() = runBlocking {
        val llm = ScriptedLlm(listOf(toolCall("t1" to "get_day", text = "Checking."), reply("Free until 10:15.")))
        val result = agent(llm, RecordingTools()).run(listOf(Message.user("a"), Message.assistant("b")), "free?")
        // user text, assistant tool call, tool result, assistant answer
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER, Role.ASSISTANT), result.newMessages.map { it.role })
        assertEquals("free?", result.newMessages.first().text)
    }

    @Test
    fun `a failing model call surfaces to the caller`() {
        val llm = LlmClient { throw IllegalStateException("AI unavailable (401)") }
        assertFailsWith<IllegalStateException> { runBlocking { agent(llm, RecordingTools()).run(emptyList(), "hi") } }
    }
}
