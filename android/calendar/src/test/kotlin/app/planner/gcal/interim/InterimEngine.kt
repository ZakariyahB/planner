package app.planner.gcal.interim

import app.planner.engine.model.EventRef
import app.planner.engine.model.FixedEvent
import app.planner.engine.model.Placement
import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import app.planner.engine.model.Task
import app.planner.engine.model.TaskKind
import app.planner.engine.model.TaskStatus
import app.planner.engine.schedule.BlockFit
import app.planner.engine.schedule.BlockPlacer
import app.planner.engine.schedule.DaySummary
import app.planner.engine.schedule.Overflow
import app.planner.engine.schedule.OverflowReason
import app.planner.engine.schedule.PlanRequest
import app.planner.engine.schedule.PlanResult
import app.planner.engine.schedule.PlanWarning
import app.planner.engine.schedule.PlanningEngine
import app.planner.engine.schedule.PrayerModel
import app.planner.engine.schedule.RolloverRequest
import app.planner.engine.schedule.RolloverResult
import app.planner.gcal.plan.BlockMath
import app.planner.gcal.plan.Tiers
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

// INTERIM. A deliberately small engine so :calendar can be tested and the app can run before Stream A's engine
// (DefaultPlanningEngine) is merged. It implements the contracts in EngineApi.kt simply: placeBlock exactly as newplan
// 5.2, first-fit placement in the newplan 5.5 order, no splitting, overflow to the catch-up day. When Stream A lands,
// the master binds A's engine in di/CalendarBindings.kt and this package can be deleted.

/** newplan 5.2, exactly. */
class InterimBlockPlacer : BlockPlacer {
    override fun place(
        desiredStart: LocalDateTime,
        durationMin: Int,
        anchors: List<PrayerAnchor>,
        fixed: List<FixedEvent>,
        slotMin: Int,
    ): BlockFit {
        var start = desiredStart
        while (true) {
            val slot = BlockMath.inPrayerSlot(start, anchors, slotMin) ?: break
            start = BlockMath.at(slot).plusMinutes(slotMin.toLong())
        }
        var end = start.plusMinutes(durationMin.toLong())
        var covered: List<PrayerAnchor>
        while (true) {
            covered = BlockMath.covered(start, end, anchors)
            val newEnd = start.plusMinutes(durationMin.toLong() + slotMin.toLong() * covered.size)
            if (newEnd == end) break
            end = newEnd
        }
        fixed.sortedBy { it.start }.firstOrNull { BlockMath.overlaps(start, end, it.start, it.end) }
            ?.let { return BlockFit.Collides(start, end, it) }
        return BlockFit.Fits(start, end, slotMin * covered.size, covered.map { it.prayer })
    }
}

/** Offsets, weekend interpolation between the nearest weekdays, no Friday Dhuhr, no Fajr. */
class InterimPrayerModel : PrayerModel {
    override fun resolve(dates: List<LocalDate>, imported: List<PrayerAnchor>, settings: Settings): List<PrayerAnchor> {
        val known = imported.filter { it.prayer != Prayer.FAJR && !it.interpolated }
            .groupBy { it.prayer }.mapValues { (_, list) -> list.associateBy { it.date } }
        return dates.flatMap { date ->
            listOf(Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA).mapNotNull { prayer ->
                if (prayer == Prayer.DHUHR && date.dayOfWeek == DayOfWeek.FRIDAY) return@mapNotNull null
                val byDate = known[prayer].orEmpty()
                val anchor = byDate[date] ?: interpolate(date, prayer, byDate) ?: return@mapNotNull null
                val offset = settings.prayerOffsetMin[prayer] ?: 0
                anchor.copy(begin = anchor.begin.plusMinutes(offset.toLong()))
            }
        }
    }

