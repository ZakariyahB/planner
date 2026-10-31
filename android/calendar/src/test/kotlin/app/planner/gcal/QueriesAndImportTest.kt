package app.planner.gcal

import app.planner.engine.changeset.ChangeSource
import app.planner.engine.changeset.EventTime
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.ExecutionLog
import app.planner.engine.model.Prayer
import app.planner.engine.model.TaskKind
import app.planner.engine.model.Tier
import app.planner.engine.ports.AuthState
import app.planner.engine.schedule.DayFlag
import app.planner.gcal.Fixture.at
import app.planner.gcal.Fixture.cal
import app.planner.gcal.Fixture.day
import app.planner.gcal.plan.TemplateImporter
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class QueriesAndImportTest {
    @Test
    fun `template import reads every reference task with its calendar and constraints`() = runTest {
        val w = TestWorld()
        w.sync.refresh()
        val report = w.service.importTemplate(Fixture.monday, Fixture.monday.plusDays(7))
        assertEquals(23, report.imported)
        assertEquals(0, report.needsALook)
        val tasks = w.tasks.all().associateBy { it.sourceEvent!!.eventId }
        val lec = tasks.getValue("allday-lec-ps-mon")
        assertEquals("Lecture - P&S", lec.title)
        assertEquals(CalendarCategory.LECTURES, lec.category)
        assertEquals(60, lec.durationMin)
        assertEquals(LocalTime.of(14, 0), lec.earliestStart)
        assertEquals(TaskKind.PASSIVE, lec.kind)
        assertEquals(day(0), lec.assignedDay)
        assertEquals(Prayer.ASR, tasks.getValue("allday-arabic-hw-tue").afterPrayer)
        assertEquals(CalendarCategory.ACADEMIC, tasks.getValue("allday-pintos-tue").category) // "Academic " matched trimmed
    }

    @Test
    fun `re-import is idempotent and picks up edits`() = runTest {
        val w = TestWorld()
        w.sync.refresh()
        w.service.importTemplate(Fixture.monday, Fixture.monday.plusDays(7))
        assertEquals(0, w.service.importTemplate(Fixture.monday, Fixture.monday.plusDays(7)).let { it.imported + it.updated })
        val ref = app.planner.engine.model.EventRef(cal(CalendarCategory.ACADEMIC), "allday-pintos-tue")
        (w.gateway as IncrementalFakeGateway).userEdit(ref) { it.copy(title = "1. PintOS (4h)") }
        w.sync.refresh()
        val again = w.service.importTemplate(Fixture.monday, Fixture.monday.plusDays(7))
        assertEquals(0, again.imported)
        assertEquals(1, again.updated)
        assertEquals(240, w.tasks.get(TemplateImporter.idFor(ref.calendarId, ref.eventId))!!.durationMin)
    }

    @Test
    fun `unreadable numbered titles go to needs a look, others are ignored`() = runTest {
        val odd = Fixture.snapshot(cal(CalendarCategory.ACADEMIC), "odd", "9. Revise everything (soon)", EventTime.AllDay(day(2), day(3)))
        val holiday = Fixture.snapshot(cal(CalendarCategory.PERSONAL), "hol", "Mum's birthday", EventTime.AllDay(day(2), day(3)))
        val w = TestWorld(events = Fixture.allEvents() + odd + holiday)
        w.sync.refresh()
        val report = w.service.importTemplate(Fixture.monday, Fixture.monday.plusDays(7))
        assertEquals(1, report.needsALook)
        val look = w.tasks.observeNeedsALook().first().single()
        assertEquals("9. Revise everything (soon)", look.title)
        assertEquals("Academic", look.calendarName)
        assertTrue(w.tasks.all().none { it.sourceEvent?.eventId == "hol" })
    }

    @Test
    fun `today lists blocks with tiers, buffers, the current block and unscheduled tasks`() = runTest {
        val w = TestWorld().start()
        applyAll(w, w.service.planDays(listOf(day(0)), ChangeSource.USER))
        w.now = at(0, "11:40")
        val sheet = w.eventsOn(day(0)).single { it.title == "Lecture - CP2" }
        w.logs.upsert(ExecutionLog("l1", sheet.taskId, sheet.ref, sheet.title, at(0, "11:30"), at(0, "12:45"), actualStart = at(0, "11:32")))
        val today = w.service.observeToday().first()
        assertEquals(day(0), today.date)
        assertEquals(listOf("P&S problem sheet", "Lecture - CP2", "Lecture - P&S", "Lecture - ADA"), today.blocks.map { it.title })
        val cp2 = today.blocks[1]
        assertEquals(1, today.nowIndex)
        assertEquals(15, cp2.prayerBufferMin)
        assertEquals(listOf(Prayer.DHUHR), cp2.coveredPrayers)
        assertNotNull(cp2.execution)
        assertEquals(Tier.EXTENDED, today.blocks[3].tier)
        assertTrue(DayFlag.EXTENDED_DAY in today.flags)
        assertTrue(today.unscheduled.isEmpty())
        assertEquals(4, today.anchors.size)
        assertEquals(AuthState.SIGNED_IN, today.auth)
        assertTrue(today.lastSync!!.ok)
    }

    @Test
    fun `week shows fixed events, end times, flags and what is not planned yet`() = runTest {
        val w = TestWorld().start()
        val week = w.service.week(Fixture.monday)
        assertEquals(7, week.days.size)
        val wed = week.days[2]
        assertTrue(wed.blocks.single { it.title == "BoL" }.isFixed)
        assertEquals(LocalTime.of(12, 45), wed.endTime)
        assertEquals(2, wed.overflowCount) // P&S CW and OS worksheet not planned yet
        assertTrue(DayFlag.INTERPOLATED_PRAYERS in week.days[5].flags)
        assertTrue(DayFlag.INTERPOLATED_PRAYERS !in week.days[0].flags)
    }

    @Test
    fun `anchors come from the prayer calendar with weekends interpolated`() = runTest {
        val w = TestWorld().start()
        val mon = w.service.anchors(day(0))
        assertEquals(LocalTime.of(11, 49), mon.first { it.prayer == Prayer.DHUHR }.begin)
        assertTrue(mon.none { it.prayer == Prayer.FAJR })
        assertNull(w.service.anchors(day(4)).firstOrNull { it.prayer == Prayer.DHUHR })
        assertTrue(w.service.anchors(day(6)).all { it.interpolated })
    }

    @Test
    fun `find events matches every word and never returns prayer entries`() = runTest {
        val w = TestWorld().start()
        assertEquals(listOf("Quran class (Sh Anass)"), w.service.findEvents("quran class", day(0), day(6)).map { it.title })
        assertTrue(w.service.findEvents("maghrib", day(0), day(6)).isEmpty())
        assertTrue(w.service.findEvents("lecture", day(0), day(1)).isNotEmpty()) // all-day template entries too
    }
}
