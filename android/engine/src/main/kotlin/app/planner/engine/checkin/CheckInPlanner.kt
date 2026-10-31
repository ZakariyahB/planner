package app.planner.engine.checkin

import app.planner.engine.model.ExecutionOutcome
import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import app.planner.engine.ports.TodayBlock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Works out a day's check-in pings from its blocks (newplan section 10). Deterministic and clock-free: the caller
 * drops pings that are already in the past (see [AlarmDiff]).
 *
 * Rules, each a [app.planner.engine.model.NotificationPrefs] setting:
 * - Only the app's task blocks that are on the calendar get pings; fixed events (classes, halaqa) do not.
 * - Start ping at the block start; end ping at the block end.
 * - Blocks with under [startOnlyUnderMin] minutes of work (prayer buffer not counted) get a start ping only.
 * - Back-to-back blocks get one "Next up" ping at the boundary instead of an end ping plus a start ping.
 * - A block spanning a prayer gets one "Dhuhr break" ping at the prayer time, per prayer it spans.
 * - No pings inside a prayer slot: a ping that lands in one is moved to the end of the slot (it is still useful
 *   a few minutes later; dropping it would lose the end-of-block question). Prayers absorbed by a fixed event have
 *   no slot. The prayer-break ping itself is the one exception: it marks the start of the slot.
 * - No pings after the hard cap: a ping later than the cap, including one moved there by a prayer slot, is dropped.
 * - A block already logged as done, skipped or stopped gets no more pings; one already started gets no start ping.
 */
object CheckInPlanner {
    /** Gap between two blocks that still counts as back to back. */
    const val BACK_TO_BACK_GAP_MIN = 5L

    fun plan(date: LocalDate, blocks: List<TodayBlock>, anchors: List<PrayerAnchor>, settings: Settings): List<CheckIn> {
        val prefs = settings.notifications
        if (!prefs.checkInsEnabled) return emptyList()

        val slotMin = settings.prayerSlotMin.toLong()
        val fixed = blocks.filter { it.isFixed }
        val prayers = anchors
            .filter { it.date == date && it.prayer != Prayer.FAJR }
            .map { it.prayer to date.atTime(it.begin) }
            .filter { (_, at) -> fixed.none { !at.isBefore(it.start) && at.isBefore(it.end) } } // absorbed: no slot
            .sortedBy { it.second }
        val slots = if (prefs.quietDuringPrayer) prayers.map { it.second to it.second.plusMinutes(slotMin) } else emptyList()
        val cap = date.atTime(settings.hardCap)

        fun quiet(at: LocalDateTime): LocalDateTime {
            var t = at
            while (true) {
                val slot = slots.firstOrNull { (from, to) -> !t.isBefore(from) && t.isBefore(to) } ?: return t
                t = slot.second
            }
        }

        val tasks = blocks
            .filter { !it.isFixed && it.ref != null && it.end.isAfter(it.start) }
            .filter { it.start.toLocalDate() == date }
            .sortedWith(compareBy({ it.start }, { it.end }))
        val outcome = tasks.map { it.execution?.outcome ?: ExecutionOutcome.PENDING }
        val targets = tasks.map { it.toCheckInBlock() }

        fun finished(i: Int) = outcome[i] in FINISHED
        fun started(i: Int) = outcome[i] == ExecutionOutcome.STARTED || outcome[i] == ExecutionOutcome.EXTENDED
        fun startOnly(i: Int) = targets[i].workMin < prefs.startOnlyUnderMin

        fun backToBack(i: Int, j: Int): Boolean {
            val a = targets[i]
            val b = targets[j]
            val gap = Duration.between(a.plannedEnd, b.plannedStart).toMinutes()
            // Also back to back when a prayer slot pushes the end ping onto (or past) the next start.
            return (gap in 0..BACK_TO_BACK_GAP_MIN) || (gap > 0 && !quiet(a.plannedEnd).isBefore(b.plannedStart))
        }

        val raw = mutableListOf<CheckIn>()
        val startMerged = BooleanArray(tasks.size)
        for (i in tasks.indices) {
            if (finished(i)) continue
            val block = targets[i]
            if (!startMerged[i] && !started(i)) raw += CheckIn(CheckIn.idFor(CheckInKind.START, block), CheckInKind.START, block.plannedStart, block)
            if (startOnly(i)) continue

            val j = i + 1
            val merge = prefs.mergeBackToBack && j < tasks.size && !finished(j) && !started(j) && backToBack(i, j)
            raw += if (merge) {
                startMerged[j] = true
                CheckIn(CheckIn.idFor(CheckInKind.NEXT_UP, block), CheckInKind.NEXT_UP, block.plannedEnd, block, next = targets[j])
            } else {
                CheckIn(CheckIn.idFor(CheckInKind.END, block), CheckInKind.END, block.plannedEnd, block)
            }

            if (prefs.midTaskPrayerPing) {
                prayers.filter { (_, at) -> at.isAfter(block.plannedStart) && at.isBefore(block.plannedEnd) }.forEach { (prayer, at) ->
                    raw += CheckIn(CheckIn.idFor(CheckInKind.PRAYER_BREAK, block, prayer), CheckInKind.PRAYER_BREAK, at, block, prayer = prayer)
                }
            }
        }

        return raw
            .map { c ->
                if (c.kind == CheckInKind.PRAYER_BREAK) c
                else quiet(c.at).let { t -> if (t == c.at) c else c.copy(at = t, shiftedFrom = c.at) }
            }
            .filter { !it.at.isAfter(cap) }
            .sortedWith(compareBy({ it.at }, { it.block.plannedStart }, { it.kind.ordinal }))
    }

    /** Several days at once (the rescheduler plans today and tomorrow, so a reboot overnight loses nothing). */
    fun planDays(days: Map<LocalDate, Pair<List<TodayBlock>, List<PrayerAnchor>>>, settings: Settings): List<CheckIn> =
        days.toSortedMap().flatMap { (date, input) -> plan(date, input.first, input.second, settings) }

    private val FINISHED = setOf(ExecutionOutcome.DONE, ExecutionOutcome.SKIPPED, ExecutionOutcome.STOPPED)
}

fun TodayBlock.toCheckInBlock(): CheckInBlock = CheckInBlock(
    ref = requireNotNull(ref) { "check-ins need a calendar event" },
    taskId = taskId,
    title = listOfNotNull(title, chunkLabel).joinToString(" "),
    plannedStart = start,
    plannedEnd = end,
    prayerBufferMin = prayerBufferMin,
    coveredPrayers = coveredPrayers,
)
