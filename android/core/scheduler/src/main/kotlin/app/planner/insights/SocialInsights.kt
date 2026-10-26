package app.planner.insights

import app.planner.social.SocialNudges
import java.time.LocalDate

/** One day of social-media use and that day's budget, in minutes. */
data class SocialPoint(val date: LocalDate, val minutes: Int, val budgetMin: Int)

data class SocialRow(val date: LocalDate, val weekday: String, val minutes: Int, val budgetMin: Int, val over: Boolean, val label: String)

object SocialInsights {
    fun rows(points: List<SocialPoint>): List<SocialRow> = points.map {
        SocialRow(it.date, SleepInsights.weekday(it.date.dayOfWeek), it.minutes, it.budgetMin, it.minutes > it.budgetMin, SocialNudges.duration(it.minutes))
    }

    fun daysOver(rows: List<SocialRow>): Int = rows.count { it.over }

    /** The scale's top: the largest figure rounded up to a whole hour, two hours when there is nothing yet. */
    fun axisMaxMin(rows: List<SocialRow>): Int {
        val largest = rows.maxOfOrNull { maxOf(it.minutes, it.budgetMin) } ?: return 120
        return (largest + 59) / 60 * 60
    }

    fun fraction(minutes: Int, axisMaxMin: Int): Double = (minutes.toDouble() / axisMaxMin).coerceIn(0.0, 1.0)
}
