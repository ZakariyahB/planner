package app.planner.readiness

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The tunable numbers of episode and sleep-day building, in one place.
 *
 * - Records closer than [mergeGap] are one episode; the gap between them stays on record as an interruption.
 * - Episodes closer than [periodGap] are one sleep period: a night with a Fajr wake-up in it is one period,
 *   a second sleep that starts hours later is a period of its own.
 * - The main sleep of a day is the biggest period; two main sleeps are at least [mainSpacing] apart
 *   (midpoint to midpoint), so a long second sleep or nap never becomes a day of its own next to a night.
 * - Sleep that starts within [sameDayWithin] of the main wake-up belongs to that day; later sleep goes with
 *   the next main sleep. With a night of up to eight hours this makes the day a window of about 24 hours.
 */
data class SleepRules(
    val mergeGap: Duration = Duration.ofMinutes(30),
    val periodGap: Duration = Duration.ofHours(3),
    val mainSpacing: Duration = Duration.ofHours(14),
    val sameDayWithin: Duration = Duration.ofHours(16),
    /** Writers whose records are trusted: Fitbit, through Health Connect. */
    val trustedOrigins: Set<String> = SleepSources.FITBIT_ORIGINS,
)

/**
 * A run of sleep: one record, or records merged because the gap between them was short. [awake] holds the
 * stretches he was awake inside it, including the short gaps between merged records. Every episode is kept,
 * whatever its length or time of day.
 */
data class SleepEpisode(
    val start: Instant,
    val end: Instant,
    val asleepMin: Int,
    val awake: List<AwakePeriod> = emptyList(),
    val deepMin: Int = 0,
    val remMin: Int = 0,
    /** True when every record in it was typed in by hand. */
    val manual: Boolean = false,
    /** The apps that wrote its records (null for an unknown writer). */
    val origins: Set<String?> = emptySet(),
    val records: Int = 1,
) {
    val inBedMin: Int get() = minutesBetween(start, end)

    fun toBlock(): SleepBlock = SleepBlock(start, end, asleepMin, awake)
}

/**
 * A sleep day: everything from one main sleep up to the next, anchored at the morning he woke from the main
 * sleep ([wakeDate]). So a night, its Fajr wake-up, the second sleep after it and the afternoon nap belong
 * together. [periods] are the day's sleep periods in order; [mainIndex] picks the main sleep among them.
 */
class SleepDay(val wakeDate: LocalDate, val periods: List<List<SleepEpisode>>, val mainIndex: Int) {
    init {
        require(periods.isNotEmpty() && periods.all { it.isNotEmpty() }) { "a sleep day has sleep in it" }
        require(mainIndex in periods.indices) { "the main sleep is one of the day's periods" }
    }

    /** Every episode of the day, in time order. */
    val episodes: List<SleepEpisode> = periods.flatten()

    /** The main sleep: the night, with its Fajr wake-up and second block if there was one. */
    val main: List<SleepEpisode> get() = periods[mainIndex]

    /** All sleep in the day. */
    val totalAsleepMin: Int = episodes.sumOf { it.asleepMin }

    /** The longest episode. */
    val core: SleepEpisode = episodes.maxWith(compareBy<SleepEpisode> { it.asleepMin }.thenByDescending { it.start })
    val coreMin: Int get() = core.asleepMin

    /** Everything that is not the core episode: the second block after Fajr, a late second sleep, naps. */
    val extraMin: Int get() = totalAsleepMin - coreMin

    val lightsOut: Instant get() = main.first().start

    /** The main wake-up: the end of the main sleep. */
    val mainWake: Instant get() = main.last().end

    /** The end of the last sleep of the day so far. */
    val lastEnd: Instant get() = episodes.last().end

    /** From lights-out to the main wake-up, awake time included. */
    val inBedMin: Int get() = minutesBetween(lightsOut, mainWake)

    /**
     * Every time he was awake in the middle of sleep: the stretches inside episodes and the gaps between the
     * episodes of one period (the Fajr wake-up). The long awake day between the night and a nap is not one.
     */
    val interruptions: List<AwakePeriod> =
        (episodes.flatMap { it.awake } + periods.flatMap { p -> p.zipWithNext { a, b -> AwakePeriod(a.end, b.start) } })
            .filter { it.minutes >= SleepGrouping.MIN_INTERRUPTION_MIN }
            .sortedBy { it.start }

