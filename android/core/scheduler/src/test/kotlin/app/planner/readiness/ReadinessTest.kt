package app.planner.readiness

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadinessTest {
    private val zone = ZoneId.of("Europe/London")

    private fun at(date: String, time: String): Instant = LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    private fun session(date: String, from: String, toDate: String, to: String, asleep: Int? = null): SleepSession {
        val s = at(date, from)
        val e = at(toDate, to)
        return SleepSession(s, e, asleep ?: java.time.Duration.between(s, e).toMinutes().toInt())
    }

    // Shapes of the real Takeout data.
    private val singleBlock = session("2026-10-28", "01:27", "2026-10-28", "05:00") // 3h33m
    private val splitFirst = session("2026-10-28", "00:32", "2026-10-28", "05:14") // 4h42m
    private val splitSecond = session("2026-10-28", "07:04", "2026-10-28", "09:14") // 2h10m
    private val sleptThrough = session("2026-10-27", "23:50", "2026-10-28", "07:20") // 7h30m

    private fun fullNights(from: String, count: Int): List<SleepNight> = (0 until count).map { i ->
        val d = LocalDate.parse(from).plusDays(i.toLong())
        SleepNight(d, listOf(SleepBlock(d.atTime(0, 15).atZone(zone).toInstant(), d.atTime(7, 15).atZone(zone).toInstant(), 420)))
    }

    @Test
    fun `a single 3h33 block is one night with lights-out at 01 27`() {
        val nights = SleepGrouping.group(listOf(singleBlock), zone)
        assertEquals(1, nights.size)
        assertEquals(LocalDate.parse("2026-10-28"), nights[0].date)
        assertEquals(213, nights[0].asleepMin)
        assertEquals(singleBlock.start, nights[0].lightsOut)
    }

    @Test
    fun `waking at Fajr gives two blocks in one night`() {
        val night = SleepGrouping.group(listOf(splitSecond, splitFirst), zone).single()
        assertEquals(2, night.blocks.size)
        assertEquals(282 + 130, night.asleepMin)
        assertEquals(splitFirst.end, night.firstBlockEnd)
        assertEquals(splitSecond.end, night.lastEnd)
    }

    @Test
    fun `a night slept through from before midnight belongs to the next morning`() {
        val night = SleepGrouping.group(listOf(sleptThrough), zone).single()
        assertEquals(LocalDate.parse("2026-10-28"), night.date)
        assertEquals(1, night.blocks.size)
    }

    @Test
    fun `naps and tiny records are ignored and brief wake-ups merge`() {
        val nap = session("2026-10-28", "14:00", "2026-10-28", "14:40")
        val tiny = session("2026-10-28", "02:00", "2026-10-28", "02:10")
        assertTrue(SleepGrouping.group(listOf(nap, tiny), zone).isEmpty())

        val a = session("2026-10-28", "00:30", "2026-10-28", "03:00")
        val b = session("2026-10-28", "03:20", "2026-10-28", "06:00")
        val merged = SleepGrouping.group(listOf(a, b), zone).single()
        assertEquals(1, merged.blocks.size)
        assertEquals(150 + 160, merged.asleepMin)
    }

}
