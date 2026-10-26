package app.planner.scheduler

import app.planner.calendar.FixedEvent
import app.planner.model.Prayer
import app.planner.model.Task
import app.planner.readiness.ReadinessBand
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Everything the scheduler needs to know about one day, besides the tasks. */
data class DayContext(
    val date: LocalDate,
    val zone: ZoneId,
    val fixed: List<FixedEvent> = emptyList(),
    val prayers: Map<Prayer, LocalTime> = emptyMap(),
    /** Task blocks that stay where they are (locked, user-placed or already started); planned around. */
    val existing: List<Block> = emptyList(),
    /** Nothing is placed before this time (used when replanning today). */
    val notBefore: LocalTime? = null,
)

data class DayPlan(val date: LocalDate, val blocks: List<Block>, val unplaced: List<Task>)

data class WeekPlan(
    val blocks: List<Block>,
    /** Tasks that missed their due day and were moved to the Sunday catch-up (placed there or not). */
    val overflow: List<Task>,
    /** Tasks that fit nowhere in the range, including overflow that did not fit on Sunday either. */
    val unscheduled: List<Task>,
    /** Tasks the readiness band suggests dropping from the first day; needs approval unless already applied. */
    val loadCut: List<Task>,
    /** More overflow than the Sunday catch-up allowance. */
    val catchUpExceeded: Boolean,
    /** Tasks whose chosen start time couldn't be honoured; they were planned normally instead. */
    val pinConflicts: List<PinConflict> = emptyList(),
)

data class PinConflict(val task: Task, val reason: String)

internal class DayLayout(ctx: DayContext, private val rules: DayRules, honourNotBefore: Boolean) {
    val fixedSlots: List<Slot> = ctx.fixed.mapNotNull { e ->
        val s = minuteOn(ctx, e.start)
        val en = minuteOn(ctx, e.end)
        if (en > s) Slot(s, en) else null
    }
    val prayerSlots: List<Slot> = ctx.prayers
        .filterKeys { it != Prayer.FAJR }
        .values
        .map { Slot(it.toMinuteOfDay(), it.toMinuteOfDay() + rules.prayerBufferMin) }
    val windowStart: Int =
        maxOf(rules.wake.toMinuteOfDay(), if (honourNotBefore) ctx.notBefore?.toMinuteOfDay() ?: 0 else 0)
    val windowEnd: Int = rules.workEndFor(ctx.date.dayOfWeek, fixedSlots.sumOf { it.length }).toMinuteOfDay()

    /** Time a new block may not use: fixed events and blocks plus their trailing buffer, and prayers. */
    fun busy(existing: List<Block>): List<Slot> =
        fixedSlots.map { Slot(it.start, it.end + rules.blockBufferMin) } +
            prayerSlots +
            existing.map { Slot(it.start.toMinuteOfDay(), it.end.toMinuteOfDay() + rules.blockBufferMin) }

    private fun minuteOn(ctx: DayContext, instant: Instant): Int {
        val local = instant.atZone(ctx.zone)
        return when {
            local.toLocalDate() < ctx.date -> 0
            local.toLocalDate() > ctx.date -> 24 * 60
            else -> local.toLocalTime().toMinuteOfDay()
        }
    }
}

class Scheduler(val rules: DayRules = DayRules()) {

    /** Earliest-deadline-first, then priority, then longest first; ids make it deterministic. */
    private val order = compareBy<Task> { it.dueAt ?: Instant.MAX }
        .thenByDescending { it.priority }
        .thenByDescending { it.estMin }
        .thenBy { it.id }