    val awakeMin: Int get() = interruptions.sumOf { it.minutes }

    /** The one-night view the older screens use: the main sleep as blocks, the rest as extras. */
    fun toNight(): SleepNight =
        SleepNight(wakeDate, main.map { it.toBlock() }, periods.filterIndexed { i, _ -> i != mainIndex }.flatten().map { it.toBlock() })

    override fun toString(): String = "SleepDay($wakeDate, total=$totalAsleepMin, core=$coreMin, extra=$extraMin, episodes=${episodes.size})"
}

/** Turns records into episodes: every record is kept, short gaps are merged, overlaps are never counted twice. */
object SleepEpisodes {
    fun build(records: List<SleepSession>, rules: SleepRules = SleepRules()): List<SleepEpisode> {
        val out = mutableListOf<SleepEpisode>()
        for (s in records.filter { it.end > it.start }.sortedWith(compareBy<SleepSession> { it.start }.thenByDescending { it.end })) {
            val last = out.lastOrNull()
            if (last == null || Duration.between(last.end, s.start) >= rules.mergeGap) {
                out += SleepEpisode(s.start, s.end, s.asleepMin, s.awake, s.deepMin, s.remMin, s.manual, setOf(s.origin), 1)
            } else {
                out[out.lastIndex] = merge(last, s)
            }
        }
        return out
    }

    /**
     * Adds [s] to [e]. Only the part of [s] after [e]'s end adds sleep: two records of the same stretch (a second
     * writer, or a record typed in over a recorded one) are counted once.
     */
    private fun merge(e: SleepEpisode, s: SleepSession): SleepEpisode {
        val durSec = Duration.between(s.start, s.end).seconds.toDouble()
        val newFrom = maxOf(s.start, e.end)
        val newSec = if (s.end > newFrom) Duration.between(newFrom, s.end).seconds.toDouble() else 0.0
        val share = if (durSec > 0) newSec / durSec else 0.0
        val gap = if (s.start > e.end) listOf(AwakePeriod(e.end, s.start)) else emptyList()
        val awakeAfter = s.awake.filter { it.start >= e.end }
        return SleepEpisode(
            start = e.start,
            end = maxOf(e.end, s.end),
            asleepMin = e.asleepMin + Math.round(s.asleepMin * share).toInt(),
            awake = e.awake + gap + awakeAfter,
            deepMin = e.deepMin + Math.round(s.deepMin * share).toInt(),
            remMin = e.remMin + Math.round(s.remMin * share).toInt(),
            manual = e.manual && s.manual,
            origins = e.origins + s.origin,
            records = e.records + 1,
        )
    }
}

/**
 * Which writer's records count. Fitbit (through Health Connect) is the trusted source; another writer such as
 * Samsung Health is used only for a sleep day Fitbit has nothing for, and then only one other writer, so two
 * apps recording the same night are never added together. Records typed in by hand always count.
 */
object SleepSources {
    /** Fitbit's own app and the Google app it now writes through. */
    val FITBIT_ORIGINS: Set<String> = setOf("com.fitbit.FitbitMobile", "com.google.android.apps.fitness")

    fun select(records: List<SleepSession>, zone: ZoneId, rules: SleepRules = SleepRules()): List<SleepSession> {
        val valid = records.filter { it.end > it.start }
        val days = SleepDays.buildUnfiltered(valid, zone, rules)
        val kept = mutableListOf<SleepSession>()
        val claimed = HashSet<Int>()
        for (day in days) {
            val from = day.episodes.first().start
            val to = day.episodes.last().end
            val inDay = valid.withIndex().filter { (i, r) -> i !in claimed && r.start >= from && r.start <= to }
            claimed += inDay.map { it.index }
            val recorded = inDay.map { it.value }.filter { !it.manual }
            val trusted = recorded.filter { it.origin in rules.trustedOrigins }
            kept += inDay.map { it.value }.filter { it.manual }
            kept += trusted.ifEmpty {
                recorded.groupBy { it.origin }
                    .entries
                    .maxWithOrNull(compareBy<Map.Entry<String?, List<SleepSession>>> { e -> e.value.sumOf { Duration.between(it.start, it.end).seconds } }.thenByDescending { it.key.orEmpty() })
                    ?.value
                    .orEmpty()
            }
        }
        return kept.sortedBy { it.start }
    }
}

