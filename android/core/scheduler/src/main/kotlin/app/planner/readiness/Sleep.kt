package app.planner.readiness

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A stretch inside a sleep record when he was awake: the night's interruptions. */
data class AwakePeriod(val start: Instant, val end: Instant) {
    val minutes: Int get() = minutesBetween(start, end)
}

/** One sleep record from Health Connect (or typed in by hand). [awake] are the interruptions inside it. */
data class SleepSession(
    val start: Instant,
    val end: Instant,
    val asleepMin: Int,
    val deepMin: Int = 0,
    val remMin: Int = 0,
    val manual: Boolean = false,
    val awake: List<AwakePeriod> = emptyList(),
)

data class SleepBlock(val start: Instant, val end: Instant, val asleepMin: Int, val awake: List<AwakePeriod> = emptyList())

/** The night that ends on the morning of [date]; usually one block, or two when he wakes at Fajr and goes back to bed. */
data class SleepNight(val date: LocalDate, val blocks: List<SleepBlock>) {
    val asleepMin: Int get() = blocks.sumOf { it.asleepMin }
    val lightsOut: Instant get() = blocks.first().start
    val firstBlockEnd: Instant get() = blocks.first().end
    val lastEnd: Instant get() = blocks.last().end

    /** From lights-out to the final wake-up, awake time included. */
    val inBedMin: Int get() = minutesBetween(lightsOut, lastEnd)

    /** Every time he was awake between lights-out and the final wake-up, in order. */
    val interruptions: List<AwakePeriod>
        get() = (blocks.flatMap { it.awake } + blocks.zipWithNext { a, b -> AwakePeriod(a.end, b.start) })
            .filter { it.minutes >= SleepGrouping.MIN_INTERRUPTION_MIN }
            .sortedBy { it.start }

    val awakeMin: Int get() = interruptions.sumOf { it.minutes }
}

internal fun minutesBetween(start: Instant, end: Instant): Int = ((Duration.between(start, end).seconds + 30) / 60).toInt()

/** What Health Connect says he was doing for a stretch of a sleep record. */
enum class StageKind { AWAKE, LIGHT, DEEP, REM, ASLEEP, UNKNOWN }

data class SleepStage(val kind: StageKind, val start: Instant, val end: Instant)

/**
 * One Health Connect record as a session. The sleep total is the time in bed minus the stretches he was awake,
 * so a stage the watch labels oddly (or leaves out) still counts as sleep instead of vanishing, and the
 * seconds are summed before rounding: Fitbit writes many short stages and rounding each one to the minute
 * would lose a large share of the night.
 */
fun sleepSessionFromStages(start: Instant, end: Instant, stages: List<SleepStage>): SleepSession {
    val inBedSec = Duration.between(start, end).seconds.coerceAtLeast(0)
    if (stages.isEmpty()) return SleepSession(start, end, ((inBedSec + 30) / 60).toInt())

    val clipped = stages.mapNotNull { st ->
        val s = maxOf(st.start, start)
        val e = minOf(st.end, end)
        if (e > s) SleepStage(st.kind, s, e) else null
    }
    val awake = mergeAwake(clipped.filter { it.kind == StageKind.AWAKE }.map { AwakePeriod(it.start, it.end) })
        .filter { Duration.between(it.start, it.end).seconds >= SleepGrouping.MIN_INTERRUPTION_MIN * 60L }
    val awakeSec = awake.sumOf { Duration.between(it.start, it.end).seconds }
    fun minutes(kind: StageKind) = ((clipped.filter { it.kind == kind }.sumOf { Duration.between(it.start, it.end).seconds } + 30) / 60).toInt()
    return SleepSession(
        start = start,
        end = end,
        asleepMin = (((inBedSec - awakeSec).coerceAtLeast(0) + 30) / 60).toInt(),
        deepMin = minutes(StageKind.DEEP),
        remMin = minutes(StageKind.REM),
        awake = awake,
    )
}

private fun mergeAwake(periods: List<AwakePeriod>): List<AwakePeriod> {
    val out = mutableListOf<AwakePeriod>()
    for (p in periods.sortedBy { it.start }) {
        val last = out.lastOrNull()
        if (last != null && p.start <= last.end) out[out.lastIndex] = AwakePeriod(last.start, maxOf(last.end, p.end)) else out += p
    }
    return out
}

object SleepGrouping {
    /** Awake stretches shorter than this are not interruptions: the watch logs half-minute stirrings all night. */
    const val MIN_INTERRUPTION_MIN = 1

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
                // Two apps can record the same night; the overlap must not be counted twice.
                val overlap = if (s.start < last.end) minutesBetween(s.start, minOf(last.end, s.end)) else 0
                val gap = if (s.start > last.end) listOf(AwakePeriod(last.end, s.start)) else emptyList()
                blocks[blocks.lastIndex] = SleepBlock(
                    last.start,
                    maxOf(last.end, s.end),
                    last.asleepMin + (s.asleepMin - minOf(overlap, s.asleepMin)),
                    last.awake + gap + s.awake,
                )
            } else {
                blocks += SleepBlock(s.start, s.end, s.asleepMin, s.awake)
            }
        }
        return blocks
    }
}

enum class SleepPhaseKind { ASLEEP, AWAKE }

/** One stretch of a night, in local time, for showing when he slept and when he was up. */
data class SleepPhase(val kind: SleepPhaseKind, val start: LocalTime, val end: LocalTime) {
    fun label(): String = "${kind.name.lowercase()} ${FORMAT.format(start)}–${FORMAT.format(end)}"

    private companion object {
        val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}

/** Asleep blocks with the awake gaps between them, e.g. asleep 00:32–05:14, awake 05:14–07:04, asleep 07:04–09:14. */
fun SleepNight.phases(zone: ZoneId): List<SleepPhase> =
    blocks.flatMapIndexed { i, b ->
        val asleep = SleepPhase(SleepPhaseKind.ASLEEP, b.start.atZone(zone).toLocalTime(), b.end.atZone(zone).toLocalTime())
        val next = blocks.getOrNull(i + 1)
        if (next == null) {
            listOf(asleep)
        } else {
            listOf(asleep, SleepPhase(SleepPhaseKind.AWAKE, asleep.end, next.start.atZone(zone).toLocalTime()))
        }
    }

/** "05:14–07:04 (1h 50m)" for each time he was awake during the night. */
fun SleepNight.interruptionLabels(zone: ZoneId): List<String> =
    interruptions.map { "${SleepText.clock(it.start, zone)}–${SleepText.clock(it.end, zone)} (${SleepText.duration(it.minutes)})" }

/** One night in a line: "00:20 to 09:41 · slept 8h 16m of 9h 21m in bed · 3 interruptions, 1h 05m awake". */
fun SleepNight.summaryLine(zone: ZoneId): String {
    val count = interruptions.size
    val breaks = when (count) {
        0 -> "no interruptions"
        1 -> "1 interruption, ${SleepText.duration(awakeMin)} awake"
        else -> "$count interruptions, ${SleepText.duration(awakeMin)} awake"
    }
    return "${SleepText.clock(lightsOut, zone)} to ${SleepText.clock(lastEnd, zone)} · slept ${SleepText.duration(asleepMin)} of ${SleepText.duration(inBedMin)} in bed · $breaks"
}

object SleepText {
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun clock(instant: Instant, zone: ZoneId): String = CLOCK.format(instant.atZone(zone))

    fun duration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }
}