    private fun interpolate(date: LocalDate, prayer: Prayer, byDate: Map<LocalDate, PrayerAnchor>): PrayerAnchor? {
        val before = (1L..7L).map { date.minusDays(it) }.firstOrNull { it in byDate }
        val after = (1L..7L).map { date.plusDays(it) }.firstOrNull { it in byDate }
        val minutes = when {
            before != null && after != null -> {
                val a = byDate.getValue(before).begin.toSecondOfDay() / 60
                val b = byDate.getValue(after).begin.toSecondOfDay() / 60
                val span = ChronoUnit.DAYS.between(before, after).toDouble()
                a + (b - a) * ChronoUnit.DAYS.between(before, date) / span
            }
            before != null -> trend(before, date, byDate, -1)
            after != null -> trend(after, date, byDate, 1)
            else -> return null
        }
        val m = Math.round(minutes).toInt().coerceIn(0, 24 * 60 - 1)
        return PrayerAnchor(date, prayer, java.time.LocalTime.of(m / 60, m % 60), interpolated = true)
    }

    /** Extend from [from] by the day-to-day trend seen next to it, in the direction away from [date]. */
    private fun trend(from: LocalDate, date: LocalDate, byDate: Map<LocalDate, PrayerAnchor>, dir: Long): Double {
        val base = byDate.getValue(from).begin.toSecondOfDay() / 60.0
        val neighbour = (1L..3L).map { from.plusDays(it * dir) }.firstOrNull { it in byDate } ?: return base
        val perDay = (base - byDate.getValue(neighbour).begin.toSecondOfDay() / 60.0) / ChronoUnit.DAYS.between(neighbour, from)
        return base + perDay * ChronoUnit.DAYS.between(from, date)
    }

    override fun absorb(anchors: List<PrayerAnchor>, fixed: List<FixedEvent>): List<PrayerAnchor> = BlockMath.absorb(anchors, fixed)
}

