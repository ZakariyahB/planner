package app.planner.assistant

import app.planner.assistant.RecordingProposalPort.Call
import app.planner.assistant.conversation.InMemoryConversationStore
import app.planner.assistant.llm.ContentBlock
import app.planner.assistant.tools.ToolNames
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.fakes.FakePlannerQueries
import app.planner.engine.fakes.InMemorySettingsStore
import app.planner.engine.fakes.InMemoryTaskStore
import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.EventRef
import app.planner.engine.ports.ChangeDraft
import app.planner.engine.ports.NowProvider
import app.planner.engine.ports.ProposalOutcome
import app.planner.engine.ports.ReplanReason
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * newplan 9 example intents, end to end through AssistantService with a scripted model. Each must produce exactly
 * the right ProposalPort call and nothing else: the assistant proposes, it never writes (it has nothing to write
 * with). The scripted model reads event ids out of the real find_events results, as the real model must.
 */
class ExampleIntentsTest {
    private val port = RecordingProposalPort()
    private val tuesday = LocalDate.of(2026, 11, 3)
    private val thursday = LocalDate.of(2026, 11, 5)
    private val sunday = LocalDate.of(2026, 11, 8)
    private val nextMonday = LocalDate.of(2026, 11, 9)

    private fun service(llm: ScriptedLlm) = AssistantService(
        llm = llm,
        prompts = ::loadPrompts,
        queries = FakePlannerQueries(now = { MONDAY_MORNING }),
        proposals = port,
        tasks = InMemoryTaskStore(ReferenceWeek.tasks),
        settings = InMemorySettingsStore(),
        store = InMemoryConversationStore(),
        now = NowProvider { MONDAY_MORNING },
    )

    /** The (calendar_id, event_id) of every candidate in the last find_events result. */
    private fun candidates(r: app.planner.assistant.llm.LlmRequest): List<Pair<String, String>> =
        r.lastToolResults().single()["candidates"]!!.jsonArray.map {
            val o = it.jsonObject
            o["calendar_id"]!!.jsonPrimitive.content to o["event_id"]!!.jsonPrimitive.content
        }

    private fun assertOnlyCall(expected: Call) {
        assertEquals(listOf(expected), port.calls, "exactly one ProposalPort call, and nothing else")
    }

    @Test
    fun `halaqa ran over by 1h replans around the exact event`() = runTest {
        val llm = ScriptedLlm(
            { toolUse(ToolNames.FIND_EVENTS, """{"query":"Halaqa"}""") },
            { r ->
                val (cal, id) = candidates(r).single()
                toolUse(ToolNames.REPLAN, """{"reason":"overrun","calendar_id":"$cal","event_id":"$id","extra_min":60}""")
            },
            { reply("Halaqa ran an hour over, so I've proposed moving what came after. It's waiting for your approval.") },
        )
        val answer = service(llm).send("halaqa ran over by 1h")

        assertOnlyCall(Call.Replan(ReplanReason.Overrun(EventRef("cal-personal", "halaqa-wed"), 60), ChangeSource.ASSISTANT))
        assertEquals(listOf("cs-1"), answer.proposalIds)
        assertEquals(null, answer.error)
    }

    @Test
    fun `remove Quran class finds it, then proposes removing that exact event`() = runTest {
        val llm = ScriptedLlm(
            { toolUse(ToolNames.FIND_EVENTS, """{"query":"Quran class"}""") },
            { r ->
                val (cal, id) = candidates(r).single()
                toolUse(ToolNames.PROPOSE_CHANGES, """{"summary":"Remove Quran class","changes":[{"action":"remove","calendar_id":"$cal","event_id":"$id"}]}""")
            },
            { reply("I've proposed removing Quran class (Sh Anass) on Thursday 18:00. Approve it on the card.") },
        )
        val answer = service(llm).send("remove Quran class")

        assertOnlyCall(
            Call.Propose(
                listOf(ChangeDraft.RemoveEvent(EventRef("cal-personal", "quran-class-thu"), RecurrenceScope.THIS_INSTANCE)),
                ChangeSource.ASSISTANT, "Remove Quran class",
            ),
        )
        assertEquals(listOf("cs-1"), answer.proposalIds)
    }

