package app.planner.assistant

import app.planner.assistant.conversation.Conversation
import app.planner.assistant.conversation.ConversationCodec
import app.planner.assistant.conversation.InMemoryConversationStore
import app.planner.assistant.llm.ContentBlock
import app.planner.assistant.llm.LlmException
import app.planner.assistant.llm.LlmFailure
import app.planner.assistant.llm.LlmResponse
import app.planner.assistant.llm.Message
import app.planner.assistant.llm.Role
import app.planner.assistant.tools.ToolNames
import app.planner.engine.fakes.FakePlannerQueries
import app.planner.engine.fakes.InMemorySettingsStore
import app.planner.engine.fakes.InMemoryTaskStore
import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.Settings
import app.planner.engine.ports.ChatRole
import app.planner.engine.ports.NowProvider
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class AssistantServiceTest {
    private val port = RecordingProposalPort()
    private val store = InMemoryConversationStore()

    private fun service(llm: ScriptedLlm, maxHistoryChars: Int = 120_000) = AssistantService(
        llm = llm,
        prompts = ::loadPrompts,
        queries = FakePlannerQueries(now = { MONDAY_MORNING }),
        proposals = port,
        tasks = InMemoryTaskStore(ReferenceWeek.tasks),
        settings = InMemorySettingsStore(Settings(assistantModel = "claude-sonnet-5-5")),
        store = store,
        now = NowProvider { MONDAY_MORNING },
        clock = { Instant.EPOCH },
        maxHistoryChars = maxHistoryChars,
    )

    @Test
    fun `sends the frozen v2 prompt, the tools, the chosen model and a context block before the user's words`() = runTest {
        val llm = ScriptedLlm({ reply("hi") })
        service(llm).send("hello")
        val req = llm.requests.single()
        val prompts = loadPrompts()
        assertEquals(prompts.system, req.system)
        assertEquals(prompts.tools, req.tools)
        assertEquals("claude-sonnet-5-5", req.model)
        val (context, words) = req.messages.single().content.map { (it as ContentBlock.Text).text }
        assertTrue(context.startsWith("[App context"))
        assertTrue("Backlog" in context)
        assertEquals("hello", words)
    }

    @Test
    fun `the second message replays the first exchange unchanged, with the same system prompt`() = runTest {
        val thinking = ContentBlock.Opaque(json("""{"type":"thinking","thinking":"","signature":"s"}"""))
        val llm = ScriptedLlm(
            { LlmResponse(Message(Role.ASSISTANT, listOf(thinking, ContentBlock.ToolUse("t", ToolNames.GET_TASKS, json("{}")))), "tool_use") },
            { reply("first") },
            { reply("second") },
        )
        val s = service(llm)
        s.send("one")
        s.send("two")
        val firstTurnEnd = llm.requests[1].messages + Message(Role.ASSISTANT, listOf(ContentBlock.Text("first")))
        assertEquals(firstTurnEnd, llm.requests[2].messages.take(firstTurnEnd.size))
        assertEquals(llm.requests[0].system, llm.requests[2].system)
        assertTrue(thinking in llm.requests[2].messages[1].content, "thinking is replayed")
    }

    @Test
    fun `turns show the user, the reply and the proposal for an inline card`() = runTest {
        val llm = ScriptedLlm(
            { toolUse(ToolNames.REPLAN, """{"reason":"rollover","target_week_start":"2026-11-09"}""") },
            { reply("Proposed next week.") },
        )
        val s = service(llm)
        val answer = s.send("copy template into next week")
        val turns = s.observeConversation().first()
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), turns.map { it.role })
        assertEquals("cs-1", turns.last().proposalId)
        assertEquals(listOf("cs-1"), answer.proposalIds)
    }

    @Test
    fun `several proposals in one reply each get a turn with their id`() = runTest {
        val llm = ScriptedLlm(
            { toolUse(ToolNames.REPLAN, """{"reason":"rollover","target_week_start":"2026-11-09"}""") },
            { toolUse(ToolNames.REPLAN, """{"reason":"plan_days","days":["2026-11-02"]}""") },
            { reply("") },
        )
        val s = service(llm)
        val answer = s.send("do both")
        val turns = s.observeConversation().first()
        assertEquals(listOf("cs-1", "cs-2"), answer.proposalIds)
        assertEquals(listOf(null, "cs-1", "cs-2"), turns.map { it.proposalId })
        assertEquals("I've put that on the approval card.", answer.text)
    }

    @Test
    fun `the conversation survives a restart`() = runTest {
        service(ScriptedLlm({ reply("remembered") })).send("hello")
        val llm = ScriptedLlm({ reply("again") })
        val again = service(llm)
        assertEquals(2, again.observeConversation().first().size)
        again.send("still there?")
        assertEquals(3, llm.requests.single().messages.size, "the saved exchange is replayed")
    }

    @Test
    fun `a failed call shows a note and leaves the model's history as it was`() = runTest {
        val s = service(ScriptedLlm({ reply("ok") }, { throw LlmException(LlmFailure.CAP_REACHED, "Monthly AI cap reached (402)") }))
        s.send("one")
        val before = store.load().history
        val answer = s.send("two")
        assertEquals("Monthly AI cap reached (402)", answer.error)
        assertEquals(before, store.load().history)
        assertEquals(ChatRole.NOTE, s.observeConversation().first().last().role)
    }

    @Test
    fun `a refusal does not enter the history`() = runTest {
        val s = service(ScriptedLlm({ LlmResponse(Message(Role.ASSISTANT, emptyList()), "refusal") }))
        val answer = s.send("something odd")
        assertEquals(ToolLoop.REFUSED_REPLY, answer.text)
        assertEquals(emptyList(), store.load().history)
    }

    @Test
    fun `a long history starts afresh for the model instead of being edited`() = runTest {
        val big = Conversation(history = List(4) { Message.user("x".repeat(1_000)) })
        store.save(big)
        val llm = ScriptedLlm({ reply("fresh") })
        val s = service(llm, maxHistoryChars = 2_000)
        s.send("hello")
        assertEquals(1, llm.requests.single().messages.size)
        assertNotNull(s.observeConversation().first().firstOrNull { it.role == ChatRole.NOTE })
        assertTrue(ConversationCodec.encode(store.load()).length < 10_000)
    }

    @Test
    fun `clear forgets everything`() = runTest {
        val s = service(ScriptedLlm({ reply("a") }))
        s.send("hello")
        s.clear()
        assertEquals(emptyList(), s.observeConversation().first())
        assertEquals(Conversation(), store.load())
    }

    @Test
    fun `an empty message is not sent`() = runTest {
        val llm = ScriptedLlm()
        val answer = service(llm).send("   ")
        assertNotNull(answer.error)
        assertEquals(0, llm.requests.size)
    }
}
