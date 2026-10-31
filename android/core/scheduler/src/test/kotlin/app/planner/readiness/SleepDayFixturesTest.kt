package app.planner.readiness

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance fixtures for sleep: the shapes of his real nights (single block, Fajr split, a second sleep
 * after 11:00, a fragmented night, two apps writing the same night) and the totals they must give.
 */
class SleepDayFixturesTest {
    private val zone = ZoneId.of("Europe/London")
    private val fitbit = "com.fitbit.FitbitMobile"
    private val samsung = "com.sec.android.app.shealth"

    private fun at(date: String, time: String): Instant = LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    /** A record from [from] on [date] to [to] (the next day if [to] is earlier), asleep throughout unless [asleep] says. */
    private fun rec(date: String, from: String, to: String, asleep: Int? = null, origin: String? = fitbit, manual: Boolean = false): SleepSession {
        val s = at(date, from)
        var e = at(date, to)
        if (e <= s) e = e.plus(Duration.ofDays(1))
        return SleepSession(s, e, asleep ?: Duration.between(s, e).toMinutes().toInt(), manual = manual, origin = if (manual) null else origin)
    }

    private fun days(vararg records: SleepSession) = SleepDays.build(records.toList(), zone)

    private fun date(s: String) = LocalDate.parse(s)

    @Test
    fun `single block`() {
        val day = days(rec("2026-10-28", "01:27", "05:00")).single()
        assertEquals(date("2026-10-28"), day.wakeDate)
        assertEquals(213, day.totalAsleepMin)
        assertEquals(213, day.coreMin)
        assertEquals(0, day.extraMin)
        assertEquals(1, day.episodes.size)
        assertEquals(at("2026-10-28", "05:00"), day.mainWake)
    }

    @Test
    fun `Fajr split is one main sleep with the wake-up as an interruption`() {
        val first = sleepSessionFromStages(
            at("2026-10-28", "00:32"), at("2026-10-28", "05:14"),
            listOf(
                SleepStage(StageKind.LIGHT, at("2026-10-28", "00:32"), at("2026-10-28", "02:00")),
                SleepStage(StageKind.AWAKE, at("2026-10-28", "02:00"), at("2026-10-28", "02:12")),
                SleepStage(StageKind.DEEP, at("2026-10-28", "02:12"), at("2026-10-28", "05:14")),
            ),
        ).copy(origin = fitbit)
        val day = days(rec("2026-10-28", "07:04", "09:14"), first).single()

        assertEquals(282 - 12 + 130, day.totalAsleepMin)
        assertEquals(270, day.coreMin)
        assertEquals(130, day.extraMin)
        assertEquals(2, day.main.size)
        assertEquals(at("2026-10-28", "00:32"), day.lightsOut)
        assertEquals(at("2026-10-28", "09:14"), day.mainWake)
        assertEquals(listOf(12, 110), day.interruptions.map { it.minutes })
        assertEquals(122, day.awakeMin)
    }

    @Test
    fun `a second sleep after 11 00 is counted in the same day`() {
        val day = days(rec("2026-10-28", "00:40", "05:20"), rec("2026-10-28", "11:30", "14:10")).single()
        assertEquals(date("2026-10-28"), day.wakeDate)
        assertEquals(280 + 160, day.totalAsleepMin)
        assertEquals(280, day.coreMin)
        assertEquals(160, day.extraMin)
        assertEquals(at("2026-10-28", "05:20"), day.mainWake)
        // the hours awake between the night and the second sleep are a day, not an interruption
        assertTrue(day.interruptions.isEmpty())

        val night = day.toNight()
        assertEquals(440, night.asleepMin)
        assertEquals(280, night.nightAsleepMin)
        assertEquals(1, night.extra.size)
    }

    @Test
    fun `three fragments are all kept, however short`() {
        val day = days(
            rec("2026-10-27", "23:50", "01:10"),
            rec("2026-10-28", "01:55", "05:05"),
            rec("2026-10-28", "05:50", "06:05"),
        ).single()
        assertEquals(date("2026-10-28"), day.wakeDate)
        assertEquals(80 + 190 + 15, day.totalAsleepMin)
        assertEquals(190, day.coreMin)
        assertEquals(95, day.extraMin)
        assertEquals(3, day.episodes.size)
        assertEquals(listOf(45, 45), day.interruptions.map { it.minutes })
        assertEquals(at("2026-10-27", "23:50"), day.lightsOut)
        assertEquals(at("2026-10-28", "06:05"), day.mainWake)
    }

    @Test
    fun `two writers overlapping, Fitbit counts and the other is dropped`() {
        val day = days(
            rec("2026-10-28", "00:05", "08:40", asleep = 480, origin = samsung),
            rec("2026-10-28", "00:20", "08:20", asleep = 440, origin = fitbit),
        ).single()
        assertEquals(440, day.totalAsleepMin)
        assertEquals(setOf<String?>(fitbit), day.episodes.single().origins)
        assertEquals(at("2026-10-28", "00:20"), day.lightsOut)
    }

