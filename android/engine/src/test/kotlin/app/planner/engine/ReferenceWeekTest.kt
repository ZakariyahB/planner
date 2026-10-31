package app.planner.engine

import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.Prayer
import java.time.DayOfWeek
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferenceWeekTest {
    @Test
    fun `week starts on Monday 2 November`() {
        assertEquals(DayOfWeek.MONDAY, ReferenceWeek.monday.dayOfWeek)
    }

    @Test
    fun `no Friday Dhuhr and weekends are interpolated`() {
        val friday = ReferenceWeek.days[4]
        assertNull(ReferenceWeek.anchors.firstOrNull { it.date == friday && it.prayer == Prayer.DHUHR })
        assertTrue(ReferenceWeek.anchors.filter { it.date.dayOfWeek.value >= 6 }.all { it.interpolated })
        assertTrue(ReferenceWeek.importedAnchors.none { it.date.dayOfWeek.value >= 6 })
    }

    @Test
    fun `PintOS totals 8 hours`() {
        assertEquals(480, ReferenceWeek.tasks.filter { it.title == "PintOS" }.sumOf { it.durationMin })
    }

    @Test
    fun `reference placements are duration plus prayer buffer`() {
        val byId = ReferenceWeek.tasks.associateBy { it.id }
        ReferenceWeek.referencePlacements.groupBy { it.taskId }.forEach { (id, chunks) ->
            val worked = chunks.sumOf { Duration.between(it.start, it.end).toMinutes().toInt() - it.prayerBufferMin }
            assertEquals(byId.getValue(id).durationMin, worked, id)
        }
    }

    @Test
    fun `category calendars match by trimmed case-insensitive name`() {
        assertEquals(CalendarCategory.ACADEMIC, CalendarCategory.forCalendarName("Academic "))
        assertEquals(CalendarCategory.QURAN_ARABIC, CalendarCategory.forCalendarName("quran and arabic"))
        assertNull(CalendarCategory.forCalendarName("Prayer times"))
    }
}
