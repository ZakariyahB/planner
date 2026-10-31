package app.planner.gcal

import app.planner.engine.changeset.ChangeFlag
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.changeset.ChangeType
import app.planner.engine.changeset.EventTime
import app.planner.engine.changeset.PlannerTags
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.EventRef
import app.planner.engine.model.Prayer
import app.planner.engine.ports.ChangeDraft
import app.planner.engine.ports.ProposalOutcome
import app.planner.engine.ports.ReplanReason
import app.planner.gcal.Fixture.at
import app.planner.gcal.Fixture.cal
import app.planner.gcal.Fixture.day
import app.planner.gcal.plan.TemplateImporter
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ProposalsTest {
    private fun proposed(o: ProposalOutcome) = assertIs<ProposalOutcome.Proposed>(o, "got $o").changeSet

    @Test
    fun `proposing writes nothing and saves the proposal for the approval card`() = runTest {
        val w = TestWorld().start()
        val writesBefore = w.gateway.writeLog.size
        val cs = proposed(w.service.propose(listOf(ChangeDraft.AddTask("Internship apps", durationMin = 120, day = day(6))), ChangeSource.ASSISTANT, "Add internship apps"))
        assertEquals(writesBefore, w.gateway.writeLog.size)
        assertEquals(listOf(cs.id), w.changeSets.observePending().first().map { it.id })
        val add = cs.changes.single()
        assertEquals(ChangeType.ADD, add.type)
        assertEquals(cal(CalendarCategory.PERSONAL), add.after!!.calendarId) // routed by the category rules
        assertTrue(EventIds.isValid(add.newEventId!!))
        assertEquals("1", add.after!!.privateProps[PlannerTags.PLANNER])
        assertEquals("1/1", add.after!!.privateProps[PlannerTags.CHUNK])
        assertNotNull(w.tasks.get(add.taskId!!)) // in the backlog already
    }

    @Test
    fun `scheduling a template task edits its all-day event into the first timed block`() = runTest {
        val w = TestWorld().start()
        val taskId = TemplateImporter.idFor(cal(CalendarCategory.LECTURES), "allday-lec-ps-mon")
        val cs = proposed(w.service.propose(listOf(ChangeDraft.ScheduleTask(taskId)), ChangeSource.USER, "Plan Lecture - P&S"))
        val edit = cs.changes.single()
        assertEquals(ChangeType.EDIT, edit.type)
        assertEquals(EventRef(cal(CalendarCategory.LECTURES), "allday-lec-ps-mon"), edit.target!!.ref)
        assertEquals(cal(CalendarCategory.LECTURES), edit.after!!.calendarId)
        assertEquals(EventTime.Timed(at(0, "14:00"), at(0, "15:15")), edit.after!!.time)
        assertEquals("Lecture - P&S", edit.after!!.title)
        assertEquals("Task: 1h. Recording available from 14:00. Includes 15 min for Asr.", edit.after!!.description)
        assertTrue(ChangeFlag.CONVERTS_ALL_DAY in edit.flags)
        assertEquals("Includes 15 min for Asr", edit.note)
    }

    @Test
    fun `planning a day proposes every template task and flags the extended block`() = runTest {
        val w = TestWorld().start()
        val cs = proposed(w.service.planDays(listOf(day(0)), ChangeSource.USER))
        assertEquals(4, cs.changes.size)
        assertTrue(cs.changes.all { it.type == ChangeType.EDIT && ChangeFlag.CONVERTS_ALL_DAY in it.flags })
        val ada = cs.changes.single { it.after!!.title == "Lecture - ADA" }
        assertEquals(EventTime.Timed(at(0, "17:00"), at(0, "19:15")), ada.after!!.time)
        assertTrue(ChangeFlag.EXTENDED_DAY in ada.flags)
        assertTrue(ada.after!!.description!!.contains("Extended hours (after 18:00)."))
        assertTrue(ada.after!!.description!!.contains("Includes 15 min for Isha."))
    }

    @Test
    fun `weekend blocks say the prayer time was interpolated`() = runTest {
        val w = TestWorld().start()
        val cs = proposed(w.service.planDays(listOf(day(5)), ChangeSource.USER))
        val covering = cs.changes.filter { it.after!!.description!!.contains("Includes") }
        assertTrue(covering.isNotEmpty())
        assertTrue(covering.all { ChangeFlag.INTERPOLATED_PRAYER in it.flags && it.after!!.description!!.endsWith("(Sat prayer time interpolated)") })
    }

    @Test
    fun `remove asks which when the title matches more than one event`() = runTest {
        val extra = Fixture.snapshot(cal(CalendarCategory.PERSONAL), "quran-class-next", "Quran class (Sh Anass)", EventTime.Timed(at(3, "18:00").plusDays(7), at(3, "18:30").plusDays(7)))
        val w = TestWorld(events = Fixture.allEvents() + extra).start()
        val o = w.service.propose(listOf(ChangeDraft.RemoveEvent(EventRef("", "Quran class"))), ChangeSource.ASSISTANT, "Remove Quran class")
        val choice = assertIs<ProposalOutcome.NeedsChoice>(o)
        assertEquals(2, choice.candidates.size)
        assertTrue(w.changeSets.observePending().first().isEmpty())
    }

    @Test
    fun `remove resolves the exact event and shows it as the target`() = runTest {
        val w = TestWorld().start()
        val cs = proposed(w.service.propose(listOf(ChangeDraft.RemoveEvent(EventRef("", "Quran class"))), ChangeSource.ASSISTANT, "Remove Quran class"))
        val c = cs.changes.single()
        assertEquals(ChangeType.REMOVE, c.type)
        assertEquals(EventRef(cal(CalendarCategory.PERSONAL), "quran-class-thu"), c.target!!.ref)
        assertTrue(ChangeFlag.TOUCHES_FIXED_EVENT in c.flags)
        assertEquals(RecurrenceScope.THIS_INSTANCE, c.scope)
    }

    @Test
    fun `prayer times can never be targeted`() = runTest {
        val w = TestWorld().start()
        val ref = EventRef(Fixture.PRAYER, "prayer-asr-${day(0)}")
        val o = w.service.propose(listOf(ChangeDraft.RemoveEvent(ref)), ChangeSource.ASSISTANT, "x")
        assertIs<ProposalOutcome.Rejected>(o)
        assertTrue(w.changeSets.observePending().first().isEmpty())
    }

    @Test
    fun `calendars the user has not allowed are read-only`() = runTest {
        val settings = app.planner.engine.model.Settings(writableCategories = CalendarCategory.entries.toSet() - CalendarCategory.PERSONAL)
        val w = TestWorld(settings = settings).start()
        val o = w.service.propose(listOf(ChangeDraft.RemoveEvent(EventRef(cal(CalendarCategory.PERSONAL), "bol-wed"))), ChangeSource.USER, "x")
        assertIs<ProposalOutcome.Rejected>(o)
    }

    @Test
    fun `external organiser and recurring flags, default scope this instance`() = runTest {
        val lesson = Fixture.snapshot(cal(CalendarCategory.QURAN_ARABIC), "arabic-lesson-sun", "Arabic lesson", EventTime.Timed(at(6, "12:15"), at(6, "13:15")), organiserIsExternal = true, recurring = "arabic-series")
        val w = TestWorld(events = Fixture.allEvents().filter { it.ref.eventId != "arabic-lesson-sun" } + lesson).start()
        val cs = proposed(w.service.propose(listOf(ChangeDraft.EditEvent(lesson.ref, title = "Arabic lesson (online)")), ChangeSource.USER, "Rename"))
        val c = cs.changes.single()
        assertTrue(ChangeFlag.EXTERNAL_ORGANISER in c.flags)
        assertTrue(ChangeFlag.RECURRING in c.flags)
        assertEquals(RecurrenceScope.THIS_INSTANCE, c.scope)
    }

    @Test
    fun `moving two lectures to the same day does not stack them`() = runTest {
        val w = TestWorld().start()
        applyAll(w, w.service.planDays(listOf(day(1)), ChangeSource.USER))
        val lectures = w.service.findEvents("Lecture", day(1), day(2))
        assertEquals(2, lectures.size)
        val cs = proposed(w.service.propose(lectures.map { ChangeDraft.MoveEvent(it.ref, day(3)) }, ChangeSource.ASSISTANT, "Push Tuesday's lectures to Thursday"))
        val times = cs.changes.map { it.after!!.time as EventTime.Timed }
        assertEquals(2, times.size)
        assertTrue(times.all { it.start.toLocalDate() == day(3) })
        assertTrue(!(times[0].start < times[1].end && times[1].start < times[0].end), "overlap: $times")
        // Lecture - SDE keeps its "from 14:00" and lands after BoL-free Thursday morning work is respected
        assertTrue(times.all { !it.start.toLocalTime().isBefore(LocalTime.of(9, 30)) })
    }

    @Test
    fun `halaqa ran over pushes the blocks after it`() = runTest {
        val halaqa = Fixture.snapshot(cal(CalendarCategory.PERSONAL), "halaqa-wed", "Halaqa", EventTime.Timed(at(2, "14:00"), at(2, "16:30")))
        val w = TestWorld(events = Fixture.allEvents() + halaqa).start()
        applyAll(w, w.service.planDays(listOf(day(2)), ChangeSource.USER))
        val before = w.service.week(Fixture.monday).days[2].blocks.filter { !it.isFixed }
        val after16 = before.filter { !it.start.isBefore(at(2, "16:30")) }
        assertTrue(after16.isNotEmpty(), "blocks: $before")
        w.now = at(2, "16:40")
        val cs = proposed(w.service.replan(ReplanReason.Overrun(halaqa.ref, 60), ChangeSource.ASSISTANT))
        assertTrue(cs.changes.all { it.type == ChangeType.MOVE })
        assertTrue(cs.changes.all { (it.after!!.time as EventTime.Timed).start >= at(2, "17:30") }, "${cs.changes.map { it.after!!.time }}")
        assertNull(cs.changes.firstOrNull { it.target!!.ref == halaqa.ref }) // the halaqa itself is not edited
    }

    @Test
    fun `need 30 more extends the block`() = runTest {
        val w = TestWorld().start()
        applyAll(w, w.service.planDays(listOf(day(0)), ChangeSource.USER))
        val sheet = w.service.findEvents("problem sheet", day(0), day(1)).single()
        w.now = at(0, "11:25")
        val cs = proposed(w.service.replan(ReplanReason.Overrun(sheet.ref, 30), ChangeSource.NOTIFICATION))
        val edit = cs.changes.first { it.target!!.ref == sheet.ref }
        assertEquals(ChangeType.EDIT, edit.type)
        // 09:30 + 2h30 covers Dhuhr at 11:49: +15
        assertEquals(EventTime.Timed(at(0, "09:30"), at(0, "12:15")), edit.after!!.time)
        // Lecture - CP2 at 11:30 must move out of the way
        assertTrue(cs.changes.any { it.target!!.title == "Lecture - CP2" && it.type == ChangeType.MOVE })
    }

    @Test
    fun `stop here shortens the block and proposes the rest for the catch-up day`() = runTest {
        val w = TestWorld().start()
        applyAll(w, w.service.planDays(listOf(day(1)), ChangeSource.USER))
        val pintos = w.service.findEvents("PintOS", day(1), day(2)).single()
        w.now = at(1, "10:30")
        val cs = proposed(w.service.replan(ReplanReason.StoppedEarly(pintos.ref, at(1, "10:30")), ChangeSource.NOTIFICATION))
        val edit = cs.changes.first { it.type == ChangeType.EDIT }
        assertEquals(EventTime.Timed(at(1, "09:30"), at(1, "10:30")), edit.after!!.time)
        val add = cs.changes.first { it.type == ChangeType.ADD }
        assertEquals("PintOS (rest)", add.after!!.title)
        assertEquals(day(6), (add.after!!.time as EventTime.Timed).start.toLocalDate())
        assertTrue(add.after!!.description!!.startsWith("Task: 2h."))
    }

    @Test
    fun `adding a task that does not fit says so and proposes nothing`() = runTest {
        val w = TestWorld().start()
        val o = w.service.propose(listOf(ChangeDraft.AddTask("Huge essay", durationMin = 720, day = day(2))), ChangeSource.USER, "x")
        val nothing = assertIs<ProposalOutcome.NothingToDo>(o)
        assertTrue(nothing.reason.contains("backlog"))
    }

    @Test
    fun `nonsense durations are rejected`() = runTest {
        val w = TestWorld().start()
        assertIs<ProposalOutcome.Rejected>(w.service.propose(listOf(ChangeDraft.AddTask("x", durationMin = 0)), ChangeSource.USER, "x"))
    }

    @Test
    fun `after prayer constraint is kept`() = runTest {
        val w = TestWorld().start()
        val cs = proposed(w.service.propose(listOf(ChangeDraft.AddTask("Arabic revision", durationMin = 60, day = day(0), afterPrayer = Prayer.ASR)), ChangeSource.USER, "x"))
        val t = cs.changes.single().after!!.time as EventTime.Timed
        assertTrue(!t.start.isBefore(at(0, "14:20")), "$t")
        assertEquals(cal(CalendarCategory.QURAN_ARABIC), cs.changes.single().after!!.calendarId)
        assertTrue(cs.changes.single().after!!.description!!.contains("After Asr."))
    }

    @Test
    fun `rollover copies the template into next week with the gaps it assumed`() = runTest {
        val halaqa = Fixture.snapshot(cal(CalendarCategory.PERSONAL), "halaqa-wed", "Halaqa", EventTime.Timed(at(2, "14:00"), at(2, "16:30")), recurring = "halaqa-series")
        val w = TestWorld(events = Fixture.allEvents() + halaqa).start()
        val next = Fixture.monday.plusDays(7)
        val cs = proposed(w.service.rollover(next, ChangeSource.USER))
        assertTrue(cs.changes.isNotEmpty())
        assertTrue(cs.changes.all { it.type == ChangeType.ADD && (it.after!!.time as EventTime.Timed).start.toLocalDate() >= next })
        assertTrue(cs.notes.any { it.contains("Halaqa") }, "${cs.notes}")
        val wedNext = next.plusDays(2)
        val onWed = cs.changes.map { it.after!!.time as EventTime.Timed }.filter { it.start.toLocalDate() == wedNext }
        assertTrue(onWed.none { it.start < wedNext.atTime(16, 30) && wedNext.atTime(14, 0) < it.end }, "halaqa gap kept: $onWed")
    }
}

/** Approve everything in a proposal and apply it. */
suspend fun applyAll(w: TestWorld, outcome: ProposalOutcome) {
    val cs = assertIs<ProposalOutcome.Proposed>(outcome, "got $outcome").changeSet
    val record = w.writer.apply(ApprovalGate.approveAll(cs))
    check(record.allVerified) { "not verified: ${record.results}" }
}
