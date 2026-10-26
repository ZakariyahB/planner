package app.planner.social

import java.time.LocalDate

/** Android keeps about a week of usage events, so days before usage access was checked can still be read. */
object UsageBackfill {
    const val LOOKBACK_DAYS = 7

    /**
     * Past days, oldest first, to read from the phone's history: those within [LOOKBACK_DAYS] with no saved
     * row. Saved days are not read again, as their older events may have been dropped since.
     */
    fun daysToFill(today: LocalDate, saved: Set<LocalDate>): List<LocalDate> =
        (LOOKBACK_DAYS downTo 1).map { today.minusDays(it.toLong()) }.filter { it !in saved }
}
