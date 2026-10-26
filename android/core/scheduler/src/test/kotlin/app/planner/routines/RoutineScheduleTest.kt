package app.planner.routines

import app.planner.scheduler.DayRules
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoutineScheduleTest {
    private val zone = ZoneId.of("Europe/London")
    private val rules = DayRules()

    private fun at(date: String, time: String): Instant =
        LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    private fun next(routine: Routine, now: Instant, fixed: (LocalDate) -> Int = { 0 }, r: DayRules = rules) =
        RoutineSchedule.next(routine, now, zone, r, fixed)

    // 2026-10-26 is a Monday.

    @Test
    fun `work stops at 21 00 later the same day`() {
        assertEquals(at("2026-10-26", "21:00"), next(Routine.WORK_STOPS, at("2026-10-26", "09:00")))
    }

    @Test
    fun `work stops moves to the next day once 21 00 has passed`() {
        assertEquals(at("2026-10-27", "21:00"), next(Routine.WORK_STOPS, at("2026-10-26", "21:30")))
    }

    @Test
    fun `a trigger that is exactly now is not returned again`() {
        assertEquals(at("2026-10-27", "21:00"), next(Routine.WORK_STOPS, at("2026-10-26", "21:00")))
    }

    @Test
    fun `work stops at 22 00 on a packed day`() {
        val packed = { d: LocalDate -> if (d == LocalDate.parse("2026-10-28")) 180 else 0 }
        assertEquals(at("2026-10-28", "22:00"), next(Routine.WORK_STOPS, at("2026-10-28", "08:00"), packed))
    }

    @Test
    fun `just under three hours of fixed events is not packed`() {
        assertEquals(at("2026-10-28", "21:00"), next(Routine.WORK_STOPS, at("2026-10-28", "08:00"), fixed = { 179 }))
    }

    @Test
    fun `a packed day only moves that day's work stop`() {
        val packed = { d: LocalDate -> if (d == LocalDate.parse("2026-10-27")) 240 else 0 }
        // 22:00 has passed on the packed Tuesday, so the next one is Wednesday's ordinary 21:00.
        assertEquals(at("2026-10-28", "21:00"), next(Routine.WORK_STOPS, at("2026-10-27", "22:30"), packed))
    }

    @Test
    fun `plan ready is the wind-down time and lights-out warning is 30 minutes before lights-out`() {
        val now = at("2026-10-26", "18:00")
        assertEquals(at("2026-10-26", "23:30"), next(Routine.PLAN_READY, now))
        assertEquals(at("2026-10-26", "23:45"), next(Routine.LIGHTS_OUT, now))
    }

    @Test
    fun `plan ready rolls to the next day after 23 30`() {
        assertEquals(at("2026-10-27", "23:30"), next(Routine.PLAN_READY, at("2026-10-26", "23:31")))
        assertEquals(at("2026-10-26", "23:45"), next(Routine.LIGHTS_OUT, at("2026-10-26", "23:31")))
    }

    @Test
    fun `lights-out warning after midnight lights-out still belongs to the evening before`() {
        val late = DayRules(lightsOut = LocalTime.of(0, 30))
        assertEquals(at("2026-10-27", "00:00"), next(Routine.LIGHTS_OUT, at("2026-10-26", "18:00"), r = late))
    }

    @Test
    fun `lights-out warning before midnight is the same evening`() {
        val early = DayRules(lightsOut = LocalTime.of(22, 0), windDown = LocalTime.of(21, 30))
        assertEquals(at("2026-10-26", "21:30"), next(Routine.LIGHTS_OUT, at("2026-10-26", "18:00"), r = early))
    }

    @Test
    fun `sunday review is at 19 00 on the coming Sunday`() {
        assertEquals(at("2026-11-01", "19:00"), next(Routine.SUNDAY_REVIEW, at("2026-10-26", "10:00")))
    }

    @Test
    fun `on Sunday before 19 00 the review is today, after it a week later`() {
        assertEquals(at("2026-11-01", "19:00"), next(Routine.SUNDAY_REVIEW, at("2026-11-01", "12:00")))
        assertEquals(at("2026-11-08", "19:00"), next(Routine.SUNDAY_REVIEW, at("2026-11-01", "19:00")))
    }

    @Test
    fun `clocks going back keep the local time and the gap between triggers is 25 hours`() {
        // BST ends on Sunday 2026-10-25 at 02:00.
        val first = next(Routine.PLAN_READY, at("2026-10-24", "20:00"))
        val second = next(Routine.PLAN_READY, first)
        assertEquals(at("2026-10-24", "23:30"), first)
        assertEquals(at("2026-10-25", "23:30"), second)
        assertEquals(25 * 60L, java.time.Duration.between(first, second).toMinutes())
    }

    @Test
    fun `clocks going forward keep the local time and the gap between triggers is 23 hours`() {
        // BST starts on Sunday 2026-03-29 at 01:00.
        val first = next(Routine.WORK_STOPS, at("2026-03-28", "20:00"))
        val second = next(Routine.WORK_STOPS, first)
        assertEquals(at("2026-03-28", "21:00"), first)
        assertEquals(at("2026-03-29", "21:00"), second)
        assertEquals(23 * 60L, java.time.Duration.between(first, second).toMinutes())
    }

    @Test
    fun `a time that does not exist on the clock change day is pushed forward, never skipped`() {
        val oneThirty = DayRules(windDown = LocalTime.of(1, 30), lightsOut = LocalTime.of(2, 30))
        val result = next(Routine.PLAN_READY, at("2026-03-28", "23:00"), r = oneThirty)
        val local = result.atZone(zone)
        assertEquals(LocalDate.parse("2026-03-29"), local.toLocalDate())
        assertTrue(local.toLocalTime() >= LocalTime.of(1, 30))
        assertTrue(result > at("2026-03-28", "23:00"))
    }

    @Test
    fun `nextAll gives every timed routine and nothing else`() {
        val all = RoutineSchedule.nextAll(at("2026-10-26", "10:00"), zone, rules) { 0 }
        assertEquals(
            setOf(Routine.WORK_STOPS, Routine.PLAN_READY, Routine.LIGHTS_OUT, Routine.SUNDAY_REVIEW),
            all.keys,
        )
        assertTrue(all.values.all { it > at("2026-10-26", "10:00") })
    }

    @Test
    fun `only timed routines can be scheduled`() {
        assertFailsWith<IllegalArgumentException> { next(Routine.MORNING_BRIEF, at("2026-10-26", "10:00")) }
        assertFailsWith<IllegalArgumentException> { next(Routine.PROVISIONAL_READINESS, at("2026-10-26", "10:00")) }
    }
}
