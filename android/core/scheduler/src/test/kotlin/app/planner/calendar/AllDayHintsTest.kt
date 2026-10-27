package app.planner.calendar

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AllDayHintsTest {
    private fun parse(title: String) = AllDayHintParser.parse(title)

    @Test
    fun `a length in brackets becomes the estimate and leaves the name`() {
        assertEquals(AllDayHints("PintOS", 180, null), parse("PintOS (3h)"))
        assertEquals(AllDayHints("OS worksheet", 90, null), parse("OS worksheet (90m)"))
        assertEquals(AllDayHints("Arabic", 90, null), parse("Arabic (1h30m)"))
        assertEquals(AllDayHints("Arabic", 90, null), parse("Arabic (1.5h)"))
        assertEquals(AllDayHints("Reading", 45, null), parse("Reading (45 min)"))
    }

    @Test
    fun `a start time is read with or without from`() {
        assertEquals(AllDayHints("Lecture - SDE", 60, LocalTime.of(14, 0)), parse("Lecture - SDE (1h, from 14:00)"))
        assertEquals(AllDayHints("Halaqa", null, LocalTime.of(19, 30)), parse("Halaqa (at 19:30)"))
        assertEquals(AllDayHints("Society", null, LocalTime.of(9, 0)), parse("Society (9:00)"))
    }

    @Test
    fun `a time range gives both the start and the length`() {
        assertEquals(AllDayHints("Workshop", 90, LocalTime.of(10, 0)), parse("Workshop (10:00-11:30)"))
    }

    @Test
    fun `a stated length wins over a range`() {
        assertEquals(AllDayHints("Workshop", 45, LocalTime.of(10, 0)), parse("Workshop (45m, 10:00-11:30)"))
    }

    @Test
    fun `brackets that say something else stay in the title`() {
        assertEquals(AllDayHints("Submit coursework (optional)", null, null), parse("Submit coursework (optional)"))
        assertEquals(AllDayHints("Tutorial (Room 3)", null, null), parse("Tutorial (Room 3)"))
    }

    @Test
    fun `brackets that are only partly understood are left alone`() {
        assertEquals(AllDayHints("PintOS (3h, urgent)", null, null), parse("PintOS (3h, urgent)"))
    }

    @Test
    fun `a title with nothing to read is unchanged`() {
        assertEquals(AllDayHints("Eid", null, null), parse("Eid"))
    }

    @Test
    fun `an impossible time or a range backwards is ignored`() {
        assertEquals(AllDayHints("Thing (25:00)", null, null), parse("Thing (25:00)"))
        assertEquals(AllDayHints("Thing (11:00-10:00)", null, null), parse("Thing (11:00-10:00)"))
    }

    @Test
    fun `a title that is only brackets keeps its text`() {
        assertEquals("(3h)", parse("(3h)").title)
        assertEquals(180, parse("(3h)").estMin)
    }

    @Test
    fun `lengths are kept within what a task can be`() {
        assertEquals(720, parse("Trip (30h)").estMin)
        assertEquals(5, parse("Ping (1m)").estMin)
        assertNull(parse("Ping").estMin)
    }

    @Test
    fun `two bracket groups are both read`() {
        assertEquals(AllDayHints("Lecture", 60, LocalTime.of(14, 0)), parse("Lecture (1h) (from 14:00)"))
    }
}
