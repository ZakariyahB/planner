package app.planner.ui.model

import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.Prayer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Plain-English formatting shared by every screen. Pure Kotlin so it is tested on the JVM.

object Fmt {
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val longDay = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
    private val shortDay = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
    private val weekday = DateTimeFormatter.ofPattern("EEE", Locale.UK)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", Locale.UK)
    private val dayMonthShort = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

    fun time(t: LocalTime): String = t.format(hhmm)
    fun time(t: LocalDateTime): String = t.format(hhmm)
    fun range(start: LocalDateTime, end: LocalDateTime): String = "${time(start)} – ${time(end)}"

    /** "Tuesday 3 November". */
    fun longDate(d: LocalDate): String = d.format(longDay)

    /** "Tue 3 Nov". */
    fun shortDate(d: LocalDate): String = d.format(shortDay)

    /** "Tue". */
    fun weekday(d: LocalDate): String = d.format(weekday)

    /** "2 – 8 November", or "30 November – 6 December" across months. */
    fun weekRange(start: LocalDate, endInclusive: LocalDate): String =
        if (start.month == endInclusive.month) "${start.dayOfMonth} – ${endInclusive.format(dayMonth)}"
        else "${start.format(dayMonth)} – ${endInclusive.format(dayMonth)}"

    /** "9 – 15 Nov". */
    fun weekRangeShort(start: LocalDate, endInclusive: LocalDate): String =
        if (start.month == endInclusive.month) "${start.dayOfMonth} – ${endInclusive.format(dayMonthShort)}"
        else "${start.format(dayMonthShort)} – ${endInclusive.format(dayMonthShort)}"

    /** 180 -> "3h", 210 -> "3h 30m", 69 -> "1h 09m", 45 -> "45m", 0 -> "0m". */
    fun duration(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        val h = m / 60
        val r = m % 60
        return when {
            h == 0 -> "${r}m"
            r == 0 -> "${h}h"
            else -> "${h}h ${r.toString().padStart(2, '0')}m"
        }
    }

    fun prayer(p: Prayer): String = p.name.lowercase().replaceFirstChar { it.uppercase() }

    /** "Dhuhr", "Dhuhr and Asr", "Dhuhr, Asr and Maghrib". */
    fun prayers(list: List<Prayer>): String = joinAnd(list.map(::prayer))

    fun joinAnd(parts: List<String>): String = when (parts.size) {
        0 -> ""
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }

    fun category(c: CalendarCategory): String = c.calendarName

    /** Minutes between two times, never negative. */
    fun minutesBetween(a: LocalDateTime, b: LocalDateTime): Int =
        java.time.Duration.between(a, b).toMinutes().toInt().coerceAtLeast(0)
}
