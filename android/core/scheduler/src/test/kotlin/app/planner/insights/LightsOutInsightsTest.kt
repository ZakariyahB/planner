package app.planner.insights

import app.planner.readiness.SleepBlock
import app.planner.readiness.SleepNight
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class LightsOutInsightsTest {
    private val zone = ZoneId.of("Europe/London")

    private fun night(morning: String, lightsOut: String): SleepNight {
        val d = LocalDate.parse(morning)
        val t = LocalTime.parse(lightsOut)
        val startDate = if (t >= LocalTime.of(12, 0)) d.minusDays(1) else d
        val start = startDate.atTime(t).atZone(zone).toInstant()
        return SleepNight(d, listOf(SleepBlock(start, start.plusSeconds(6 * 3600), 360)))
    }

    private fun delay(lightsOut: String) = LightsOutInsights.rows(listOf(night("2026-10-27", lightsOut)), zone).single().minutesAfterTarget

    @Test
    fun `lights-out is measured against 00 15, early being negative`() {
        assertEquals(17, delay("00:32"))
        assertEquals(72, delay("01:27"))
        assertEquals(0, delay("00:15"))
        assertEquals(-25, delay("23:50"))
        assertEquals(-45, delay("23:30"))
    }

    @Test
    fun `rows are the last seven nights, oldest first, with weekdays`() {
        val nights = (20..30).map { night("2026-10-$it", "00:30") }
        val rows = LightsOutInsights.rows(nights, zone)
        assertEquals(7, rows.size)
        assertEquals("Sat", rows.first().weekday) // 24 October 2026
        assertEquals(LocalDate.of(2026, 10, 30), rows.last().date)
    }

    @Test
    fun `labels are short and say which side of the target`() {
        assertEquals("+17 min", LightsOutInsights.label(17))
        assertEquals("−25 min", LightsOutInsights.label(-25))
        assertEquals("on time", LightsOutInsights.label(0))
    }

    @Test
    fun `bars grow with how far from the target, capped at two hours`() {
        assertEquals(0.5, LightsOutInsights.barFraction(60), 1e-9)
        assertEquals(0.5, LightsOutInsights.barFraction(-60), 1e-9)
        assertEquals(1.0, LightsOutInsights.barFraction(300), 1e-9)
        assertEquals(0.0, LightsOutInsights.barFraction(0), 1e-9)
    }

    @Test
    fun `the average counts late nights as late and early ones as early`() {
        val rows = LightsOutInsights.rows(listOf(night("2026-10-26", "01:15"), night("2026-10-27", "23:15")), zone)
        assertEquals(0, LightsOutInsights.averageMinutesAfterTarget(rows))
        assertEquals(null, LightsOutInsights.averageMinutesAfterTarget(emptyList()))
    }
}
