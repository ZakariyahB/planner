package app.planner.readiness

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One sleep day in a few numbers, for the assistant's context. */
data class SleepDayLine(
    val wakeDate: LocalDate,
    val totalMin: Int,
    val coreMin: Int,
    val extraMin: Int,
    val episodes: Int,
    val interruptions: Int,
    val awakeMin: Int,
    /** "00:32" and "09:14": lights-out and main wake-up, local time. */
    val lightsOut: String,
    val wake: String,
)

/**
 * What the assistant needs to coach on sleep, without the details of records and episodes. Rolling totals end
 * at [now]; [debt7Min] is the shortfall against [needMin] over the last seven sleep days.
 */
data class SleepSummary(
    val now: Instant,
    val needMin: Int,
    val asleep24hMin: Int,
    val asleep72hMin: Int,
    val debt7Min: Int,
    /** Average total sleep of the last seven sleep days on record; null with none. */
    val average7Min: Int?,
    /** The last seven sleep days, oldest first. */
    val days: List<SleepDayLine>,
)

object SleepSummaries {
    /**
     * Summarises [days] (from [SleepDays.build]) as of [now]. Pure: call it with the days the app holds, then
     * pass [text] (or the fields) to the model.
     */
    fun summarise(days: List<SleepDay>, now: Instant, zone: ZoneId, needMin: Int = ReadinessWeights.NEED_MIN): SleepSummary {
        val ended = days.filter { it.mainWake <= now }
        val episodes = days.flatMap { it.episodes }
        val recent = ended.takeLast(7)
        val today = now.atZone(zone).toLocalDate()
        return SleepSummary(
            now = now,
            needMin = needMin,
            asleep24hMin = asleepBetween(episodes, now.minus(Duration.ofHours(24)), now),
            asleep72hMin = asleepBetween(episodes, now.minus(Duration.ofHours(72)), now),
            debt7Min = ReadinessEngine.sleepDebtMin(ended, today, needMin),
            average7Min = recent.takeIf { it.isNotEmpty() }?.let { r -> r.sumOf { it.totalAsleepMin } / r.size },
            days = recent.map { d ->
                SleepDayLine(
                    wakeDate = d.wakeDate,
                    totalMin = d.totalAsleepMin,
                    coreMin = d.coreMin,
                    extraMin = d.extraMin,
                    episodes = d.episodes.size,
                    interruptions = d.interruptions.size,
                    awakeMin = d.awakeMin,
                    lightsOut = SleepText.clock(d.lightsOut, zone),
                    wake = SleepText.clock(d.mainWake, zone),
                )
            },
        )
    }

    /** A few plain lines, e.g. "Last 24h: 7h 5m asleep (need 7h). Last 72h: 19h 40m." then one line per day. */
    fun text(summary: SleepSummary): String = buildString {
        val d = SleepText::duration
        append("Last 24h: ${d(summary.asleep24hMin)} asleep (need ${d(summary.needMin)}). Last 72h: ${d(summary.asleep72hMin)}.")
        append(" 7-day debt: ${d(summary.debt7Min)}.")
        summary.average7Min?.let { append(" 7-day average: ${d(it)}.") }
        for (day in summary.days) {
            append("\n${day.wakeDate}: ${d(day.totalMin)} (core ${d(day.coreMin)}, extra ${d(day.extraMin)}), ")
            append("${day.lightsOut} to ${day.wake}, ")
            append(
                when (day.interruptions) {
                    0 -> "no interruptions"
                    1 -> "1 interruption, ${d(day.awakeMin)} awake"
                    else -> "${day.interruptions} interruptions, ${d(day.awakeMin)} awake"
                },
            )
        }
    }
}
