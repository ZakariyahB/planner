package app.planner.engine.schedule

import app.planner.engine.model.EventRef
import app.planner.engine.model.FixedEvent
import app.planner.engine.model.Placement
import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import app.planner.engine.model.Task
import app.planner.engine.model.TaskStatus
import app.planner.engine.model.Tier
import app.planner.engine.prayer.DefaultPrayerModel
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID

/** The engine wired with its default parts. */
object Engine {
    val placer: BlockPlacer = DefaultBlockPlacer
    val prayers: PrayerModel = DefaultPrayerModel
    val planning: PlanningEngine = DefaultPlanningEngine(placer, prayers)
}

/**
 * newplan 5.3 to 5.7. Pure and deterministic: no clock, no I/O, identical input gives identical output.
 *
 * - Each task is planned on its `assignedDay` when that is in [PlanRequest.days] (see [DayPlanner] for the order and
 *   the splitting rule). A task with no `assignedDay` goes on the requested day where it fits in the lowest tier
 *   (earliest day on a tie, days up to its deadline first), after all day-assigned tasks. Tasks assigned to other
 *   days, and `DONE`/`SKIPPED` tasks, are left alone.
 * - Work that does not fit becomes [Overflow], proposed for the catch-up date (never the next day). Overflow from
 *   the catch-up day itself, or later, has no proposed date: moving it needs the user's decision. A split task may
 *   be partly placed; only the rest overflows.
 * - [PlanRequest.keep] placements stay exactly where they are and are planned around; their work counts towards
 *   their task, and only the rest is placed. They are returned in [PlanResult.placements] with the new chunks, and
 *   every task's chunks (kept and new) are numbered in time order, so a kept `(2/2)` whose first chunk was dropped
 *   becomes a single block. Their times, buffer and covered prayers are kept as given; their tier is recomputed.
 * - [PlanRequest.notBefore]: nothing new starts before it; earlier days get nothing (their tasks overflow with
 *   [OverflowReason.PAST_HARD_CAP]).
 * - Fixed events marked `assumed` are planned around like real ones and reported as [PlanWarning.AssumedGapKept].
 */
