package app.planner.assistant

import app.planner.assistant.llm.ContentBlock
import app.planner.assistant.llm.LlmResponse
import app.planner.assistant.llm.Message
import app.planner.assistant.llm.Role
import app.planner.assistant.tools.ToolExecutor
import app.planner.assistant.tools.ToolOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray

class ToolLoopTest {
    private val ran = mutableListOf<String>()
    private val tools = ToolExecutor { name, _ ->
        ran += name
        if (name == "boom") throw IllegalStateException("broken")
        ToolOutcome("""{"ok":"$name"}""", proposalId = if (name == "propose_changes") "cs-9" else null)
    }
    private val noTools = JsonArray(emptyList())

    private fun twoCalls() = LlmResponse(
        Message(
            Role.ASSISTANT,
            listOf(
                ContentBlock.ToolUse("a", "get_week", json("{}")),
                ContentBlock.ToolUse("b", "get_tasks", json("{}")),
            ),
        ),
        stopReason = "tool_use",
    )

    @Test
    fun `runs every tool asked for and sends all results back in one message`() = runTest {
        val llm = ScriptedLlm({ twoCalls() }, { reply("done") })
        val result = ToolLoop(llm, tools).run("sys", noTools, emptyList(), Message.user("hi"))

        assertEquals(listOf("get_week", "get_tasks"), ran)
        val results = llm.requests[1].messages.last()
        assertEquals(Role.USER, results.role)
        assertEquals(listOf("a", "b"), results.content.map { (it as ContentBlock.ToolResult).toolUseId })
        assertEquals("done", result.reply)
        assertEquals(LoopEnd.DONE, result.end)
        assertEquals(4, result.newMessages.size)
    }

    @Test
    fun `each request repeats the previous one exactly and only appends`() = runTest {
        val history = listOf(Message.user("earlier"), Message(Role.ASSISTANT, listOf(ContentBlock.Text("ok"))))
        val llm = ScriptedLlm({ twoCalls() }, { twoCalls() }, { reply("done") })
        ToolLoop(llm, tools).run("sys", noTools, history, Message.user("now"))

        for (i in 1 until llm.requests.size) {
            val before = llm.requests[i - 1]
            val after = llm.requests[i]
            assertEquals(before.system, after.system)
            assertEquals(before.tools, after.tools)
            assertEquals(before.messages, after.messages.take(before.messages.size), "request $i edited the history")
        }
        assertEquals(history, llm.requests.first().messages.take(2))
    }

    @Test
    fun `collects proposal ids from the tools`() = runTest {
        val llm = ScriptedLlm({ toolUse("propose_changes", "{}") }, { reply("proposed") })
        val result = ToolLoop(llm, tools).run("sys", noTools, emptyList(), Message.user("add"))
        assertEquals(listOf("cs-9"), result.proposalIds)
    }

    @Test
    fun `a failing tool becomes an error result, not a crash`() = runTest {
        val llm = ScriptedLlm({ toolUse("boom", "{}") }, { r ->
            val res = r.messages.last().content.single() as ContentBlock.ToolResult
            assertTrue(res.isError)
            assertTrue("broken" in res.content)
            reply("sorry")
        })
        assertEquals("sorry", ToolLoop(llm, tools).run("sys", noTools, emptyList(), Message.user("x")).reply)
    }

    @Test
    fun `a refusal keeps nothing from the turn`() = runTest {
        val llm = ScriptedLlm({ toolUse("get_week", "{}") }, { LlmResponse(Message(Role.ASSISTANT, emptyList()), "refusal") })
        val result = ToolLoop(llm, tools).run("sys", noTools, emptyList(), Message.user("x"))
        assertEquals(LoopEnd.REFUSED, result.end)
        assertEquals(emptyList(), result.newMessages)
        assertEquals(ToolLoop.REFUSED_REPLY, result.reply)
    }

    @Test
    fun `a tool call cut off by max_tokens is never run`() = runTest {
        val llm = ScriptedLlm(
            { LlmResponse(Message(Role.ASSISTANT, listOf(ContentBlock.ToolUse("c", "propose_changes", json("{}")))), "max_tokens") },
            { r ->
                assertTrue((r.messages.last().content.single() as ContentBlock.ToolResult).isError)
                reply("retrying later")
            },
        )
        val result = ToolLoop(llm, tools).run("sys", noTools, emptyList(), Message.user("x"))
        assertEquals(emptyList(), ran)
        assertEquals(emptyList(), result.proposalIds)
    }

    @Test
    fun `stops at the round limit and closes the turn with an answer`() = runTest {
        val llm = ScriptedLlm({ toolUse("get_week", "{}") }, { toolUse("get_week", "{}") })
        val result = ToolLoop(llm, tools, maxRounds = 2).run("sys", noTools, emptyList(), Message.user("x"))
        assertEquals(LoopEnd.ROUND_LIMIT, result.end)
        assertEquals(1, ran.size, "the last round's calls are not run")
        assertEquals(Role.ASSISTANT, result.newMessages.last().role)
        assertEquals(ToolLoop.GAVE_UP, result.reply)
    }
}
