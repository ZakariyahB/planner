package app.planner.insights

import app.planner.readiness.SleepNight
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** A stretch of sleep as fractions (0 to 1) along the track that runs from 21:00 to 12:00. */
data class SleepBar(val start: Double, val end: Double)

data class SleepRow(val date: LocalDate, val weekday: String, val bars: List<SleepBar>, val asleepMin: Int)

data class AxisLabel(val label: String, val position: Double)

object SleepInsights {
    private const val SPAN_MIN = 15 * 60

    /** Where the 00:15 lights-out target falls on the track. */
    const val TARGET_POSITION: Double = 195.0 / SPAN_MIN

    val AXIS = listOf(
        AxisLabel("21:00", 0.0),
        AxisLabel("00:00", 0.2),
        AxisLabel("03:00", 0.4),
        AxisLabel("06:00", 0.6),
        AxisLabel("09:00", 0.8),
        AxisLabel("12:00", 1.0),
    )

    /** The last [count] nights, oldest first, each laid out on the 21:00 to 12:00 track. */
    fun rows(nights: List<SleepNight>, zone: ZoneId, count: Int = 7): List<SleepRow> =
        nights.sortedBy { it.date }.takeLast(count).map { night ->
            val anchor = night.date.minusDays(1).atTime(21, 0).atZone(zone).toInstant()
            fun position(instant: java.time.Instant) =
                (Duration.between(anchor, instant).toMinutes().toDouble() / SPAN_MIN).coerceIn(0.0, 1.0)
            SleepRow(
                date = night.date,
                weekday = weekday(night.date.dayOfWeek),
                bars = night.blocks.map { SleepBar(position(it.start), position(it.end)) },
                asleepMin = night.asleepMin,
            )
        }

    fun averageAsleepMin(rows: List<SleepRow>): Int? =
        rows.takeIf { it.isNotEmpty() }?.let { list -> list.sumOf { it.asleepMin } / list.size }

    fun weekday(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.UK)
}
