package app.planner.engine.prayer

import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.EventRef
import app.planner.engine.model.FixedEvent
import app.planner.engine.model.Prayer
import app.planner.engine.model.Prayer.ASR
import app.planner.engine.model.Prayer.DHUHR
import app.planner.engine.model.Prayer.ISHA
import app.planner.engine.model.Prayer.MAGHRIB
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrayerModelTest {
    private val model = DefaultPrayerModel
    private fun t(s: String) = LocalTime.parse(s)
    private fun minutes(a: LocalTime, b: LocalTime) = abs(a.toSecondOfDay() - b.toSecondOfDay()) / 60

    private fun resolve(dates: List<LocalDate>, imported: List<PrayerAnchor>, settings: Settings = Settings()) =
        model.resolve(dates, imported, settings)

    private fun List<PrayerAnchor>.at(date: LocalDate, p: Prayer) = single { it.date == date && it.prayer == p }

    @Test fun `weekdays keep their imported begin times, not interpolated`() {
        val out = resolve(ReferenceWeek.days.take(5), ReferenceWeek.importedAnchors)
        assertEquals(ReferenceWeek.importedAnchors.sortedWith(compareBy({ it.date }, { it.begin })), out)
        assertTrue(out.none { it.interpolated })
    }

    @Test fun `weekend is interpolated from the weekdays by the day-to-day trend and marked`() {
        val sat = ReferenceWeek.days[5]
        val sun = ReferenceWeek.days[6]
        val out = resolve(ReferenceWeek.days, ReferenceWeek.importedAnchors)
        val weekend = out.filter { it.date == sat || it.date == sun }
        assertEquals(8, weekend.size)
        assertTrue(weekend.all { it.interpolated })
        // Asr, Maghrib and Isha match the newplan 13.1 table exactly.
        for (p in listOf(ASR, MAGHRIB, ISHA)) for (d in listOf(sat, sun)) {
            assertEquals(ReferenceWeek.anchors.at(d, p).begin, out.at(d, p).begin, "$p $d")
        }
        // Dhuhr is flat at 11:49 Mon-Thu (no Friday Dhuhr), so the trend is 0 and the weekend gets 11:49. The table's
        // 11:48 is one minute earlier; see notes/A.md.
        assertEquals(t("11:49"), out.at(sat, DHUHR).begin)
        assertEquals(t("11:49"), out.at(sun, DHUHR).begin)
        // Every weekend value is within a minute of the table.
        weekend.forEach { assertTrue(minutes(it.begin, ReferenceWeek.anchors.at(it.date, it.prayer).begin) <= 1, "$it") }
    }

    @Test fun `trend is the least-squares slope over the nearest weekdays`() {
        // Asr Mon-Fri 14:05, 14:04, 14:02, 14:01, 14:00: slope -1.3/day, so Sat 13:58.7 -> 13:59 and Sun 13:57.4 -> 13:57.
        // Using only the last step (-1/day) would give Sun 13:58.
        val out = resolve(ReferenceWeek.days, ReferenceWeek.importedAnchors)
        assertEquals(t("13:59"), out.at(ReferenceWeek.days[5], ASR).begin)
        assertEquals(t("13:57"), out.at(ReferenceWeek.days[6], ASR).begin)
    }

    @Test fun `with weekdays on both sides the weekend is linearly interpolated`() {
        val fri = ReferenceWeek.days[4]
        val nextMon = fri.plusDays(3)
        val imported = listOf(PrayerAnchor(fri, ASR, t("14:00")), PrayerAnchor(nextMon, ASR, t("13:54")))
        val out = resolve(listOf(fri.plusDays(1), fri.plusDays(2)), imported)
        assertEquals(listOf(t("13:58"), t("13:56")), out.map { it.begin })
        assertTrue(out.all { it.interpolated })
    }

    @Test fun `rounding is half up to the minute`() {
        val fri = ReferenceWeek.days[4]
        val nextMon = fri.plusDays(3)
        // 14:00 to 13:59 over 3 days: Sat 13:59.67 -> 14:00, Sun 13:59.33 -> 13:59.
        val out = resolve(listOf(fri.plusDays(1), fri.plusDays(2)), listOf(PrayerAnchor(fri, ASR, t("14:00")), PrayerAnchor(nextMon, ASR, t("13:59"))))
        assertEquals(listOf(t("14:00"), t("13:59")), out.map { it.begin })
        // Exactly half: 14:00 and 13:59 over 2 days -> 13:59.5 rounds up to 14:00.
        val half = resolve(listOf(fri.plusDays(1)), listOf(PrayerAnchor(fri, ASR, t("14:00")), PrayerAnchor(fri.plusDays(2), ASR, t("13:59"))))
        assertEquals(t("14:00"), half.single().begin)
    }

    @Test fun `interpolation across the October clock change works in solar time`() {
        // UK clocks go back on Sunday 25 October 2026. Friday 23 is BST, Monday 26 is GMT.
        val fri = LocalDate.of(2026, 10, 23)
        val mon = LocalDate.of(2026, 10, 26)
        val imported = listOf(PrayerAnchor(fri, ASR, t("15:20")), PrayerAnchor(mon, ASR, t("14:14")))
        val out = resolve(listOf(fri.plusDays(1), fri.plusDays(2)), imported)
        // In UTC: 14:20 -> 14:14 over 3 days, so Sat 14:18 UTC (15:18 BST) and Sun 14:16 UTC (14:16 GMT).
        assertEquals(listOf(t("15:18"), t("14:16")), out.map { it.begin })
    }

    @Test fun `extrapolation across the clock change keeps the trend in solar time`() {
        // Asr Mon-Fri 19-23 Oct (BST) 15:28 down to 15:20, two minutes a day; only that side is known.
        val mon = LocalDate.of(2026, 10, 19)
        val imported = (0..4).map { PrayerAnchor(mon.plusDays(it.toLong()), ASR, t("15:28").minusMinutes(2L * it)) }
        val out = resolve(listOf(mon.plusDays(5), mon.plusDays(6)), imported)
        assertEquals(listOf(t("15:18"), t("14:16")), out.map { it.begin })
    }

    @Test fun `spring clock change`() {
        // UK clocks go forward on Sunday 29 March 2026. Friday 27 is GMT, Monday 30 is BST.
        val fri = LocalDate.of(2026, 3, 27)
        val mon = LocalDate.of(2026, 3, 30)
        val imported = listOf(PrayerAnchor(fri, MAGHRIB, t("18:24")), PrayerAnchor(mon, MAGHRIB, t("19:30")))
        val out = resolve(listOf(fri.plusDays(1), fri.plusDays(2)), imported)
        // UTC 18:24 -> 18:30: Sat 18:26 GMT, Sun 18:28 UTC = 19:28 BST.
        assertEquals(listOf(t("18:26"), t("19:28")), out.map { it.begin })
    }

    @Test fun `a single known day gives that day's time with no trend`() {
        val fri = ReferenceWeek.days[4]
        val out = resolve(listOf(fri.plusDays(1)), listOf(PrayerAnchor(fri, ISHA, t("17:58"))))
        assertEquals(t("17:58"), out.single().begin)
        assertTrue(out.single().interpolated)
    }

    @Test fun `no known day within a week means no anchor`() {
        val fri = ReferenceWeek.days[4]
        assertTrue(resolve(listOf(fri.plusDays(8)), listOf(PrayerAnchor(fri, ISHA, t("17:58")))).isEmpty())
        assertEquals(1, resolve(listOf(fri.plusDays(7)), listOf(PrayerAnchor(fri, ISHA, t("17:58")))).size)
    }

    @Test fun `a missing weekday is interpolated too`() {
        val imported = ReferenceWeek.importedAnchors.filterNot { it.date == ReferenceWeek.days[2] }
        val out = resolve(listOf(ReferenceWeek.days[2]), imported)
        assertEquals(4, out.size)
        assertTrue(out.all { it.interpolated })
        // Tue 14:04 and Thu 14:01: Wed 14:02.5 -> 14:03 (the real value was 14:02).
        assertEquals(t("14:03"), out.at(ReferenceWeek.days[2], ASR).begin)
    }

    @Test fun `per-prayer offsets apply to imported and interpolated times`() {
        val settings = Settings(prayerOffsetMin = mapOf(DHUHR to 20, ASR to -5))
        val out = resolve(ReferenceWeek.days, ReferenceWeek.importedAnchors, settings)
        assertEquals(t("12:09"), out.at(ReferenceWeek.days[0], DHUHR).begin)
        assertEquals(t("14:00"), out.at(ReferenceWeek.days[0], ASR).begin)
        assertEquals(t("16:35"), out.at(ReferenceWeek.days[0], MAGHRIB).begin)
        assertEquals(t("13:54"), out.at(ReferenceWeek.days[5], ASR).begin)
        assertEquals(t("12:09"), out.at(ReferenceWeek.days[5], DHUHR).begin)
    }

    @Test fun `no Dhuhr on Friday, even interpolated`() {
        val out = resolve(ReferenceWeek.days, ReferenceWeek.importedAnchors)
        assertTrue(out.none { it.date == ReferenceWeek.days[4] && it.prayer == DHUHR })
        assertEquals(3, out.count { it.date == ReferenceWeek.days[4] })
    }

    @Test fun `Fajr is never returned and never used for interpolation`() {
        val fajr = ReferenceWeek.days.take(5).map { PrayerAnchor(it, Prayer.FAJR, t("05:30")) }
        val out = resolve(ReferenceWeek.days, ReferenceWeek.importedAnchors + fajr)
        assertTrue(out.none { it.prayer == Prayer.FAJR })
        assertEquals(27, out.size) // 7 days x 4, minus Friday Dhuhr.
    }

    @Test fun `duplicate imports - the first wins`() {
        val mon = ReferenceWeek.days[0]
        val out = resolve(listOf(mon), listOf(PrayerAnchor(mon, ASR, t("14:05")), PrayerAnchor(mon, ASR, t("15:00"))))
        assertEquals(t("14:05"), out.single().begin)
    }

    @Test fun `output is sorted by date and time and dates are deduplicated`() {
        val days = ReferenceWeek.days.reversed() + ReferenceWeek.days
        val out = resolve(days, ReferenceWeek.importedAnchors.shuffled(java.util.Random(1)))
        assertEquals(out.sortedWith(compareBy({ it.date }, { it.begin })), out)
        assertEquals(out.distinct(), out)
    }

    @Test fun `absorb drops anchors inside a fixed event - start inclusive, end exclusive`() {
        val wed = ReferenceWeek.days[2]
        val anchors = ReferenceWeek.anchorsOn(wed)
        fun ev(from: String, to: String) = FixedEvent(EventRef("c", "$from-$to"), "E", wed.atTime(t(from)), wed.atTime(t(to)))
        assertEquals(listOf(DHUHR, MAGHRIB, ISHA), model.absorb(anchors, listOf(ev("14:00", "16:30"))).map { it.prayer })
        // Event starting exactly at the anchor absorbs it.
        assertTrue(model.absorb(anchors, listOf(ev("14:02", "15:00"))).none { it.prayer == ASR })
        // Event ending exactly at the anchor does not.
        assertTrue(model.absorb(anchors, listOf(ev("13:00", "14:02"))).any { it.prayer == ASR })
        // Only the slot overlapping (anchor before the event) does not absorb either.
        assertTrue(model.absorb(anchors, listOf(ev("14:10", "15:00"))).any { it.prayer == ASR })
        // Other days are untouched.
        assertEquals(ReferenceWeek.anchors.size - 2, model.absorb(ReferenceWeek.anchors, ReferenceWeek.fixed.filter { it.start.toLocalDate() == wed }).size)
    }
}
