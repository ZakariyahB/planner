package app.planner.calendar

import app.planner.model.Prayer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrayerTimesTest {
    private val london = ZoneId.of("Europe/London")

    private fun event(title: String, start: String, calendar: String = "prayer") =
        CalendarEvent(title + start, calendar, title, EventTiming.Timed(Instant.parse(start), Instant.parse(start).plusSeconds(900)))

    @Test
    fun `identifies prayers by common spellings`() {
        assertEquals(Prayer.DHUHR, PrayerTimes.identify("Dhuhr"))
        assertEquals(Prayer.DHUHR, PrayerTimes.identify("Zuhr 12:40"))
        assertEquals(Prayer.ASR, PrayerTimes.identify("Asr"))
        assertEquals(Prayer.MAGHRIB, PrayerTimes.identify("maghrib"))
        assertEquals(Prayer.ISHA, PrayerTimes.identify("Isha'"))
        assertEquals(Prayer.FAJR, PrayerTimes.identify("Fajr"))
        assertNull(PrayerTimes.identify("Sunrise"))
    }

    @Test
    fun `only the prayer calendar is read and times are local`() {
        val events = listOf(
            event("Dhuhr", "2026-10-28T12:40:00Z"),
            event("Asr", "2026-10-28T14:55:00Z"),
            event("Sunrise", "2026-10-28T07:00:00Z"),
            event("Asr", "2026-10-28T09:00:00Z", calendar = "other"),
        )
        val byDate = PrayerTimes.fromEvents(events, "prayer", london)
        assertEquals(mapOf(Prayer.DHUHR to LocalTime.of(12, 40), Prayer.ASR to LocalTime.of(14, 55)), byDate[LocalDate.of(2026, 10, 28)])
    }

    @Test
    fun `saturday borrows friday and sunday borrows monday`() {
        val byDate = mapOf(
            LocalDate.of(2026, 10, 30) to mapOf(Prayer.ASR to LocalTime.of(14, 50)),
            LocalDate.of(2026, 11, 2) to mapOf(Prayer.ASR to LocalTime.of(14, 40)),
        )
        assertEquals(LocalTime.of(14, 50), PrayerTimes.forDate(LocalDate.of(2026, 10, 31), byDate, null)[Prayer.ASR])
        assertEquals(LocalTime.of(14, 40), PrayerTimes.forDate(LocalDate.of(2026, 11, 1), byDate, null)[Prayer.ASR])
    }

    @Test
    fun `sunday falls back to friday when monday is not loaded`() {
        val byDate = mapOf(LocalDate.of(2026, 10, 30) to mapOf(Prayer.ASR to LocalTime.of(14, 50)))
        assertEquals(LocalTime.of(14, 50), PrayerTimes.forDate(LocalDate.of(2026, 11, 1), byDate, null)[Prayer.ASR])
    }

    @Test
    fun `fajr comes from the weekly entry and a weekday without data has no prayers`() {
        val weekday = LocalDate.of(2026, 10, 28)
        assertEquals(mapOf(Prayer.FAJR to LocalTime.of(5, 45)), PrayerTimes.forDate(weekday, emptyMap(), LocalTime.of(5, 45)))
        assertTrue(PrayerTimes.forDate(weekday, emptyMap(), null).isEmpty())
    }
}
