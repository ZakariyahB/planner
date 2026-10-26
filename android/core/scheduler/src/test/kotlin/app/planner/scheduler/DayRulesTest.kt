package app.planner.scheduler

import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DayRulesTest {
    private val rules = DayRules()

    @Test
    fun `normal day stops work at 21 00`() {
        assertEquals(LocalTime.of(21, 0), rules.workEndFor(DayOfWeek.MONDAY, fixedMinutes = 60))
    }

    @Test
    fun `packed day with 3h of fixed events stops at 22 00`() {
        assertEquals(LocalTime.of(22, 0), rules.workEndFor(DayOfWeek.WEDNESDAY, fixedMinutes = 180))
    }

    @Test
    fun `just under packed threshold stays at 21 00`() {
        assertEquals(LocalTime.of(21, 0), rules.workEndFor(DayOfWeek.WEDNESDAY, fixedMinutes = 179))
    }

    @Test
    fun `friday is clear from 17 00 even on a packed day`() {
        assertEquals(LocalTime.of(17, 0), rules.workEndFor(DayOfWeek.FRIDAY, fixedMinutes = 240))
    }

    @Test
    fun `packed stop earlier than normal stop is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            DayRules(workStop = LocalTime.of(22, 0), workStopPacked = LocalTime.of(21, 0))
        }
    }
}
