package app.planner.readiness

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** One sleep record from Health Connect (or typed in by hand). */
data class SleepSession(
    val start: Instant,
    val end: Instant,
    val asleepMin: Int,
    val deepMin: Int = 0,
    val remMin: Int = 0,
    val manual: Boolean = false,
)

data class SleepBlock(val start: Instant, val end: Instant, val asleepMin: Int)

/** The night that ends on the morning of [date]; usually one block, or two when he wakes at Fajr and goes back to bed. */
data class SleepNight(val date: LocalDate, val blocks: List<SleepBlock>) {
    val asleepMin: Int get() = blocks.sumOf { it.asleepMin }
    val lightsOut: Instant get() = blocks.first().start
    val firstBlockEnd: Instant get() = blocks.first().end
    val lastEnd: Instant get() = blocks.last().end
}

object SleepGrouping {
    private val MERGE_GAP: Duration = Duration.ofMinutes(30)
    private val MIN_SESSION: Duration = Duration.ofMinutes(20)
    private val NAP_FROM: LocalTime = LocalTime.of(11, 0)
    private val EVENING_FROM: LocalTime = LocalTime.of(19, 0)

    /**
     * Groups sessions into nights. A session starting from 19:00 belongs to the next morning's night;
     * sessions starting 11:00–19:00 are naps and are ignored; sessions under 20 minutes are noise.
     * Blocks less than 30 minutes apart are merged (a brief wake-up is not a second sleep).
     */
    fun group(sessions: List<SleepSession>, zone: ZoneId): List<SleepNight> =
        sessions
            .filter { Duration.between(it.start, it.end) >= MIN_SESSION }
            .mapNotNull { s ->
                val local = s.start.atZone(zone)
                val t = local.toLocalTime()
                when {
                    t >= NAP_FROM && t < EVENING_FROM -> null
                    t >= EVENING_FROM -> local.toLocalDate().plusDays(1) to s
                    else -> local.toLocalDate() to s
                }
            }
            .groupBy({ it.first }, { it.second })
            .map { (date, list) -> SleepNight(date, mergeBlocks(list.sortedBy { it.start })) }
            .sortedBy { it.date }

    private fun mergeBlocks(sorted: List<SleepSession>): List<SleepBlock> {
        val blocks = mutableListOf<SleepBlock>()
        for (s in sorted) {
            val last = blocks.lastOrNull()
            if (last != null && Duration.between(last.end, s.start) < MERGE_GAP) {
                blocks[blocks.lastIndex] = SleepBlock(last.start, maxOf(last.end, s.end), last.asleepMin + s.asleepMin)
            } else {
                blocks += SleepBlock(s.start, s.end, s.asleepMin)
            }
        }
        return blocks
    }
}
