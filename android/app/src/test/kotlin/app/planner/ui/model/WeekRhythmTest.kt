package app.planner.ui.model

import app.planner.engine.fakes.FakePlannerQueries
import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.Settings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekRhythmTest {
    private val settings = Settings()
    private val queries = FakePlannerQueries(now = { ReferenceWeek.days[1].atTime(10, 0) })

    private fun ui(): WeekRhythmUi = runBlocking {
        val week = queries.week(ReferenceWeek.monday)
        val anchors = ReferenceWeek.days.flatMap { queries.anchors(it) }
        WeekRhythm.build(week, anchors, settings, today = ReferenceWeek.days[1])
    }

    @Test
    fun headerAndNextWeekLabels() {
        val u = ui()
        assertEquals("2 – 8 November", u.rangeLabel)
        assertEquals("9 – 15 Nov", u.nextWeekLabel)
        assertEquals(ReferenceWeek.monday.plusDays(7), u.nextWeekStart)
        assertTrue(u.hasEstimatedTimes)
    }

    @Test
    fun rowsShowDhuhrJumuahEstimatesAndToday() {
        val rows = ui().rows
        assertEquals(listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"), rows.map { it.label })
        assertEquals("11:49", rows[0].sub)
        assertEquals("today", rows[1].sub)
        assertEquals("Jumu’ah", rows[4].sub)
        assertEquals("~11:48", rows[5].sub)
        assertTrue(rows[1].isToday)
    }

    @Test
    fun endTimesAndLateDays() {
        val rows = ui().rows
        assertEquals(listOf("19:15", "19:15", "18:31", "20:00", "17:30", "16:15", "19:00"), rows.map { it.endLabel })
        assertEquals(listOf(true, true, true, true, false, false, true), rows.map { it.late })
        assertEquals(listOf("Extended day"), rows[0].flags)
    }

    @Test
    fun segmentsFillByPlannedTimeAndColourExtendedAndFixed() {
        val rows = ui().rows
        // Monday: 09:30 to Dhuhr fully planned (P&S sheet, then CP2 from 11:30).
        assertEquals(1f, rows[0].segments[0].fraction, 0.001f)
        assertEquals(FillKind.WORK, rows[0].segments[0].kind)
        // Monday after Isha holds the extended ADA lecture.
        assertEquals(FillKind.EXTENDED, rows[0].segments[4].kind)
        // Wednesday 14:02 to 16:31 is almost all Halaqa (fixed).
        assertEquals(FillKind.FIXED, rows[2].segments[2].kind)
        // Saturday after Maghrib: nothing.
        assertEquals(FillKind.EMPTY, rows[5].segments[3].kind)
        rows.forEach { r -> r.segments.forEach { assertTrue(it.fraction in 0f..1f) } }
    }

    @Test
    fun fridayBorrowsDhuhrFromThursdaySoColumnsLineUp() {
        val anchors = runBlocking { ReferenceWeek.days.flatMap { queries.anchors(it) } }
        val b = WeekRhythm.boundaries(ReferenceWeek.days[4], anchors, settings)
        assertEquals(6, b.size)
        assertEquals("11:49", Fmt.time(b[1]))
        assertEquals("09:30", Fmt.time(b[0]))
        assertEquals("22:00", Fmt.time(b[5]))
    }

    @Test
    fun rowDescriptionIsSpoken() {
        assertEquals("Monday, ends 19:15, extended day, 4 blocks", ui().rows[0].description)
    }
}
