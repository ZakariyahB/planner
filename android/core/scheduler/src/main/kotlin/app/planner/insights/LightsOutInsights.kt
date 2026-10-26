package app.planner.insights

import app.planner.readiness.SleepNight
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs

/** When he went to bed against the 00:15 target: positive is late, negative is early. */
data class LightsOutRow(val date: LocalDate, val weekday: String, val minutesAfterTarget: Int)

object LightsOutInsights {
    private val TARGET: LocalTime = LocalTime.of(0, 15)
    private const val CAP_MIN = 120

    fun rows(nights: List<SleepNight>, zone: ZoneId, count: Int = 7): List<LightsOutRow> =
        nights.sortedBy { it.date }.takeLast(count).map { night ->
            LightsOutRow(night.date, SleepInsights.weekday(night.date.dayOfWeek), minutesAfterTarget(night.lightsOut.atZone(zone).toLocalTime()))
        }

    /** Minutes after the target, counted from the evening so 23:50 is 25 minutes early, not 23 hours late. */
    fun minutesAfterTarget(lightsOut: LocalTime): Int {
        fun sinceEvening(t: LocalTime) = (t.hour * 60 + t.minute - 18 * 60 + 24 * 60) % (24 * 60)
        return sinceEvening(lightsOut) - sinceEvening(TARGET)
    }

    fun label(minutes: Int): String = when {
        minutes == 0 -> "on time"
        minutes > 0 -> "+$minutes min"
        else -> "−${abs(minutes)} min"
    }

    /** Length of a bar from 0 to 1: two hours either side of the target fills it. */
    fun barFraction(minutes: Int): Double = (abs(minutes).toDouble() / CAP_MIN).coerceAtMost(1.0)

    fun averageMinutesAfterTarget(rows: List<LightsOutRow>): Int? =
        rows.takeIf { it.isNotEmpty() }?.let { list -> list.sumOf { it.minutesAfterTarget } / list.size }
}
