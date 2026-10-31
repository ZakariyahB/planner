package app.planner.engine.schedule

import app.planner.engine.model.Prayer
import app.planner.engine.model.TaskStatus
import app.planner.engine.prayer.DefaultPrayerModel
import java.time.Duration
import java.time.LocalDateTime

/** The newplan 13.2 invariants, plus a few bookkeeping ones, checked against any plan. Returns the violations. */
object PlanInvariants {
    fun work(start: LocalDateTime, end: LocalDateTime, buffer: Int) = Duration.between(start, end).toMinutes().toInt() - buffer

    fun check(request: PlanRequest, result: PlanResult): List<String> {
        val v = ArrayList<String>()
        val s = request.settings
        val slot = s.prayerSlotMin.toLong()
        val fixed = request.fixed.filter { it.end.isAfter(it.start) }
        val anchors = request.anchors.filter { it.prayer != Prayer.FAJR }
        val absorbed = DefaultPrayerModel.absorb(anchors, fixed)
        val tasks = request.tasks.associateBy { it.id }
        val ps = result.placements
        val keepSet = request.keep.map { it.taskId to it.start }.toSet()
        val fresh = ps.filter { (it.taskId to it.start) !in keepSet }

        // No two blocks overlap each other or any fixed event.
        for (i in ps.indices) for (j in i + 1 until ps.size) {
            val a = ps[i]
            val b = ps[j]
            if (a.start.isBefore(b.end) && b.start.isBefore(a.end)) v += "overlap: $a and $b"
        }
        for (p in fresh) for (f in fixed) {
            if (p.start.isBefore(f.end) && f.start.isBefore(p.end)) v += "overlaps fixed ${f.title}: $p"
        }

        for (p in fresh) {
            val t = tasks[p.taskId]
            if (t == null) {
                v += "unknown task: $p"
                continue
            }
            val day = p.start.toLocalDate()
            val windows = TierWindows.of(day, s, anchors.filter { it.date == day })
            // No block starts inside a prayer slot.
            absorbed.forEach { a ->
                val at = a.date.atTime(a.begin)
                if (!p.start.isBefore(at) && p.start.isBefore(at.plusMinutes(slot))) v += "starts inside ${a.prayer} slot: $p"
            }
            // end - start == work + slot * (anchors in [start, end)).
            val covered = absorbed.filter { val at = it.date.atTime(it.begin); !at.isBefore(p.start) && at.isBefore(p.end) }
            if (p.prayerBufferMin.toLong() != slot * covered.size) v += "buffer ${p.prayerBufferMin} != $slot x ${covered.size}: $p"
            if (p.coveredPrayers.sorted() != covered.map { it.prayer }.sorted()) v += "covered ${p.coveredPrayers} != ${covered.map { it.prayer }}: $p"
            if (p.interpolatedPrayer != covered.any { it.interpolated }) v += "interpolated flag wrong: $p"
            // The constraints.
            t.earliestStart?.let { if (p.start.isBefore(day.atTime(it))) v += "before earliestStart $it: $p" }
            t.afterPrayer?.let { prayer ->
                val a = anchors.firstOrNull { it.date == day && it.prayer == prayer }
                if (a == null) v += "placed with no $prayer anchor: $p"
                else if (p.start.isBefore(day.atTime(a.begin).plusMinutes(slot))) v += "before $prayer slot end: $p"
            }
            // Inside the work window; nothing after the hard cap.
            if (p.end.isAfter(windows.hardCap)) v += "after hard cap: $p"
            if (p.start.isBefore(windows.wake)) v += "before wake: $p"
            request.notBefore?.let { if (p.start.isBefore(it)) v += "before notBefore $it: $p" }
            if (p.tier != windows.tierOf(p.start, p.end)) v += "tier ${p.tier} != ${windows.tierOf(p.start, p.end)}: $p"
            if (t.assignedDay != null && t.assignedDay != day) v += "not on assigned day ${t.assignedDay}: $p"
            if (work(p.start, p.end, p.prayerBufferMin) <= 0) v += "empty block: $p"
        }

        val minChunk = { id: String -> maxOf(tasks.getValue(id).minChunkMin, s.minChunkMin) }
        for ((id, chunks) in ps.groupBy { it.taskId }) {
            val t = tasks[id] ?: continue
            val sorted = chunks.sortedBy { it.start }
            if (sorted.map { it.chunkIndex } != (1..sorted.size).toList()) v += "chunk numbering: $sorted"
            if (sorted.any { it.chunkCount != sorted.size }) v += "chunk count: $sorted"
            if (!t.splittable && sorted.size > 1 && request.keep.none { it.taskId == id }) v += "unsplittable task split: $sorted"
            val freshChunks = sorted.filter { (it.taskId to it.start) !in keepSet }
            // Every chunk is >= the minimum unless the task (or what is left of it after kept work) is shorter.
            if (sorted.size > 1) freshChunks.forEach {
                val w = work(it.start, it.end, it.prayerBufferMin)
                val leftAfterKeep = t.durationMin - request.keep.filter { k -> k.taskId == id }.sumOf { k -> work(k.start, k.end, k.prayerBufferMin) }
                if (w < minChunk(id) && w != leftAfterKeep) v += "chunk under ${minChunk(id)} min: $it"
            }
        }

        // Every minute of every planned task is either placed or reported as overflow.
        for (t in request.tasks.distinctBy { it.id }) {
            if (t.status == TaskStatus.DONE || t.status == TaskStatus.SKIPPED || t.durationMin <= 0) continue
            if (t.assignedDay != null && t.assignedDay !in request.days) continue
            val placed = ps.filter { it.taskId == t.id }.sumOf { work(it.start, it.end, it.prayerBufferMin) }
            val over = result.overflow.filter { it.taskId == t.id }.sumOf { it.remainingMin }
            if (placed + over != t.durationMin) v += "${t.id}: placed $placed + overflow $over != ${t.durationMin}"
        }
        // Overflow never goes to the next day (only to the catch-up date, or nowhere).
        val catchUp = request.catchUpDate ?: request.days.firstOrNull()
            ?.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            ?.with(java.time.temporal.TemporalAdjusters.nextOrSame(s.catchUpDay))
        result.overflow.forEach { o ->
            if (o.proposedDate != null && o.proposedDate != catchUp) v += "overflow not to the catch-up day: $o"
            val from = tasks[o.taskId]?.assignedDay
            if (from != null && o.proposedDate != null && !o.proposedDate.isAfter(from)) v += "overflow proposed on or before its own day: $o"
        }
        return v
    }
}

/** SplitMix64: a tiny deterministic generator, so the property tests need no new dependency and replay by seed. */
class SeededRandom(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += -0x61c8864680b583ebL
        var z = state
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    /** Uniform in [from, until). */
    fun int(from: Int, until: Int): Int {
        require(until > from)
        return from + java.lang.Long.remainderUnsigned(nextLong(), (until - from).toLong()).toInt()
    }

    fun chance(percent: Int): Boolean = int(0, 100) < percent

    fun <T> pick(items: List<T>): T = items[int(0, items.size)]
}
