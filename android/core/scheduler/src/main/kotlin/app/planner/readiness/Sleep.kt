package app.planner.readiness

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Why he was awake during a night, when he has said. */
enum class InterruptionReason { FAJR, TOILET, COULD_NOT_SLEEP, OTHER }

/**
 * A stretch when he was awake: inside a sleep record, or the gap between two records of one night (the Fajr
 * wake-up). [reason] is null until he tags it.
 */
data class AwakePeriod(val start: Instant, val end: Instant, val reason: InterruptionReason? = null) {
    val minutes: Int get() = minutesBetween(start, end)
}

/**
 * One sleep record from Health Connect (or typed in by hand). [awake] are the interruptions inside it.
 * [origin] is the package of the app that wrote it (Health Connect's `dataOrigin`), null when not known
 * (typed in, or stored before the origin was kept).
 */
data class SleepSession(
    val start: Instant,
    val end: Instant,
    val asleepMin: Int,
    val deepMin: Int = 0,
    val remMin: Int = 0,
    val manual: Boolean = false,
    val awake: List<AwakePeriod> = emptyList(),
    val origin: String? = null,
)

data class SleepBlock(val start: Instant, val end: Instant, val asleepMin: Int, val awake: List<AwakePeriod> = emptyList())

/**
 * The older one-night view of a [SleepDay], kept for the screens and insights that show a night.
 * [blocks] are the main sleep (one block, or two when he wakes at Fajr and goes back to bed); [extra] is the
 * rest of the sleep day: a second sleep later in the morning, naps. [date] is the morning he woke up.
 */
data class SleepNight(val date: LocalDate, val blocks: List<SleepBlock>, val extra: List<SleepBlock> = emptyList()) {
    /** All sleep of the sleep day, the main sleep and anything after it. */
    val asleepMin: Int get() = nightAsleepMin + extra.sumOf { it.asleepMin }

    /** Sleep in the main blocks only. */
    val nightAsleepMin: Int get() = blocks.sumOf { it.asleepMin }
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

    /**
     * The sleep days on record as one-night views, oldest first. Every record counts, whatever its length or
     * time of day; see [SleepDays.build] for how records become episodes and days.
     */
    fun group(sessions: List<SleepSession>, zone: ZoneId): List<SleepNight> =
        SleepDays.build(sessions, zone).map { it.toNight() }
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
    val later = if (extra.isEmpty()) "" else " · plus ${SleepText.duration(asleepMin - nightAsleepMin)} later"
    return "${SleepText.clock(lightsOut, zone)} to ${SleepText.clock(lastEnd, zone)} · slept ${SleepText.duration(nightAsleepMin)} of ${SleepText.duration(inBedMin)} in bed · $breaks$later"
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
