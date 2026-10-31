package app.planner.engine.schedule

import app.planner.engine.model.EventRef
import app.planner.engine.model.FixedEvent
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Task
import app.planner.engine.model.TaskKind
import app.planner.engine.model.Tier
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.TreeSet

/** One placed piece of work (a whole task or one chunk), before chunk numbering. */
internal data class Piece(
    val taskId: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val bufferMin: Int,
    val covered: List<PrayerAnchor>,
)

/** A task to place on one day. [earliest] already includes the day start, `earliestStart` and `afterPrayer`. */
internal data class Work(
    val task: Task,
    /** Position in the request's task list: the final tie-break, so equal tasks keep the user's order. */
    val index: Int,
    val earliest: LocalDateTime,
    val minChunk: Int,
    val constrained: Boolean,
) {
    val id: String get() = task.id
}

/** What is busy on a day and how much of each task is still to place. Copied for trial placements. */
internal class DayState(
    busy: List<FixedEvent>,
    pieces: List<Piece> = emptyList(),
    remaining: Map<String, Int> = emptyMap(),
) {
    /** Fixed events, kept placements and placed pieces, sorted by start. */
    val busy: MutableList<FixedEvent> = busy.sortedWith(BUSY_ORDER).toMutableList()
    val pieces: MutableList<Piece> = pieces.toMutableList()
    val remaining: MutableMap<String, Int> = LinkedHashMap(remaining)

    fun copy(): DayState = DayState(busy, pieces, remaining)

    fun assign(other: DayState) {
        busy.clear(); busy.addAll(other.busy)
        pieces.clear(); pieces.addAll(other.pieces)
        remaining.clear(); remaining.putAll(other.remaining)
    }

    fun left(id: String): Int = remaining[id] ?: 0

    fun commit(taskId: String, fit: BlockFit.Fits, workMin: Int, covered: List<PrayerAnchor>) {
        remaining[taskId] = left(taskId) - workMin
        pieces += Piece(taskId, fit.start, fit.end, fit.bufferMin, covered)
        val block = FixedEvent(EventRef(PLACED_CALENDAR, "$taskId#${pieces.size}"), taskId, fit.start, fit.end)
        val at = busy.indexOfFirst { BUSY_ORDER.compare(it, block) > 0 }
        if (at < 0) busy += block else busy.add(at, block)
    }

    companion object {
        const val PLACED_CALENDAR = "planner:placed"
        val BUSY_ORDER: Comparator<FixedEvent> =
            compareBy<FixedEvent>({ it.start }, { it.end }, { it.ref.calendarId }, { it.ref.eventId })
    }
}

/**
 * Places the tasks of one day (newplan 5.4 and 5.5) with the injected [BlockPlacer] doing all prayer arithmetic.
 *
 * Order:
 * 1. Constrained tasks (`earliestStart` or `afterPrayer`) first, earliest constraint first, each at its earliest
 *    feasible time.
 * 2. Then the others by rank: priority (higher first), deep before passive, the `OVERFLOW` kind (the catch-up block)
 *    last. Tasks of equal rank are tried in every order (up to [MAX_PERMUTED] of them) and the best result kept,
 *    by [Score]; the user's order wins ties.
 *
 * Every task tries tier 0 first, then 1, 2 and 3: at each tier the day's gaps are swept from the day start, and a
 * task goes into the first gap where it fits as one block within the tier limit. A task is split only when it fits
 * as one block nowhere within the tier limit, and only in a gap that ends at something busy (a fixed event or
 * another block), never at a tier boundary or the hard cap: splitting there would just put two chunks back to back.
 * A chunk is the largest piece that fits the gap while leaving a remainder of 0 or at least the minimum chunk. Each
 * chunk is its own block, so it runs the prayer rule independently.
 */