class InterimPlanningEngine(
    private val placer: BlockPlacer = InterimBlockPlacer(),
    private val prayers: PrayerModel = InterimPrayerModel(),
) : PlanningEngine {
    override fun plan(request: PlanRequest): PlanResult {
        val s = request.settings
        val anchors = prayers.absorb(request.anchors, request.fixed)
        val occupied = (request.fixed + request.keep.map { it.asFixed() }).toMutableList()
        val placements = mutableListOf<Placement>()
        val overflow = mutableListOf<Overflow>()
        val warnings = mutableListOf<PlanWarning>()
        val catchUp = request.catchUpDate
            ?: request.days.firstOrNull()?.with(TemporalAdjusters.nextOrSame(s.catchUpDay))

        val dated = request.tasks.filter { it.status == TaskStatus.BACKLOG || it.status == TaskStatus.SCHEDULED }
        for (day in request.days) {
            val dayTasks = dated.filter { it.assignedDay == day || (it.assignedDay == null && day == request.days.first()) }
            for (task in order(dayTasks, day, anchors, s)) {
                val p = firstFit(task, day, anchors, occupied, request.notBefore, s)
                if (p == null) {
                    overflow += Overflow(task.id, task.durationMin, OverflowReason.NO_ROOM, catchUp)
                    continue
                }
                placements += p
                occupied += p.asFixed()
                val deadline = task.deadline
                if (deadline != null && p.end.toLocalDate().isAfter(deadline)) {
                    warnings += PlanWarning.DeadlineMissed(task.id, deadline, p.end)
                }
            }
        }
        request.fixed.filter { it.assumed && it.start.toLocalDate() in request.days }
            .forEach { warnings += PlanWarning.AssumedGapKept(it.start.toLocalDate(), it.title) }

        val days = request.days.associateWith { day ->
            val today = placements.filter { it.start.toLocalDate() == day } + request.keep.filter { it.start.toLocalDate() == day }
            DaySummary(
                date = day,
                endTime = today.maxOfOrNull { it.end }?.toLocalTime(),
                maxTier = today.maxOfOrNull { it.tier } ?: app.planner.engine.model.Tier.NORMAL,
                flags = Tiers.dayFlags(
                    today.map { it.tier },
                    interpolatedPrayers = anchors.any { it.date == day && it.interpolated },
                    hasOverflow = overflow.isNotEmpty() && day == catchUp,
                ),
            )
        }
        return PlanResult(placements, overflow, warnings, days)
    }

    /** newplan 5.5: constrained tasks first, then priority, deep work before passive, overflow slots last. */
    private fun order(tasks: List<Task>, day: LocalDate, anchors: List<PrayerAnchor>, s: Settings): List<Task> {
        val (constrained, free) = tasks.partition { it.earliestStart != null || it.afterPrayer != null }
        return constrained.sortedWith(compareBy({ earliest(it, day, anchors, null, s) }, { it.id })) +
            free.sortedWith(compareBy({ -it.priority }, { kindRank(it.kind) }, { it.id }))
    }

    private fun kindRank(kind: TaskKind) = when (kind) { TaskKind.DEEP -> 0; TaskKind.PASSIVE -> 1; TaskKind.OVERFLOW -> 2 }

    private fun earliest(task: Task, day: LocalDate, anchors: List<PrayerAnchor>, notBefore: LocalDateTime?, s: Settings): LocalDateTime {
        var t = day.atTime(s.wakeTime)
        task.earliestStart?.let { t = maxOf(t, day.atTime(it)) }
        task.afterPrayer?.let { p ->
            anchors.firstOrNull { it.date == day && it.prayer == p }
                ?.let { t = maxOf(t, BlockMath.at(it).plusMinutes(s.prayerSlotMin.toLong())) }
        }
        notBefore?.let { t = maxOf(t, it) }
        return t
    }

    private fun firstFit(
        task: Task, day: LocalDate, anchors: List<PrayerAnchor>, occupied: List<FixedEvent>, notBefore: LocalDateTime?, s: Settings,
    ): Placement? {
        val from = earliest(task, day, anchors, notBefore, s)
        val cap = day.atTime(s.hardCap)
        val dayAnchors = anchors.filter { it.date == day }
        val candidates = (listOf(from) +
            occupied.map { it.end } +
            dayAnchors.map { BlockMath.at(it).plusMinutes(s.prayerSlotMin.toLong()) })
            .filter { !it.isBefore(from) && it.isBefore(cap) && it.toLocalDate() == day }
            .distinct().sorted()
        for (c in candidates) {
            if (occupied.any { !c.isBefore(it.start) && c.isBefore(it.end) }) continue
            val fit = placer.place(c, task.durationMin, dayAnchors, occupied, s.prayerSlotMin) as? BlockFit.Fits ?: continue
            if (fit.end.isAfter(cap)) continue
            val maghrib = dayAnchors.firstOrNull { it.prayer == Prayer.MAGHRIB }?.begin
            return Placement(
                taskId = task.id, start = fit.start, end = fit.end, prayerBufferMin = fit.bufferMin,
                coveredPrayers = fit.coveredPrayers, tier = Tiers.of(fit.start, fit.end, maghrib, s),
                interpolatedPrayer = BlockMath.covered(fit.start, fit.end, dayAnchors).any { it.interpolated },
            )
        }
        return null
    }

    private fun Placement.asFixed() = FixedEvent(EventRef("", "placement:$taskId:$chunkIndex"), taskId, start, end)

    override fun rollover(request: RolloverRequest): RolloverResult {
        val shift = ChronoUnit.DAYS.between(request.templateWeekStart, request.targetWeekStart)
        val weekTag = Regex("""\s*\bw-?\d+\b""", RegexOption.IGNORE_CASE)
        val tasks = request.templateTasks.map { t ->
            t.copy(
                id = UUID.nameUUIDFromBytes("${t.id}@${request.targetWeekStart}".toByteArray()).toString(),
                title = if (request.settings.stripWeekTagsOnRollover) t.title.replace(weekTag, "").trim() else t.title,
                assignedDay = t.assignedDay?.plusDays(shift),
                deadline = t.deadline?.plusDays(shift),
                status = TaskStatus.BACKLOG,
                calendarEventIds = emptyList(),
                sourceEvent = null,
            )
        }
        val notes = mutableListOf<String>()
        val assumed = request.expectedRecurring.mapNotNull { e ->
            val start = e.start.plusDays(shift)
            val present = request.targetFixed.any { it.title.equals(e.title, ignoreCase = true) && it.start.toLocalDate() == start.toLocalDate() }
            if (present) null else {
                notes += "${e.title} (${start.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }}) is not on the calendar yet; its time is kept free."
                e.copy(start = start, end = e.end.plusDays(shift), assumed = true)
            }
        }
        val days = (0L..6L).map { request.targetWeekStart.plusDays(it) }
        val plan = plan(PlanRequest(days, tasks, request.targetFixed + assumed, request.targetAnchors, request.settings))
        return RolloverResult(tasks, plan, notes)
    }
}