    /**
     * Places [tasks] on one day. Tasks are never split. With [hardestFirst] the longest task claims
     * the earliest slot (used on amber/red days), otherwise earliest-deadline-first.
     */
    fun plan(ctx: DayContext, tasks: List<Task>, hardestFirst: Boolean = false): DayPlan {
        val layout = DayLayout(ctx, rules, honourNotBefore = true)
        val window = Slot(layout.windowStart, layout.windowEnd)
        var placedMinutes = ctx.existing.sumOf { it.minutes }
        val blocks = mutableListOf<Block>()
        val unplaced = mutableListOf<Task>()

        val sorted = tasks.sortedWith(order).let { list ->
            if (hardestFirst && list.isNotEmpty()) {
                val hardest = list.maxWith(compareBy<Task> { it.estMin }.thenBy { it.priority }.thenByDescending { it.id })
                listOf(hardest) + list.filter { it !== hardest }
            } else {
                list
            }
        }

        for (task in sorted) {
            val lowerBound = lowerBound(task, ctx)
            if (lowerBound == null || placedMinutes + task.estMin > rules.maxTaskMinutesPerDay) {
                unplaced += task
                continue
            }
            val free = subtract(window, layout.busy(ctx.existing + blocks))
            val start = free.firstNotNullOfOrNull { f ->
                val s = ceil5(maxOf(f.start, lowerBound))
                if (s + task.estMin <= f.end) s else null
            }
            if (start == null) {
                unplaced += task
            } else {
                blocks += Block(
                    taskId = task.id,
                    date = ctx.date,
                    start = start.toLocalTime(),
                    end = (start + task.estMin).toLocalTime(),
                )
                placedMinutes += task.estMin
            }
        }
        return DayPlan(ctx.date, blocks.sortedBy { it.start }, unplaced)
    }

    /**
     * Plans [days] in order (the first is "today"). A task due on or before a day that does not fit
     * moves to the Sunday catch-up, never to the next day. [band] applies to the first day only.
     * With [applyLoadCut] the suggested cut is carried out; otherwise it is only reported in [WeekPlan.loadCut].
     */
    fun planWeek(
        days: List<DayContext>,
        tasks: List<Task>,
        now: Instant,
        band: ReadinessBand = ReadinessBand.GREEN,
        applyLoadCut: Boolean = false,
    ): WeekPlan {
        val alreadyPlaced = days.flatMap { it.existing }.mapNotNull { it.taskId }.toSet()
        val remaining = tasks.filter { it.id !in alreadyPlaced }.toMutableList()
        val waiting = mutableListOf<Task>() // overflow not yet placed
        val everOverflowed = mutableListOf<Task>()
        val placed = mutableListOf<Block>()
        var loadCut = emptyList<Task>()
        val catchUpDate = days.firstOrNull { it.date.dayOfWeek == DayOfWeek.SUNDAY }?.date

        val pinned = mutableMapOf<LocalDate, MutableList<Block>>()
        val pinConflicts = mutableListOf<PinConflict>()
        pinTasks(days, remaining, pinned, pinConflicts)
        placed += pinned.values.flatten()

        days.forEachIndexed { index, base ->
            val ctx = base.copy(existing = base.existing + pinned[base.date].orEmpty())
            val isCatchUp = ctx.date == catchUpDate
            val isFirst = index == 0 && band != ReadinessBand.GREEN
            var candidates = (if (isCatchUp) waiting + remaining else remaining).filter { availableOn(it, ctx) }

            var result = plan(ctx, candidates, hardestFirst = isFirst)
            if (isFirst) {
                loadCut = loadCutFor(band, result, candidates, ctx, now)
                if (applyLoadCut && loadCut.isNotEmpty()) {
                    val cut = loadCut.map { it.id }.toSet()
                    candidates = candidates.filter { it.id !in cut }
                    result = plan(ctx, candidates, hardestFirst = true)
                }
            }

            placed += result.blocks
            val placedIds = result.blocks.mapNotNull { it.taskId }.toSet()
            remaining.removeAll { it.id in placedIds }
            waiting.removeAll { it.id in placedIds }

            if (!isCatchUp && catchUpDate != null) {
                result.unplaced
                    .filter { t -> dueDate(t, ctx.zone)?.let { it <= ctx.date } == true }
                    .forEach { t ->
                        if (remaining.remove(t)) {
                            waiting += t
                            everOverflowed += t
                        }
                    }
            }
        }

        return WeekPlan(
            blocks = placed,
            overflow = everOverflowed,
            unscheduled = remaining + waiting,
            loadCut = loadCut,
            catchUpExceeded = everOverflowed.sumOf { it.estMin } > rules.sundayCatchUpMin,
            pinConflicts = pinConflicts,
        )
    }

