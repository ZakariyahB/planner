package app.planner.assistant

import app.planner.assistant.RecordingProposalPort.Call
import app.planner.assistant.tools.AssistantTools
import app.planner.assistant.tools.ToolNames
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.fakes.FakePlannerQueries
import app.planner.engine.fakes.InMemoryTaskStore
import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.EventRef
import app.planner.engine.model.Prayer
import app.planner.engine.model.UnparsedTemplate
import app.planner.engine.ports.ChangeDraft
import app.planner.engine.ports.NowProvider
import app.planner.engine.ports.ReplanReason
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AssistantToolsTest {
    private val port = RecordingProposalPort()
    private val taskStore = InMemoryTaskStore(ReferenceWeek.tasks)
    private val tools = AssistantTools(FakePlannerQueries(now = { MONDAY_MORNING }), port, taskStore, NowProvider { MONDAY_MORNING })

    private suspend fun run(name: String, input: String) = tools.execute(name, json(input))

    private suspend fun error(name: String, input: String): String {
        val out = run(name, input)
        assertTrue(out.isError, "expected an error for $input, got ${out.content}")
        assertEquals(emptyList(), port.calls, "a bad input must not reach the engine")
        return json(out.content)["error"]!!.jsonPrimitive.content
    }

    @Test
    fun `get_week lists the blocks with their ids and times`() = runTest {
        val out = json(run(ToolNames.GET_WEEK, """{"from":"2026-11-03","to":"2026-11-03"}""").content)
        val day = out["days"]!!.jsonArray.single().jsonObject
        assertEquals("2026-11-03", day["date"]!!.jsonPrimitive.content)
        val first = day["blocks"]!!.jsonArray.first().jsonObject
        assertEquals("PintOS", first["title"]!!.jsonPrimitive.content)
        assertEquals("09:30", first["start"]!!.jsonPrimitive.content)
        assertEquals("pintos-tue-1", first["event_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get_week spans two fetched weeks and refuses more than 14 days`() = runTest {
        val out = json(run(ToolNames.GET_WEEK, """{"from":"2026-11-02","to":"2026-11-15"}""").content)
        assertEquals(14, out["days"]!!.jsonArray.size)
        assertTrue("14" in error(ToolNames.GET_WEEK, """{"from":"2026-11-02","to":"2026-11-16"}"""))
    }

    @Test
    fun `get_tasks returns the backlog and the needs-a-look list`() = runTest {
        taskStore.setNeedsALook(listOf(UnparsedTemplate(EventRef("c", "e"), "weird title", "Academic ", LocalDate.of(2026, 11, 2))))
        val out = json(run(ToolNames.GET_TASKS, "{}").content)
        val arabic = out["tasks"]!!.jsonArray.map { it.jsonObject }.first { it["task_id"]!!.jsonPrimitive.content == "arabic-hw-tue" }
        assertEquals("Asr", arabic["after_prayer"]!!.jsonPrimitive.content)
        assertEquals("Academic", out["needs_a_look"]!!.jsonArray.single().jsonObject["calendar"]!!.jsonPrimitive.content)
    }

    @Test
    fun `get_prayer_anchors gives begin times and the slot rule`() = runTest {
        val out = json(run(ToolNames.GET_PRAYER_ANCHORS, """{"date":"2026-11-07"}""").content)
        val anchors = out["anchors"]!!.jsonArray.map { it.jsonObject }
        assertEquals("Dhuhr", anchors.first()["prayer"]!!.jsonPrimitive.content)
        assertTrue(anchors.all { it["interpolated"]?.jsonPrimitive?.content == "true" }, "Saturday is interpolated")
    }

    @Test
    fun `find_events returns candidates with exact ids and changes nothing`() = runTest {
        val out = json(run(ToolNames.FIND_EVENTS, """{"query":"pintos"}""").content)
        assertEquals(5, out["candidates"]!!.jsonArray.size)
        assertEquals(emptyList(), port.calls)
    }

    @Test
    fun `every add field reaches the engine as intent`() = runTest {
        run(
            ToolNames.PROPOSE_CHANGES,
            """{"summary":"s","changes":[{"action":"add","title":"Arabic hw","category":"quran and arabic","duration_min":60,
               "day":"2026-11-04","earliest_start":"15:00","after_prayer":"asr","deadline":"2026-11-06","splittable":false}]}""",
        )
        val expected = ChangeDraft.AddTask(
            "Arabic hw", CalendarCategory.QURAN_ARABIC, 60, LocalDate.of(2026, 11, 4), LocalTime.of(15, 0), Prayer.ASR,
            LocalDate.of(2026, 11, 6), splittable = false,
        )
        assertEquals(listOf<Call>(Call.Propose(listOf(expected), ChangeSource.ASSISTANT, "s")), port.calls)
    }

    @Test
    fun `schedule, edit and remove map to their drafts with scope`() = runTest {
        run(
            ToolNames.PROPOSE_CHANGES,
            """{"summary":"s","changes":[
              {"action":"schedule_task","task_id":"sde-thu","day":"2026-11-05"},
              {"action":"edit","calendar_id":"c","event_id":"e","title":"New","duration_min":90,"category":"Academic","scope":"whole_series"},
              {"action":"remove","calendar_id":"c","event_id":"f","scope":"this_and_following"}]}""",
        )
        val drafts = (port.calls.single() as Call.Propose).drafts
        assertEquals(
            listOf(
                ChangeDraft.ScheduleTask("sde-thu", LocalDate.of(2026, 11, 5)),
                ChangeDraft.EditEvent(EventRef("c", "e"), "New", 90, CalendarCategory.ACADEMIC, RecurrenceScope.WHOLE_SERIES),
                ChangeDraft.RemoveEvent(EventRef("c", "f"), RecurrenceScope.THIS_AND_FOLLOWING),
            ),
            drafts,
        )
    }

    @Test
    fun `one bad change stops the whole proposal`() = runTest {
        val msg = error(
            ToolNames.PROPOSE_CHANGES,
            """{"summary":"s","changes":[{"action":"add","title":"ok","duration_min":60},{"action":"move","calendar_id":"c","event_id":"e"}]}""",
        )
        assertTrue("to_day" in msg)
    }

    @Test
    fun `inputs are checked strictly`() = runTest {
        assertTrue("action" in error(ToolNames.PROPOSE_CHANGES, """{"summary":"s","changes":[{"action":"apply"}]}"""))
        assertTrue("date" in error(ToolNames.PROPOSE_CHANGES, """{"summary":"s","changes":[{"action":"add","title":"x","duration_min":60,"day":"Sunday"}]}"""))
        assertTrue("whole number" in error(ToolNames.PROPOSE_CHANGES, """{"summary":"s","changes":[{"action":"add","title":"x","duration_min":"2h"}]}"""))
        assertTrue("find_events" in error(ToolNames.PROPOSE_CHANGES, """{"summary":"s","changes":[{"action":"remove"}]}"""))
        assertTrue("together" in error(ToolNames.PROPOSE_CHANGES, """{"summary":"s","changes":[{"action":"remove","event_id":"e"}]}"""))
        assertTrue("Dhuhr, Asr, Maghrib, Isha" in error(ToolNames.PROPOSE_CHANGES, """{"summary":"s","changes":[{"action":"add","title":"x","duration_min":60,"after_prayer":"Fajr"}]}"""))
        assertTrue("category" in error(ToolNames.PROPOSE_CHANGES, """{"summary":"s","changes":[{"action":"add","title":"x","duration_min":60,"category":"Work"}]}"""))
        assertTrue("summary" in error(ToolNames.PROPOSE_CHANGES, """{"changes":[]}"""))
        assertTrue("at least one" in error(ToolNames.PROPOSE_CHANGES, """{"summary":"s","changes":[]}"""))
        assertTrue("edit needs" in error(ToolNames.PROPOSE_CHANGES, """{"summary":"s","changes":[{"action":"edit","calendar_id":"c","event_id":"e"}]}"""))
        assertTrue("Monday" in error(ToolNames.REPLAN, """{"reason":"rollover","target_week_start":"2026-11-10"}"""))
        assertTrue("extra_min" in error(ToolNames.REPLAN, """{"reason":"overrun","calendar_id":"c","event_id":"e"}"""))
        assertTrue("reason" in error(ToolNames.REPLAN, """{"reason":"apply"}"""))
    }

    @Test
    fun `replan reasons map to the right port calls`() = runTest {
        run(ToolNames.REPLAN, """{"reason":"stopped_early","calendar_id":"c","event_id":"e","at":"15:30"}""")
        run(ToolNames.REPLAN, """{"reason":"plan_days","days":["2026-11-08","2026-11-07","2026-11-08"]}""")
        run(ToolNames.REPLAN, """{"reason":"other","text":"I'm ill today"}""")
        assertEquals(
            listOf(
                Call.Replan(ReplanReason.StoppedEarly(EventRef("c", "e"), MONDAY_MORNING.toLocalDate().atTime(15, 30)), ChangeSource.ASSISTANT),
                Call.PlanDays(listOf(LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 8)), ChangeSource.ASSISTANT),
                Call.Replan(ReplanReason.Free("I'm ill today"), ChangeSource.ASSISTANT),
            ),
            port.calls,
        )
    }

    @Test
    fun `a proposal result reports the engine's times, flags and id, and says nothing was written`() = runTest {
        val out = run(ToolNames.PROPOSE_CHANGES, """{"summary":"s","changes":[{"action":"add","title":"x","duration_min":60}]}""")
        assertFalse(out.isError)
        assertEquals("cs-1", out.proposalId)
        val body = json(out.content)
        assertEquals("proposed", body["status"]!!.jsonPrimitive.content)
        val after = body["changes"]!!.jsonArray.single().jsonObject["after"]!!.jsonObject
        assertEquals("14:00", after["start"]!!.jsonPrimitive.content)
        assertTrue("Nothing has been written" in body["next"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool names are matched without regard to case`() = runTest {
        assertFalse(run("Get_Tasks", "{}").isError)
    }
}
