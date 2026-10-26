package app.planner.calendar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class CalendarLayoutTest {
    @Test fun `overlapping events occupy different lanes`() {
        val lanes = CalendarLayout.lanes(listOf(CalendarSpan("lecture", 600, 690), CalendarSpan("meeting", 630, 660)))
        assertEquals(2, lanes.getValue("lecture").count)
        assertNotEquals(lanes.getValue("lecture").lane, lanes.getValue("meeting").lane)
    }

    @Test fun `adjacent events use the full width`() {
        val lanes = CalendarLayout.lanes(listOf(CalendarSpan("a", 600, 660), CalendarSpan("b", 660, 720)))
        assertEquals(CalendarLane(0, 1), lanes["a"])
        assertEquals(CalendarLane(0, 1), lanes["b"])
    }

    @Test fun `chain of overlaps keeps a consistent group width and reuses a free lane`() {
        val lanes = CalendarLayout.lanes(listOf(CalendarSpan("a", 480, 660), CalendarSpan("b", 540, 600), CalendarSpan("c", 600, 720), CalendarSpan("d", 780, 840)))
        assertEquals(2, lanes.getValue("c").count)
        assertEquals(lanes.getValue("b").lane, lanes.getValue("c").lane)
        assertEquals(CalendarLane(0, 1), lanes["d"])
    }
}
