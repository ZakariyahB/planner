package app.planner.assistant

import app.planner.assistant.conversation.Conversation
import app.planner.assistant.conversation.ConversationCodec
import app.planner.assistant.llm.ContentBlock
import app.planner.assistant.llm.Message
import app.planner.assistant.llm.Role
import app.planner.assistant.prompt.ContextSummary
import app.planner.engine.fakes.FakePlannerQueries
import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.Settings
import app.planner.engine.ports.ChatRole
import app.planner.engine.ports.ChatTurn
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ConversationAndContextTest {
    @Test
    fun `a conversation round-trips through its saved form`() {
        val c = Conversation(
            turns = listOf(
                ChatTurn("1", ChatRole.USER, "hi", Instant.ofEpochMilli(1000)),
                ChatTurn("2", ChatRole.ASSISTANT, "proposed", Instant.ofEpochMilli(2000), proposalId = "cs-1"),
            ),
            history = listOf(
                Message.user("ctx", "hi"),
                Message(Role.ASSISTANT, listOf(ContentBlock.Opaque(json("""{"type":"redacted_thinking","data":"zz"}""")), ContentBlock.Text("proposed"))),
            ),
        )
        assertEquals(c, ConversationCodec.decode(ConversationCodec.encode(c)))
    }

    @Test
    fun `unreadable saved data gives an empty conversation`() {
        assertEquals(Conversation(), ConversationCodec.decode(null))
        assertEquals(Conversation(), ConversationCodec.decode("not json"))
        val broken = ConversationCodec.decode("""{"turns":[{"id":"1","role":"USER","text":"hi","at":0}],"history":[{"role":"robot","content":[]}]}""")
        assertEquals(1, broken.turns.size)
        assertEquals(emptyList(), broken.history, "a partly readable history is dropped whole")
    }

    @Test
    fun `the context summary lists today, the week, the backlog and the settings`() = runTest {
        val week = FakePlannerQueries(now = { MONDAY_MORNING }).week(ReferenceWeek.monday)
        val text = ContextSummary.render(MONDAY_MORNING, week, ReferenceWeek.tasks, needsALook = 2, settings = Settings())
        assertTrue("Mon 2026-11-02 08:00 (Europe/London)" in text, text)
        assertTrue("- 09:30-11:30 P&S problem sheet [Academic]" in text, text)
        assertTrue("Tue 2026-11-03: 4 blocks" in text, text)
        assertTrue("Arabic hw (1h, Quran and Arabic, Tue 2026-11-03, after Asr) task_id=arabic-hw-tue" in text, text)
        assertTrue("Needs a look: 2" in text)
        assertTrue("hard cap 22:00; catch-up day Sunday" in text)
        assertTrue("week tags kept on rollover" in text)
    }
}
