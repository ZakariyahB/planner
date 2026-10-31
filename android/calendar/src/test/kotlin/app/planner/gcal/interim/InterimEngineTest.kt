package app.planner.gcal.interim

import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.EventRef
import app.planner.engine.model.FixedEvent
import app.planner.engine.model.Prayer
import app.planner.engine.model.Settings
import app.planner.engine.model.Task
import app.planner.engine.model.CalendarCategory
import app.planner.engine.schedule.BlockFit
import app.planner.engine.schedule.PlanRequest
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The interim engine must at least get newplan 13.1's placeBlock cases right, since the :calendar tests rely on it. */
class InterimEngineTest {
    private val placer = InterimBlockPlacer()
    private fun at(day: Int, t: String) = ReferenceWeek.days[day].atTime(LocalTime.parse(t))

    private fun place(day: Int, start: String, minutes: Int, fixed: List<FixedEvent> = emptyList()) =
        placer.place(at(day, start), minutes, ReferenceWeek.anchorsOn(ReferenceWeek.days[day]), fixed, 15)

    private fun check(day: Int, start: String, minutes: Int, end: String, buffer: Int, actualStart: String = start) {
        val fit = assertIs<BlockFit.Fits>(place(day, start, minutes))
        assertEquals(at(day, actualStart), fit.start, "start")
        assertEquals(at(day, end), fit.end, "end")
        assertEquals(buffer, fit.bufferMin, "buffer")
    }

    @Test fun `golden placeBlock cases`() {
        check(0, "09:30", 120, "11:30", 0)
        check(0, "11:30", 60, "12:45", 15)
        check(0, "14:00", 60, "15:15", 15)
        check(0, "17:00", 120, "19:15", 15)
        check(1, "09:30", 180, "12:45", 15)
        check(3, "15:00", 120, "17:15", 15)
        check(4, "15:15", 120, "17:30", 15)
        check(5, "11:30", 120, "13:45", 15)
        check(6, "13:15", 60, "14:30", 15)
        check(6, "14:30", 120, "16:45", 15)
        check(6, "16:45", 120, "19:00", 15)
    }

    @Test fun `edge cases`() {
        check(4, "14:00", 60, "15:15", 0, actualStart = "14:15") // start inside Asr slot
        check(5, "11:30", 135, "14:15", 30) // cascade
        check(0, "09:30", 139, "11:49", 0) // anchor exactly at end
        val halaqa = FixedEvent(EventRef("c", "h"), "Halaqa", at(2, "14:00"), at(2, "16:30"))
        assertIs<BlockFit.Collides>(place(2, "13:00", 120, listOf(halaqa)))
    }

    @Test fun `prayer model interpolates weekends and drops Friday Dhuhr`() {
        val resolved = InterimPrayerModel().resolve(ReferenceWeek.days, ReferenceWeek.importedAnchors, Settings())
        assertTrue(resolved.none { it.date.dayOfWeek == DayOfWeek.FRIDAY && it.prayer == Prayer.DHUHR })
        val sat = resolved.filter { it.date == ReferenceWeek.days[5] }
        assertEquals(4, sat.size)
        assertTrue(sat.all { it.interpolated })
        assertTrue(resolved.filter { it.date.dayOfWeek.value <= 5 }.none { it.interpolated })
    }

    @Test fun `offsets move the anchor`() {
        val resolved = InterimPrayerModel().resolve(listOf(ReferenceWeek.days[0]), ReferenceWeek.importedAnchors, Settings(prayerOffsetMin = mapOf(Prayer.DHUHR to 20)))
        assertEquals(LocalTime.of(12, 9), resolved.first { it.prayer == Prayer.DHUHR }.begin)
    }

    @Test fun `plans around fixed events and respects earliest start`() {
        val mon = ReferenceWeek.days[0]
        val tasks = listOf(
            Task("a", "Lecture - P&S", CalendarCategory.LECTURES, 60, assignedDay = mon, earliestStart = LocalTime.of(14, 0)),
            Task("b", "P&S problem sheet", CalendarCategory.ACADEMIC, 120, assignedDay = mon),
        )
        val result = InterimPlanningEngine().plan(PlanRequest(listOf(mon), tasks, emptyList(), ReferenceWeek.anchorsOn(mon), Settings()))
        val byId = result.placements.associateBy { it.taskId }
        assertEquals(at(0, "14:00"), byId.getValue("a").start)
        assertEquals(at(0, "15:15"), byId.getValue("a").end)
        assertEquals(at(0, "09:30"), byId.getValue("b").start)
    }
}