/** Builds sleep days from records. */
object SleepDays {
    /** Applies the source rule ([SleepSources.select]) and then groups what is left into sleep days, oldest first. */
    fun build(records: List<SleepSession>, zone: ZoneId, rules: SleepRules = SleepRules()): List<SleepDay> =
        buildUnfiltered(SleepSources.select(records, zone, rules), zone, rules)

    /** Groups every record into sleep days without choosing between writers. */
    internal fun buildUnfiltered(records: List<SleepSession>, zone: ZoneId, rules: SleepRules): List<SleepDay> {
        val periods = periods(SleepEpisodes.build(records, rules), rules)
        if (periods.isEmpty()) return emptyList()
        val mains = mains(periods, rules)

        // A period between two main sleeps belongs to the earlier day when it starts within
        // sameDayWithin of that day's main wake-up (the second sleep, a nap, an evening doze), else to the next.
        val owner = IntArray(periods.size)
        for (i in 0 until mains.first()) owner[i] = mains.first()
        for (k in mains.indices) {
            val a = mains[k]
            val b = mains.getOrNull(k + 1) ?: periods.size
            val cutoff = periods[a].last().end.plus(rules.sameDayWithin)
            var later = false
            for (i in a until b) {
                if (i > a && periods[i].first().start >= cutoff && b < periods.size) later = true
                owner[i] = if (later) mains[k + 1] else a
            }
        }

        val days = mains.map { m ->
            val members = periods.indices.filter { owner[it] == m }
            SleepDay(periods[m].last().end.atZone(zone).toLocalDate(), members.map { periods[it] }, members.indexOf(m))
        }
        return mergeSameDate(days)
    }

    private fun periods(episodes: List<SleepEpisode>, rules: SleepRules): List<List<SleepEpisode>> {
        val out = mutableListOf<MutableList<SleepEpisode>>()
        for (e in episodes) {
            val last = out.lastOrNull()
            if (last != null && Duration.between(last.last().end, e.start) < rules.periodGap) last += e else out += mutableListOf(e)
        }
        return out
    }

    /** The main sleeps, biggest first, each at least [SleepRules.mainSpacing] from a bigger one; returned in time order. */
    private fun mains(periods: List<List<SleepEpisode>>, rules: SleepRules): List<Int> {
        fun asleep(i: Int) = periods[i].sumOf { it.asleepMin }
        fun mid(i: Int): Instant {
            val s = periods[i].first().start
            return s.plus(Duration.between(s, periods[i].last().end).dividedBy(2))
        }
        val chosen = mutableListOf<Int>()
        for (i in periods.indices.sortedWith(compareByDescending<Int> { asleep(it) }.thenBy { it })) {
            if (chosen.none { Duration.between(mid(it), mid(i)).abs() < rules.mainSpacing }) chosen += i
        }
        return chosen.sorted()
    }

    /** Two main sleeps that end on the same date make one day; the bigger stays the main sleep. */
    private fun mergeSameDate(days: List<SleepDay>): List<SleepDay> {
        val out = mutableListOf<SleepDay>()
        for (d in days) {
            val last = out.lastOrNull()
            if (last == null || last.wakeDate != d.wakeDate) {
                out += d
                continue
            }
            val periods = last.periods + d.periods
            val lastMain = last.main.sumOf { it.asleepMin }
            val dMain = d.main.sumOf { it.asleepMin }
            val main = if (dMain > lastMain) last.periods.size + d.mainIndex else last.mainIndex
            out[out.lastIndex] = SleepDay(d.wakeDate, periods, main)
        }
        return out
    }
}

/** Minutes asleep between [from] and [to], counting the overlapping share of each episode. */
fun asleepBetween(episodes: List<SleepEpisode>, from: Instant, to: Instant): Int {
    var total = 0.0
    for (e in episodes) {
        val s = maxOf(e.start, from)
        val t = minOf(e.end, to)
        if (t <= s) continue
        val dur = Duration.between(e.start, e.end).seconds.toDouble()
        total += e.asleepMin * Duration.between(s, t).seconds / dur
    }
    return Math.round(total).toInt()
}