internal class DayPlanner(
    val date: LocalDate,
    val windows: TierWindows,
    /** max(wake, notBefore); the hard cap when the day is already over. */
    val dayStart: LocalDateTime,
    /** This day's anchors with absorbed prayers removed. */
    val anchors: List<PrayerAnchor>,
    val slotMin: Int,
    private val placer: BlockPlacer,
) {
    private data class Gap(val start: LocalDateTime, val end: LocalDateTime)

    /**
     * How good one order of an equal-rank group turned out; lower is better, field by field: less overflow, then less
     * time over the soft limit, in extended hours and in the rest zone, then fewer chunks, then an earlier finish.
     * The first order tried (the user's) wins ties, and the search stops at the first order with no overflow, nothing
     * outside tier 0 and no splits.
     */
    data class Score(
        val unplacedMin: Int,
        val overSoftLimitMin: Long,
        val extendedMin: Long,
        val restMin: Long,
        val pieces: Int,
        val lastEnd: LocalDateTime?,
    ) : Comparable<Score> {
        override fun compareTo(other: Score): Int = compareValuesBy(
            this, other,
            { it.unplacedMin }, { it.overSoftLimitMin }, { it.extendedMin }, { it.restMin }, { it.pieces }, { it.lastEnd },
        )
    }

    fun coveredBy(start: LocalDateTime, end: LocalDateTime): List<PrayerAnchor> = anchors.filter {
        val at = it.date.atTime(it.begin)
        !at.isBefore(start) && at.isBefore(end)
    }

    private fun place(state: DayState, from: LocalDateTime, minutes: Int): BlockFit.Fits? =
        placer.place(from, minutes, anchors, state.busy, slotMin) as? BlockFit.Fits

    /**
     * The earliest one-block fit starting at or after [from] and ending by [limit]. The earliest feasible start is
     * always [from], the end of something busy or the end of a prayer slot, so only those are tried.
     */
    fun earliestWhole(state: DayState, from: LocalDateTime, minutes: Int, limit: LocalDateTime): BlockFit.Fits? {
        val candidates = TreeSet<LocalDateTime>()
        candidates += from
        state.busy.forEach { if (it.end.isAfter(from)) candidates += it.end }
        anchors.forEach {
            val slotEnd = it.date.atTime(it.begin).plusMinutes(slotMin.toLong())
            if (slotEnd.isAfter(from)) candidates += slotEnd
        }
        for (c in candidates) {
            if (!c.isBefore(limit)) break
            val fit = place(state, c, minutes) ?: continue
            if (!fit.end.isAfter(limit)) return fit
        }
        return null
    }

    private fun nextGap(state: DayState, cursor: LocalDateTime): Gap? {
        var t = maxOf(cursor, dayStart)
        var moved = true
        while (moved) {
            moved = false
            for (b in state.busy) {
                if (!b.start.isAfter(t) && b.end.isAfter(t)) {
                    t = b.end
                    moved = true
                }
            }
        }
        if (!t.isBefore(windows.hardCap)) return null
        val next = state.busy.asSequence().map { it.start }.filter { it.isAfter(t) }.minOrNull()
        return Gap(t, if (next == null || next.isAfter(windows.hardCap)) windows.hardCap else next)
    }

    /** The largest chunk w (minChunk <= w <= remaining - minChunk) that fits from [from] by [gapEnd]. */
    private fun largestChunk(
        state: DayState,
        from: LocalDateTime,
        gapEnd: LocalDateTime,
        remaining: Int,
        minChunk: Int,
    ): Pair<BlockFit.Fits, Int>? {
        val maxW = remaining - minChunk
        if (maxW < minChunk) return null
        // For a fixed start the end only grows with the duration, so the fitting durations form a prefix.
        fun ok(w: Int): BlockFit.Fits? = place(state, from, w)?.takeIf { !it.end.isAfter(gapEnd) }
        var best = ok(minChunk) ?: return null
        var lo = minChunk
        var hi = maxW
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            val fit = ok(mid)
            if (fit != null) {
                lo = mid
                best = fit
            } else {
                hi = mid - 1
            }
        }
        return best to lo
    }

    /** One pass over the day's gaps, placing [order] (in that order) within [limit]. */
    fun sweep(state: DayState, order: List<Work>, limit: LocalDateTime) {
        var cursor = dayStart
        while (order.any { state.left(it.id) > 0 }) {
            val gap = nextGap(state, cursor) ?: return
            if (!gap.start.isBefore(limit)) return
            val gapEnd = minOf(gap.end, limit)
            val bounded = !gap.end.isAfter(limit) && gap.end.isBefore(windows.hardCap)
            var placed = false
            for (w in order) {
                val left = state.left(w.id)
                if (left <= 0) continue
                val from = maxOf(gap.start, w.earliest)
                if (!from.isBefore(gapEnd)) continue
                val whole = earliestWhole(state, from, left, limit)
                if (whole != null && whole.start.isBefore(gap.end)) {
                    state.commit(w.id, whole, left, coveredBy(whole.start, whole.end))
                    placed = true
                    break
                }
                if (whole != null) continue // it fits as one block later in this tier: do not split it here
                if (w.task.splittable && bounded) {
                    val chunk = largestChunk(state, from, gapEnd, left, w.minChunk)
                    if (chunk != null) {
                        state.commit(w.id, chunk.first, chunk.second, coveredBy(chunk.first.start, chunk.first.end))
                        placed = true
                        break
                    }
                }
            }
            cursor = if (placed) gap.start else gap.end
        }
    }

    /** Tier 0 first, then 1, 2, 3 (or up to [upTo]), until every task in [order] is placed. */
    fun placeByTier(state: DayState, order: List<Work>, upTo: Tier = Tier.OVER_SOFT_LIMIT) {
        for (tier in Tier.entries) {
            if (tier > upTo || order.none { state.left(it.id) > 0 }) return
            sweep(state, order, windows.limit(tier))
        }
    }

    fun score(state: DayState, group: List<Work>): Score {
        val ids = group.map { it.id }.toSet()
        val mine = state.pieces.filter { it.taskId in ids }
        fun mins(tier: Tier) = mine.sumOf { windows.minutesIn(tier, it.start, it.end) }
        return Score(
            unplacedMin = group.sumOf { maxOf(0, state.left(it.id)) },
            overSoftLimitMin = mins(Tier.OVER_SOFT_LIMIT),
            extendedMin = mins(Tier.EXTENDED),
            restMin = mins(Tier.REST),
            pieces = mine.size,
            lastEnd = mine.maxOfOrNull { it.end },
        )
    }

    /** Places [works] (all on this day, remaining minutes already in [state]) in newplan 5.5 order. */
    fun run(state: DayState, works: List<Work>) {
        val (constrained, free) = works.partition { it.constrained }
        constrained.sortedWith(compareBy<Work>({ it.earliest }).then(RANK).thenBy { it.index })
            .forEach { placeByTier(state, listOf(it)) }

        free.sortedWith(RANK.thenBy { it.index })
            .groupBy { rankKey(it.task) }
            .values
            .forEach { group -> placeGroup(state, group) }
    }

    private fun placeGroup(state: DayState, group: List<Work>) {
        val existing = countPieces(state, group)
        val orders = if (group.size <= MAX_PERMUTED) permutations(group) else sequenceOf(group)
        var best: DayState? = null
        var bestScore: Score? = null
        for (order in orders) {
            val trial = state.copy()
            placeByTier(trial, order)
            val sc = score(trial, group)
            if (bestScore == null || sc < bestScore) {
                best = trial
                bestScore = sc
            }
            val ideal = sc.unplacedMin == 0 && sc.overSoftLimitMin == 0L && sc.extendedMin == 0L && sc.restMin == 0L &&
                sc.pieces - existing <= group.size
            if (ideal) break
        }
        best?.let(state::assign)
    }

    private fun countPieces(state: DayState, group: List<Work>): Int {
        val ids = group.map { it.id }.toSet()
        return state.pieces.count { it.taskId in ids }
    }

    companion object {
        /** Up to 5 equal-rank tasks (120 orders) are tried in every order; larger groups keep the user's order. */
        const val MAX_PERMUTED = 5

        private fun kindOrder(kind: TaskKind) = when (kind) {
            TaskKind.DEEP -> 0
            TaskKind.PASSIVE -> 1
            TaskKind.OVERFLOW -> 2
        }

        /** Catch-up (OVERFLOW kind) last, then priority (higher first), then deep before passive. */
        fun rankKey(task: Task): Triple<Int, Int, Int> =
            Triple(if (task.kind == TaskKind.OVERFLOW) 1 else 0, -task.priority, kindOrder(task.kind))

        val RANK: Comparator<Work> = compareBy<Work>(
            { rankKey(it.task).first }, { rankKey(it.task).second }, { rankKey(it.task).third },
        )

        /** All orders of [items], lexicographic by position, so the given order comes first. */
        fun <T> permutations(items: List<T>): Sequence<List<T>> = sequence {
            val idx = IntArray(items.size) { it }
            while (true) {
                yield(idx.map { items[it] })
                var i = idx.size - 2
                while (i >= 0 && idx[i] >= idx[i + 1]) i--
                if (i < 0) break
                var j = idx.size - 1
                while (idx[j] <= idx[i]) j--
                idx[i] = idx[j].also { idx[j] = idx[i] }
                idx.reverse(i + 1, idx.size)
            }
        }
    }
}
