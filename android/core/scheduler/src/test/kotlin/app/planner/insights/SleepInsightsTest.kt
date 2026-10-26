package app.planner.insights

import app.planner.readiness.SleepBlock
import app.planner.readiness.SleepNight
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SleepInsightsTest {
    private val zone = ZoneId.of("Europe/London")

    private fun at(date: String, time: String) = LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    private fun night(morning: String, vararg blocks: Triple<String, String, Int>): SleepNight {
        val d = LocalDate.parse(morning)
        return SleepNight(
            d,
            blocks.map { (from, to, asleep) ->
                // A start before noon belongs to the morning date; an evening start to the evening before.
                val startDate = if (LocalTime.parse(from) >= LocalTime.of(12, 0)) d.minusDays(1) else d
                SleepBlock(at(startDate.toString(), from), at(d.toString(), to), asleep)
            },
        )
    }

    private val tuesday = night("2026-10-27", Triple("00:32", "05:14", 282), Triple("07:04", "09:14", 130))

    @Test
    fun `a night is laid out on a 21 00 to 12 00 track`() {
        val row = SleepInsights.rows(listOf(tuesday), zone).single()
        assertEquals(2, row.bars.size)
        // 00:32 is 212 minutes after 21:00 of 900
        assertEquals(212.0 / 900, row.bars[0].start, 1e-9)
        assertEquals(494.0 / 900, row.bars[0].end, 1e-9)
        assertEquals(604.0 / 900, row.bars[1].start, 1e-9)
        assertEquals(734.0 / 900, row.bars[1].end, 1e-9)
    }

    @Test
    fun `a night that starts before midnight counts from the evening`() {
        val row = SleepInsights.rows(listOf(night("2026-10-28", Triple("23:50", "07:20", 450))), zone).single()
        assertEquals(170.0 / 900, row.bars.single().start, 1e-9)
        assertEquals(620.0 / 900, row.bars.single().end, 1e-9)
    }

    @Test
    fun `bars are kept inside the track`() {
        val early = night("2026-10-28", Triple("20:00", "05:00", 400))
        val late = night("2026-10-29", Triple("02:00", "13:30", 600))
        val rows = SleepInsights.rows(listOf(early, late), zone)
        assertEquals(0.0, rows[0].bars.single().start, 1e-9)
        assertEquals(1.0, rows[1].bars.single().end, 1e-9)
    }

    @Test
    fun `rows carry the weekday and the minutes asleep`() {
        val row = SleepInsights.rows(listOf(tuesday), zone).single()
        assertEquals("Tue", row.weekday)
        assertEquals(LocalDate.of(2026, 10, 27), row.date)
        assertEquals(412, row.asleepMin)
    }

    @Test
    fun `only the last seven nights are shown, oldest first`() {
        val nights = (20..30).map { night("2026-10-$it", Triple("01:00", "06:00", 300)) }
        val rows = SleepInsights.rows(nights, zone)
        assertEquals(7, rows.size)
        assertEquals(LocalDate.of(2026, 10, 24), rows.first().date)
        assertEquals(LocalDate.of(2026, 10, 30), rows.last().date)
    }

    @Test
    fun `the target marker sits at 00 15`() {
        assertEquals(195.0 / 900, SleepInsights.TARGET_POSITION, 1e-9)
    }

    @Test
    fun `the average counts only nights that have sleep on record`() {
        val rows = SleepInsights.rows(
            listOf(night("2026-10-27", Triple("01:00", "07:00", 360)), night("2026-10-28", Triple("01:00", "05:00", 240))),
            zone,
        )
        assertEquals(300, SleepInsights.averageAsleepMin(rows))
        assertEquals(null, SleepInsights.averageAsleepMin(emptyList()))
    }

    @Test
    fun `axis labels are placed along the track`() {
        val labels = SleepInsights.AXIS.associate { it.label to it.position }
        assertEquals(0.0, labels.getValue("21:00"), 1e-9)
        assertEquals(0.2, labels.getValue("00:00"), 1e-9)
        assertEquals(1.0, labels.getValue("12:00"), 1e-9)
        assertTrue(labels.size >= 5)
    }
}
