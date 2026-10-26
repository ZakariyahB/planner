package app.planner.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

object Weeks {
    fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** The dates of [days] in the week starting [weekMonday], in date order. */
    fun dates(weekMonday: LocalDate, days: Set<DayOfWeek>): List<LocalDate> =
        days.sortedBy { it.value }.map { weekMonday.plusDays((it.value - DayOfWeek.MONDAY.value).toLong()) }
}