    @Test
    fun `another writer fills a day Fitbit has nothing for`() {
        val list = days(
            rec("2026-10-27", "00:20", "08:20", asleep = 440, origin = fitbit),
            rec("2026-10-27", "00:10", "08:30", asleep = 470, origin = samsung),
            rec("2026-10-28", "00:30", "07:30", asleep = 400, origin = samsung),
        )
        assertEquals(listOf(440, 400), list.map { it.totalAsleepMin })
    }

    @Test
    fun `two other writers on a day without Fitbit are never added together`() {
        val day = days(
            rec("2026-10-28", "00:30", "07:30", asleep = 400, origin = samsung),
            rec("2026-10-28", "00:40", "07:00", asleep = 380, origin = "com.example.sleep"),
        ).single()
        assertEquals(400, day.totalAsleepMin)
    }

    @Test
    fun `records of one writer that overlap are counted once`() {
        val day = days(rec("2026-10-28", "00:20", "05:00", origin = null), rec("2026-10-28", "04:00", "08:00", origin = null)).single()
        assertEquals(280 + 180, day.totalAsleepMin)
        assertEquals(1, day.episodes.size)
    }

    @Test
    fun `a record typed in by hand always counts`() {
        val day = days(rec("2026-10-28", "13:00", "14:00", manual = true), rec("2026-10-28", "00:30", "07:30")).single()
        assertEquals(420 + 60, day.totalAsleepMin)
    }

    @Test
    fun `a nap after lunch and an evening doze stay with the day he woke`() {
        val list = days(
            rec("2026-10-27", "00:30", "07:30"),
            rec("2026-10-27", "14:00", "14:40"),
            rec("2026-10-27", "20:30", "20:50"),
            rec("2026-10-28", "00:45", "07:45"),
        )
        assertEquals(listOf(date("2026-10-27"), date("2026-10-28")), list.map { it.wakeDate })
        assertEquals(420 + 40 + 20, list[0].totalAsleepMin)
        assertEquals(420, list[1].totalAsleepMin)
        assertEquals(at("2026-10-28", "00:45"), list[1].lightsOut)
    }

    @Test
    fun `sleep starting more than 16 hours after the wake-up goes with the next night`() {
        val list = days(
            rec("2026-10-27", "00:30", "07:30"),
            rec("2026-10-27", "23:45", "00:05"),
            rec("2026-10-28", "03:30", "10:00"),
        )
        assertEquals(listOf(420, 20 + 390), list.map { it.totalAsleepMin })
        assertEquals(at("2026-10-28", "03:30"), list[1].lightsOut)
    }

    @Test
    fun `a short main night with a long sleep later is still that morning's day`() {
        val list = days(
            rec("2026-10-27", "00:30", "07:30"),
            rec("2026-10-28", "01:00", "03:00"),
            rec("2026-10-28", "11:30", "15:00"),
        )
        assertEquals(listOf(date("2026-10-27"), date("2026-10-28")), list.map { it.wakeDate })
        assertEquals(120 + 210, list[1].totalAsleepMin)
        assertEquals(210, list[1].coreMin)
    }

    @Test
    fun `nights a week apart are separate days`() {
        val list = days(*(0 until 7).map { rec(date("2026-11-02").plusDays(it.toLong()).toString(), "00:15", "07:15") }.toTypedArray())
        assertEquals(7, list.size)
        assertTrue(list.all { it.totalAsleepMin == 420 })
    }

    @Test
    fun `the source rule on its own keeps only what counts`() {
        val kept = SleepSources.select(
            listOf(
                rec("2026-10-28", "00:05", "08:40", origin = samsung),
                rec("2026-10-28", "00:20", "08:20", origin = fitbit),
                rec("2026-10-28", "15:00", "15:30", manual = true),
            ),
            zone,
        )
        assertEquals(listOf(fitbit, null), kept.map { it.origin })
    }

    @Test
    fun `the summary gives rolling totals and one line per day`() {
        val list = days(rec("2026-10-27", "00:30", "07:30"), rec("2026-10-28", "00:32", "05:14"), rec("2026-10-28", "07:04", "09:14"))
        val summary = SleepSummaries.summarise(list, at("2026-10-28", "12:00"), zone)
        assertEquals(282 + 130, summary.asleep24hMin)
        assertEquals(420 + 412, summary.asleep72hMin)
        assertEquals(2, summary.days.size)
        assertEquals(SleepDayLine(date("2026-10-28"), 412, 282, 130, 2, 1, 110, "00:32", "09:14"), summary.days.last())
        val text = SleepSummaries.text(summary)
        assertTrue(text.startsWith("Last 24h: 6h 52m asleep (need 7h). Last 72h: 13h 52m."), text)
        assertTrue(text.contains("2026-10-28: 6h 52m (core 4h 42m, extra 2h 10m), 00:32 to 09:14, 1 interruption, 1h 50m awake"), text)
    }
}