class DefaultPlanningEngine(
    private val placer: BlockPlacer = DefaultBlockPlacer,
    private val prayers: PrayerModel = DefaultPrayerModel,
) : PlanningEngine {

    override fun plan(request: PlanRequest): PlanResult {
        val days = request.days.distinct()
        val settings = request.settings
        val slot = settings.prayerSlotMin
        val anchors = request.anchors.filter { it.prayer != Prayer.FAJR }
        val fixed = request.fixed.filter { it.end.isAfter(it.start) }
        val absorbed = prayers.absorb(anchors, fixed)
        if (days.isEmpty()) return PlanResult(request.keep, emptyList(), emptyList(), emptyMap())

        val catchUp = request.catchUpDate ?: days.first()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .with(TemporalAdjusters.nextOrSame(settings.catchUpDay))
        val inputIndex = request.tasks.withIndex().associate { (i, t) -> t.id to i }
        val active = request.tasks.distinctBy { it.id }
            .filter { it.status != TaskStatus.DONE && it.status != TaskStatus.SKIPPED && it.durationMin > 0 }
        val keepByTask = request.keep.groupBy { it.taskId }
        val toPlace = active.associate { t ->
            t.id to maxOf(0, t.durationMin - (keepByTask[t.id]?.sumOf { workMin(it) } ?: 0))
        }

        val planners = days.associateWith { d -> planner(d, settings, anchors, absorbed, request.notBefore) }
        val states = days.associateWith { d ->
            val from = d.atStartOfDay()
            val to = d.plusDays(1).atStartOfDay()
            val busy = fixed.filter { it.start.isBefore(to) && it.end.isAfter(from) } +
                request.keep.filter { it.start.isBefore(to) && it.end.isAfter(from) }.map(::keptBlock)
            DayState(busy)
        }
        val bases = states.mapValues { it.value.copy() }
        val overflow = ArrayList<Overflow>()
        val notes = ArrayList<PlanWarning>()

        // Day-assigned tasks, day by day.
        for (d in days) {
            val planner = planners.getValue(d)
            val state = states.getValue(d)
            val tasks = active.filter { it.assignedDay == d && toPlace.getValue(it.id) > 0 }
            val works = ArrayList<Work>()
            for (t in tasks) {
                state.remaining[t.id] = toPlace.getValue(t.id)
                val w = work(t, inputIndex.getValue(t.id), planner, settings, anchors)
                if (w == null) {
                    notes += missingPrayerNote(t, d)
                    overflow += Overflow(t.id, toPlace.getValue(t.id), OverflowReason.CONSTRAINT_UNSATISFIABLE, proposed(d, catchUp))
                } else {
                    works += w
                }
            }
            planner.run(state, works)
            for (w in works) {
                val left = state.left(w.id)
                if (left > 0) overflow += Overflow(w.id, left, reason(planner, bases.getValue(d), w, toPlace.getValue(w.id)), proposed(d, catchUp))
            }
        }

        // Tasks with no day: the requested day where they fit in the lowest tier.
        val unassigned = active.filter { it.assignedDay == null && toPlace.getValue(it.id) > 0 }
            .sortedWith(compareBy<Task>({ DayPlanner.rankKey(it).first }, { DayPlanner.rankKey(it).second }, { DayPlanner.rankKey(it).third })
                .thenBy { inputIndex.getValue(it.id) })
        for (t in unassigned) {
            val left = toPlace.getValue(t.id)
            val byDeadline = days.partition { t.deadline == null || !it.isAfter(t.deadline) }
            var chosen: Triple<LocalDate, DayState, Tier>? = null
            for (group in listOf(byDeadline.first, byDeadline.second)) {
                for (d in group) {
                    val planner = planners.getValue(d)
                    val w = work(t, inputIndex.getValue(t.id), planner, settings, anchors) ?: continue
                    val trial = states.getValue(d).copy()
                    trial.remaining[t.id] = left
                    val tier = Tier.entries.firstOrNull { tier ->
                        planner.sweep(trial, listOf(w), planner.windows.limit(tier))
                        trial.left(t.id) <= 0
                    } ?: continue
                    if (chosen == null || tier < chosen.third) chosen = Triple(d, trial, tier)
                    if (tier == Tier.NORMAL) break
                }
                if (chosen != null) break
            }
            if (chosen != null) {
                states.getValue(chosen.first).assign(chosen.second)
            } else {
                val reasons = days.mapNotNull { d ->
                    val planner = planners.getValue(d)
                    work(t, inputIndex.getValue(t.id), planner, settings, anchors)?.let { reason(planner, bases.getValue(d), it, left) }
                }
                val reason = when {
                    OverflowReason.NO_ROOM in reasons -> OverflowReason.NO_ROOM
                    OverflowReason.CONSTRAINT_UNSATISFIABLE in reasons || reasons.isEmpty() -> OverflowReason.CONSTRAINT_UNSATISFIABLE
                    else -> OverflowReason.PAST_HARD_CAP
                }
                overflow += Overflow(t.id, left, reason, catchUp)
            }
        }

        val placements = assemble(request, states, planners, settings, anchors)
        val warnings = ArrayList<PlanWarning>()
        fixed.filter { it.assumed && it.start.toLocalDate() in days }
            .sortedWith(DayState.BUSY_ORDER)
            .distinctBy { it.start.toLocalDate() to it.title.trim().lowercase() }
            .forEach { warnings += PlanWarning.AssumedGapKept(it.start.toLocalDate(), it.title) }
        val considered = active.filter { it.assignedDay == null || it.assignedDay in days }
        for (t in considered) {
            val deadline = t.deadline ?: continue
            val end = placements.filter { it.taskId == t.id }.maxOfOrNull { it.end }
            val over = overflow.filter { it.taskId == t.id }
            val late = (end != null && end.toLocalDate().isAfter(deadline)) ||
                over.any { it.proposedDate == null || it.proposedDate.isAfter(deadline) }
            if (late) warnings += PlanWarning.DeadlineMissed(t.id, deadline, end)
        }
        for (d in days) {
            if (anchors.none { it.date == d }) warnings += PlanWarning.Note("No prayer times for $d: planned without prayer buffers.")
        }
        warnings += notes

        val summaries = days.associateWith { d ->
            val windows = planners.getValue(d).windows
            val mine = placements.filter { it.start.toLocalDate() == d }
            val flags = buildSet {
                if (mine.any { windows.minutesIn(Tier.REST, it.start, it.end) > 0 }) add(DayFlag.USES_REST_TIME)
                if (mine.any { windows.minutesIn(Tier.EXTENDED, it.start, it.end) > 0 }) add(DayFlag.EXTENDED_DAY)
                if (mine.any { it.tier == Tier.OVER_SOFT_LIMIT }) add(DayFlag.OVER_SOFT_LIMIT)
                if (anchors.any { it.date == d && it.interpolated }) add(DayFlag.INTERPOLATED_PRAYERS)
                if (active.any { it.assignedDay == d && overflow.any { o -> o.taskId == it.id } }) add(DayFlag.HAS_OVERFLOW)
            }
            DaySummary(d, mine.maxOfOrNull { it.end }?.toLocalTime(), mine.maxOfOrNull { it.tier } ?: Tier.NORMAL, flags)
        }
        return PlanResult(placements, overflow, warnings, summaries)
    }

    override fun rollover(request: RolloverRequest): RolloverResult {
        val shift = ChronoUnit.DAYS.between(request.templateWeekStart, request.targetWeekStart)
        val days = (0L..6L).map { request.targetWeekStart.plusDays(it) }
        val strip = request.settings.stripWeekTagsOnRollover
        val tagged = request.templateTasks.count { WeekTags.has(it.title) }
        val tasks = request.templateTasks.map { t ->
            t.copy(
                id = UUID.nameUUIDFromBytes("rollover:${t.id}:${request.targetWeekStart}".toByteArray()).toString(),
                title = if (strip) WeekTags.strip(t.title) else t.title,
                assignedDay = t.assignedDay?.plusDays(shift),
                deadline = t.deadline?.plusDays(shift),
                status = TaskStatus.BACKLOG,
                calendarEventIds = emptyList(),
                sourceEvent = null,
            )
        }

        val notes = ArrayList<String>()
        val assumed = ArrayList<FixedEvent>()
        for (e in request.expectedRecurring) {
            val dayIndex = Math.floorMod(ChronoUnit.DAYS.between(request.targetWeekStart, e.start.toLocalDate()), 7L)
            val move = ChronoUnit.DAYS.between(e.start.toLocalDate(), request.targetWeekStart.plusDays(dayIndex))
            val start = e.start.plusDays(move)
            val present = request.targetFixed.any {
                !it.assumed && it.start.toLocalDate() == start.toLocalDate() && it.title.trim().equals(e.title.trim(), ignoreCase = true)
            }
            if (present) continue
            val end = e.end.plusDays(move)
            assumed += e.copy(ref = EventRef(e.ref.calendarId, "assumed-${e.ref.eventId}-${start.toLocalDate()}"), start = start, end = end, assumed = true)
            notes += "${e.title} is not on the calendar for ${DAY.format(start)}; kept ${HM.format(start)}-${HM.format(end)} free."
        }

        val plan = plan(
            PlanRequest(
                days = days,
                tasks = tasks,
                fixed = request.targetFixed + assumed,
                anchors = request.targetAnchors,
                settings = request.settings,
            ),
        )
        notes.add(0, "Copied ${tasks.size} template tasks from the week of ${DAY.format(request.templateWeekStart)} into the week of ${DAY.format(request.targetWeekStart)}, timed to that week's prayer times and events.")
        if (tagged > 0) notes += if (strip) "Removed week tags from $tagged titles." else "Kept week tags in $tagged titles."
        if (plan.overflow.isNotEmpty()) notes += "${plan.overflow.size} tasks did not fit; proposed for the catch-up day."
        return RolloverResult(tasks, plan, notes)
    }

    private fun planner(
        d: LocalDate,
        settings: Settings,
        anchors: List<PrayerAnchor>,
        absorbed: List<PrayerAnchor>,
        notBefore: LocalDateTime?,
    ): DayPlanner {
        val windows = TierWindows.of(d, settings, anchors.filter { it.date == d })
        val start = when {
            notBefore == null || notBefore.toLocalDate().isBefore(d) -> windows.wake
            notBefore.toLocalDate().isAfter(d) -> windows.hardCap
            else -> minOf(maxOf(windows.wake, notBefore), windows.hardCap)
        }
        return DayPlanner(d, windows, start, absorbed.filter { it.date == d }, settings.prayerSlotMin, placer)
    }

    /** The task as work on [planner]'s day, or null when its `afterPrayer` has no anchor that day. */
    private fun work(t: Task, index: Int, planner: DayPlanner, settings: Settings, anchors: List<PrayerAnchor>): Work? {
        var earliest = planner.dayStart
        t.earliestStart?.let { earliest = maxOf(earliest, planner.date.atTime(it)) }
        t.afterPrayer?.let { p ->
            val a = anchors.filter { it.date == planner.date && it.prayer == p }.minOfOrNull { it.begin } ?: return null
            earliest = maxOf(earliest, planner.date.atTime(a).plusMinutes(settings.prayerSlotMin.toLong()))
        }
        val minChunk = maxOf(1, t.minChunkMin, settings.minChunkMin)
        return Work(t, index, earliest, minChunk, constrained = t.earliestStart != null || t.afterPrayer != null)
    }

    /**
     * Why [w] did not fit: try all [minutes] of it (not just what is left after its own chunks) alone on the day as it
     * was before planning. Fits alone: other work took the room. Fits only without its constraint: the constraint.
     * Otherwise it needs time past the hard cap.
     */
    private fun reason(planner: DayPlanner, base: DayState, w: Work, minutes: Int): OverflowReason {
        if (!planner.dayStart.isBefore(planner.windows.hardCap)) return OverflowReason.PAST_HARD_CAP
        if (!w.earliest.isBefore(planner.windows.hardCap)) return OverflowReason.CONSTRAINT_UNSATISFIABLE
        fun fitsAlone(work: Work): Boolean {
            val s = base.copy()
            s.remaining[work.id] = minutes
            planner.placeByTier(s, listOf(work))
            return s.left(work.id) <= 0
        }
        if (fitsAlone(w)) return OverflowReason.NO_ROOM
        if (w.constrained && fitsAlone(w.copy(earliest = planner.dayStart, constrained = false))) {
            return OverflowReason.CONSTRAINT_UNSATISFIABLE
        }
        return OverflowReason.PAST_HARD_CAP
    }

    private fun proposed(day: LocalDate, catchUp: LocalDate): LocalDate? = catchUp.takeIf { it.isAfter(day) }

    private fun missingPrayerNote(t: Task, d: LocalDate): PlanWarning.Note {
        val p = t.afterPrayer!!.name.lowercase().replaceFirstChar { it.titlecase(Locale.UK) }
        return PlanWarning.Note("${t.title}: no $p time on $d, so \"after $p\" cannot be met.")
    }

    private fun assemble(
        request: PlanRequest,
        states: Map<LocalDate, DayState>,
        planners: Map<LocalDate, DayPlanner>,
        settings: Settings,
        anchors: List<PrayerAnchor>,
    ): List<Placement> {
        fun windowsFor(d: LocalDate) = planners[d]?.windows ?: TierWindows.of(d, settings, anchors.filter { it.date == d })
        val fresh = states.values.flatMap { it.pieces }.map { piece ->
            Placement(
                taskId = piece.taskId,
                start = piece.start,
                end = piece.end,
                prayerBufferMin = piece.bufferMin,
                coveredPrayers = piece.covered.map { it.prayer },
                tier = windowsFor(piece.start.toLocalDate()).tierOf(piece.start, piece.end),
                interpolatedPrayer = piece.covered.any { it.interpolated },
            )
        }
        val kept = request.keep.map { it.copy(tier = windowsFor(it.start.toLocalDate()).tierOf(it.start, it.end)) }
        val out = ArrayList<Placement>()
        (kept + fresh).groupBy { it.taskId }.forEach { (_, chunks) ->
            val sorted = chunks.sortedBy { it.start }
            sorted.forEachIndexed { i, p -> out += p.copy(chunkIndex = i + 1, chunkCount = sorted.size) }
        }
        return out.sortedWith(compareBy<Placement>({ it.start }, { it.end }, { it.taskId }, { it.chunkIndex }))
    }

    companion object {
        private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
        private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

        fun workMin(p: Placement): Int = Duration.between(p.start, p.end).toMinutes().toInt() - p.prayerBufferMin

        private fun keptBlock(p: Placement) =
            FixedEvent(EventRef("planner:keep", "${p.taskId}#${p.chunkIndex}"), p.taskId, p.start, p.end)
    }
}
