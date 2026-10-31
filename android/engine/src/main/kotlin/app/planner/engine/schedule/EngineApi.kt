package app.planner.engine.schedule

import app.planner.engine.model.FixedEvent
import app.planner.engine.model.Placement
import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import app.planner.engine.model.Task
import app.planner.engine.model.Tier
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// Contracts (Stream 0). Implemented by Stream A. Change only through docs/redesign/CHANGES.md.
//
// The engine is deterministic: identical input gives identical output. It never writes to a calendar and never
// reads the clock; "now" is passed in. The AI never computes times: it passes intent, the engine returns times.

/** Result of placing one block at a desired start (newplan 5.2). */
sealed interface BlockFit {
    /** [start] may be later than desired (a start inside a prayer slot moves to the slot end). */
    data class Fits(
        val start: LocalDateTime,
        val end: LocalDateTime,
        val bufferMin: Int,
        val coveredPrayers: List<Prayer>,
    ) : BlockFit

    /** [start, end) would overlap [with]; the caller tries the next gap or splits. */
    data class Collides(val start: LocalDateTime, val end: LocalDateTime, val with: FixedEvent) : BlockFit
}

interface BlockPlacer {
    /**
     * newplan 5.2: never start inside a prayer slot [a, a+slot); add one slot of buffer per anchor a with
     * start <= a < end, iterating to a fixed point (the cascade). An anchor exactly at end does not count.
     * [anchors] should already have absorbed prayers removed (see [PrayerModel.absorb]).
     */
    fun place(
        desiredStart: LocalDateTime,
        durationMin: Int,
        anchors: List<PrayerAnchor>,
        fixed: List<FixedEvent>,
        slotMin: Int = 15,
    ): BlockFit
}

interface PrayerModel {
    /**
     * Anchors for every date in [dates]: imported times plus per-prayer offsets; Saturday and Sunday interpolated from
     * neighbouring weekdays (marked interpolated); no Friday Dhuhr. Fajr is excluded (not a work-window anchor).
     */
    fun resolve(dates: List<LocalDate>, imported: List<PrayerAnchor>, settings: Settings): List<PrayerAnchor>

    /** Drops anchors that fall inside a fixed event (e.g. Asr during Halaqa): no slot, no buffer. */
    fun absorb(anchors: List<PrayerAnchor>, fixed: List<FixedEvent>): List<PrayerAnchor>
}

data class PlanRequest(
    /** Days to plan, in order. Each task is planned on its assignedDay when that is in range. */
    val days: List<LocalDate>,
    val tasks: List<Task>,
    val fixed: List<FixedEvent>,
    /** Already resolved by [PrayerModel.resolve]. The engine applies absorption itself. */
    val anchors: List<PrayerAnchor>,
    val settings: Settings,
    /** Replanning: nothing is placed before this. */
    val notBefore: LocalDateTime? = null,
    /** Placements that stay exactly where they are (started blocks, the user's own edits). Planned around. */
    val keep: List<Placement> = emptyList(),
    /** Where overflow goes; null means the [Settings.catchUpDay] of the week of the first day. */
    val catchUpDate: LocalDate? = null,
)

enum class DayFlag { USES_REST_TIME, EXTENDED_DAY, OVER_SOFT_LIMIT, INTERPOLATED_PRAYERS, HAS_OVERFLOW }

data class DaySummary(
    val date: LocalDate,
    /** End of the last block that day, or null if nothing is placed. */
    val endTime: LocalTime?,
    val maxTier: Tier,
    val flags: Set<DayFlag>,
)

enum class OverflowReason { NO_ROOM, PAST_HARD_CAP, CONSTRAINT_UNSATISFIABLE }

/** Work that did not fit. It is proposed for the catch-up day, never stacked onto the next day. */
data class Overflow(
    val taskId: String,
    val remainingMin: Int,
    val reason: OverflowReason,
    val proposedDate: LocalDate?,
)

sealed interface PlanWarning {
    data class DeadlineMissed(val taskId: String, val deadline: LocalDate, val plannedEnd: LocalDateTime?) : PlanWarning

    /** A recurring fixed event the template assumed (Halaqa, Jumu'ah prep) is not on the calendar; the gap was kept. */
    data class AssumedGapKept(val date: LocalDate, val title: String) : PlanWarning

    data class Note(val text: String) : PlanWarning
}

data class PlanResult(
    val placements: List<Placement>,
    val overflow: List<Overflow>,
    val warnings: List<PlanWarning>,
    val days: Map<LocalDate, DaySummary>,
)

data class RolloverRequest(
    /** Tasks of the template week (typically imported from its all-day events). */
    val templateTasks: List<Task>,
    val templateWeekStart: LocalDate,
    val targetWeekStart: LocalDate,
    /** Fixed events already on the calendar in the target week. */
    val targetFixed: List<FixedEvent>,
    /** Recurring fixed events the template relied on; missing ones become assumed gaps. */
    val expectedRecurring: List<FixedEvent>,
    /** The target week's prayer anchors, already resolved. */
    val targetAnchors: List<PrayerAnchor>,
    val settings: Settings,
)

data class RolloverResult(
    /** Template tasks re-dated by 7 days (new ids), titles kept or stripped per settings. */
    val tasks: List<Task>,
    val plan: PlanResult,
    val notes: List<String>,
)

interface PlanningEngine {
    fun plan(request: PlanRequest): PlanResult

    fun rollover(request: RolloverRequest): RolloverResult
}