    /**
     * Places tasks that have a chosen start time exactly there, as locked user blocks, before anything
     * else is planned. The chosen time may fall outside the work window, but not on a fixed event,
     * prayer time, another block, or the past. Pins beyond the planned days are left alone; pins that
     * can't be honoured stay in [remaining] to be planned normally and are reported in [conflicts].
     */
    private fun pinTasks(
        days: List<DayContext>,
        remaining: MutableList<Task>,
        pinned: MutableMap<LocalDate, MutableList<Block>>,
        conflicts: MutableList<PinConflict>,
    ) {
        val firstDay = days.firstOrNull() ?: return
        val wanted = remaining.filter { it.startAt != null }
            .sortedWith(compareBy<Task>({ dueDate(it, firstDay.zone) ?: firstDay.date }, { it.startAt }, { it.id }))
        for (task in wanted) {
            val date = dueDate(task, firstDay.zone) ?: firstDay.date
            val base = days.firstOrNull { it.date == date }
            if (base == null) {
                remaining.remove(task) // chosen day isn't in the plan; never pull it onto another day
                continue
            }
            val ctx = base.copy(existing = base.existing + pinned[date].orEmpty())
            val start = task.startAt!!.toMinuteOfDay()
            val end = start + task.estMin
            val reason = pinProblem(ctx, Slot(start, end))
            if (reason != null) {
                conflicts += PinConflict(task, reason)
                continue
            }
            pinned.getOrPut(date) { mutableListOf() } +=
                Block(
                    taskId = task.id, date = date, start = start.toLocalTime(), end = end.toLocalTime(),
                    locked = true, origin = BlockOrigin.USER,
                )
            remaining.remove(task)
        }
    }

    private fun pinProblem(ctx: DayContext, slot: Slot): String? {
        val layout = DayLayout(ctx, rules, honourNotBefore = false)
        return when {
            slot.end > 24 * 60 - 1 -> "it would run past midnight"
            ctx.notBefore != null && slot.start < ctx.notBefore.toMinuteOfDay() -> "that time has passed"
            layout.fixedSlots.any { it.overlaps(slot) } -> "it clashes with a fixed commitment"
            layout.prayerSlots.any { it.overlaps(slot) } -> "it clashes with prayer time"
            ctx.existing.any { Slot(it.start.toMinuteOfDay(), it.end.toMinuteOfDay()).overlaps(slot) } -> "it overlaps another block"
            else -> null
        }
    }

    private fun lowerBound(task: Task, ctx: DayContext): Int? {
        var bound = 0
        task.availableFrom?.let { from ->
            val local = from.atZone(ctx.zone)
            when {
                local.toLocalDate() > ctx.date -> return null
                local.toLocalDate() == ctx.date -> bound = maxOf(bound, local.toLocalTime().toMinuteOfDay())
            }
        }
        task.anchor?.let { a ->
            ctx.prayers[a.prayer]?.let { bound = maxOf(bound, it.toMinuteOfDay() + a.offsetMin) }
        }
        return bound
    }

    private fun availableOn(task: Task, ctx: DayContext): Boolean {
        val from = task.availableFrom?.atZone(ctx.zone)?.toLocalDate() ?: return true
        return from <= ctx.date
    }

    private fun dueDate(task: Task, zone: ZoneId): LocalDate? = task.dueAt?.atZone(zone)?.toLocalDate()

    /**
     * Amber: drop about 20% of the day's task minutes, preferring tasks not due soon, then lower
     * priority, then the furthest deadline. Red: keep only tasks due within 48 hours.
     */
    private fun loadCutFor(band: ReadinessBand, first: DayPlan, candidates: List<Task>, ctx: DayContext, now: Instant): List<Task> {
        val byId = candidates.associateBy { it.id }
        val placed = first.blocks.mapNotNull { b -> b.taskId?.let(byId::get) }
        val dueSoon = { t: Task -> t.dueAt?.let { it <= now.plus(DUE_SOON) } == true }
        return when (band) {
            ReadinessBand.GREEN -> emptyList()
            ReadinessBand.RED -> placed.filterNot(dueSoon)
            ReadinessBand.AMBER -> {
                var total = ctx.existing.sumOf { it.minutes } + placed.sumOf { it.estMin }
                val target = total * 0.8
                val cut = mutableListOf<Task>()
                val cutOrder = compareBy<Task> { dueSoon(it) }
                    .thenBy { it.priority }
                    .thenByDescending { it.dueAt ?: Instant.MAX }
                    .thenByDescending { it.estMin }
                for (t in placed.sortedWith(cutOrder)) {
                    if (total <= target) break
                    cut += t
                    total -= t.estMin
                }
                cut
            }
        }
    }

    private fun ceil5(minute: Int): Int = (minute + 4) / 5 * 5

    private companion object {
        val DUE_SOON: Duration = Duration.ofHours(48)
    }
}