    @Test
    fun `push tomorrow's lectures to Thursday moves each lecture found tomorrow`() = runTest {
        val llm = ScriptedLlm(
            { toolUse(ToolNames.FIND_EVENTS, """{"query":"Lecture","from":"$tuesday","to":"$tuesday"}""") },
            { r ->
                val moves = candidates(r).joinToString(",") { (cal, id) ->
                    """{"action":"move","calendar_id":"$cal","event_id":"$id","to_day":"$thursday"}"""
                }
                toolUse(ToolNames.PROPOSE_CHANGES, """{"summary":"Move tomorrow's lectures to Thursday","changes":[$moves]}""")
            },
            { reply("Both of tomorrow's lectures are proposed for Thursday; the engine found the times.") },
        )
        service(llm).send("push tomorrow's lectures to Thursday")

        val lectures = ReferenceWeek.calendarId(CalendarCategory.LECTURES)
        assertOnlyCall(
            Call.Propose(
                listOf(
                    ChangeDraft.MoveEvent(EventRef(lectures, "lec-sde-tue-1"), thursday),
                    ChangeDraft.MoveEvent(EventRef(lectures, "lec-os-tue-1"), thursday),
                ),
                ChangeSource.ASSISTANT, "Move tomorrow's lectures to Thursday",
            ),
        )
    }

    @Test
    fun `add 2h internship apps Sunday proposes one new task with no time in it`() = runTest {
        val llm = ScriptedLlm(
            {
                toolUse(
                    ToolNames.PROPOSE_CHANGES,
                    """{"summary":"Add internship apps on Sunday","changes":[{"action":"add","title":"Internship apps","duration_min":120,"day":"$sunday","category":"Personal"}]}""",
                )
            },
            { reply("Proposed: Internship apps, 2h on Sunday. It's on the approval card.") },
        )
        service(llm).send("add 2h internship apps Sunday")

        assertOnlyCall(
            Call.Propose(
                listOf(ChangeDraft.AddTask("Internship apps", CalendarCategory.PERSONAL, 120, sunday)),
                ChangeSource.ASSISTANT, "Add internship apps on Sunday",
            ),
        )
    }

    @Test
    fun `copy template into next week asks the engine for a rollover`() = runTest {
        val llm = ScriptedLlm(
            { toolUse(ToolNames.REPLAN, """{"reason":"rollover","target_week_start":"$nextMonday"}""") },
            { reply("I've proposed next week from the template, re-timed to that week's prayer times.") },
        )
        service(llm).send("copy template into next week")

        assertOnlyCall(Call.Rollover(nextMonday, ChangeSource.ASSISTANT))
    }

    @Test
    fun `the context tells the model which dates tomorrow, Thursday, Sunday and next week are`() = runTest {
        val llm = ScriptedLlm({ reply("ok") })
        service(llm).send("hello")
        val context = (llm.requests.single().messages.single().content.first() as ContentBlock.Text).text
        assertTrue("tomorrow Tue $tuesday" in context, context)
        assertTrue("Thu $thursday" in context)
        assertTrue("Sun $sunday" in context)
        assertTrue("next week starts $nextMonday" in context)
    }

    @Test
    fun `an ambiguous target is relayed as a question and nothing is proposed`() = runTest {
        port.answer = { ProposalOutcome.NeedsChoice("Which lecture?", emptyList()) }
        val llm = ScriptedLlm(
            {
                toolUse(
                    ToolNames.PROPOSE_CHANGES,
                    """{"summary":"Remove lecture","changes":[{"action":"remove","calendar_id":"cal-lectures","event_id":"lec-sde-tue-1"}]}""",
                )
            },
            { r ->
                assertEquals("needs_choice", r.lastToolResults().single()["status"]!!.jsonPrimitive.content)
                reply("Which lecture do you mean?")
            },
        )
        val answer = service(llm).send("remove the lecture")
        assertEquals(emptyList(), answer.proposalIds)
        assertEquals("Which lecture do you mean?", answer.text)
    }

    @Test
    fun `asking the model to apply gets an error and changes nothing`() = runTest {
        val llm = ScriptedLlm(
            { toolUse("apply_changes", """{"proposal_id":"cs-1"}""") },
            { r ->
                assertTrue(r.messages.last().content.filterIsInstance<ContentBlock.ToolResult>().single().isError)
                reply("I can't apply changes; approve it on the card.")
            },
        )
        val answer = service(llm).send("just apply it")
        assertEquals(emptyList(), port.calls)
        assertEquals(emptyList(), answer.proposalIds)
    }
}
