package app.planner.calendar

import app.planner.model.Prayer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Prayer times come from the user's "Prayer times" calendar (Dhuhr/Asr/Maghrib/Isha); Fajr is typed in weekly. */
object PrayerTimes {
    fun isPrayerCalendar(name: String): Boolean = name.trim().equals("prayer times", ignoreCase = true)

    fun identify(title: String): Prayer? {
        val t = title.lowercase()
        return when {
            "fajr" in t || "fajar" in t -> Prayer.FAJR
            "dhuhr" in t || "zuhr" in t || "duhr" in t || "dhuhur" in t -> Prayer.DHUHR
            "asr" in t -> Prayer.ASR
            "maghrib" in t -> Prayer.MAGHRIB
            "isha" in t || "esha" in t -> Prayer.ISHA
            else -> null
        }
    }

    /** Local start time of each recognised prayer event, per date. */
    fun fromEvents(events: List<CalendarEvent>, prayerCalendarId: String, zone: ZoneId): Map<LocalDate, Map<Prayer, LocalTime>> =
        events
            .filter { it.calendarId == prayerCalendarId }
            .mapNotNull { e ->
                val timed = e.timing as? EventTiming.Timed ?: return@mapNotNull null
                val prayer = identify(e.title) ?: return@mapNotNull null
                val local = timed.start.atZone(zone)
                Triple(local.toLocalDate(), prayer, local.toLocalTime().withSecond(0).withNano(0))
            }
            .groupBy({ it.first }, { it.second to it.third })
            .mapValues { (_, list) -> list.toMap() }

    /**
     * Times for [date]. The calendar has no weekend events, so Saturday borrows Friday's times and
     * Sunday borrows Monday's (or Friday's if Monday isn't loaded). [fajr] is this week's typed-in time.
     */
    fun forDate(date: LocalDate, byDate: Map<LocalDate, Map<Prayer, LocalTime>>, fajr: LocalTime?): Map<Prayer, LocalTime> {
        val base = byDate[date]?.takeIf { it.isNotEmpty() }
            ?: when (date.dayOfWeek) {
                DayOfWeek.SATURDAY -> byDate[date.minusDays(1)]
                DayOfWeek.SUNDAY -> byDate[date.plusDays(1)] ?: byDate[date.minusDays(2)]
                else -> null
            }
            ?: emptyMap()
        return if (fajr != null) base + (Prayer.FAJR to fajr) else base
    }
}
