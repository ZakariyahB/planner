package app.planner.engine.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.Serializable

// Contracts (Stream 0). Change only through docs/redesign/CHANGES.md.
//
// All times in the engine are local wall-clock in the device zone (Settings.zone, default Europe/London).
// Conversion to and from Instant happens only at the calendar boundary (:calendar).

/** The user's writable category calendars, matched by trimmed, case-insensitive name (Academic's real name is "Academic "). */
enum class CalendarCategory(val calendarName: String) {
    ACADEMIC("Academic"),
    LECTURES("Lectures"),
    QURAN_ARABIC("Quran and Arabic"),
    COMMUTE("Commute"),
    SOCIETY("Society"),
    FITNESS("Fitness"),
    PERSONAL("Personal"),
    ;

    companion object {
        /** Never hardcode calendar ids: resolve them from calendarList by name. */
        fun forCalendarName(name: String): CalendarCategory? =
            entries.firstOrNull { it.calendarName.equals(name.trim(), ignoreCase = true) }
    }
}

/** Read-only. Never written to. */
const val PRAYER_CALENDAR_NAME = "Prayer times"

enum class Prayer { FAJR, DHUHR, ASR, MAGHRIB, ISHA }

enum class TaskKind { DEEP, PASSIVE, OVERFLOW }

enum class TaskStatus { BACKLOG, SCHEDULED, DONE, SKIPPED }

@Serializable
data class EventRef(val calendarId: String, val eventId: String)

data class Task(
    /** Client-generated (UUID string), so it can be written into extendedProperties before the event exists. */
    val id: String,
    val title: String,
    val category: CalendarCategory,
    val durationMin: Int,
    /** From the weekly template; the engine changes it only through an approved ChangeSet. */
    val assignedDay: LocalDate? = null,
    /** e.g. a lecture recording available from 14:00. */
    val earliestStart: LocalTime? = null,
    /** e.g. Arabic homework "after Asr": may not start before that prayer's slot ends. */
    val afterPrayer: Prayer? = null,
    val deadline: LocalDate? = null,
    val splittable: Boolean = true,
    val minChunkMin: Int = 45,
    /** Higher is more important. */
    val priority: Int = 0,
    val kind: TaskKind = TaskKind.DEEP,
    val status: TaskStatus = TaskStatus.BACKLOG,
    /** Linked calendar blocks, one per chunk, in chunk order. */
    val calendarEventIds: List<EventRef> = emptyList(),
    /** The all-day template event this task was imported from; scheduling edits it into the first timed chunk. */
    val sourceEvent: EventRef? = null,
    val notes: String? = null,
)

/** A timed event read from any calendar that is not one of the app's own task blocks. The engine plans around it. */
data class FixedEvent(
    val ref: EventRef,
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val organiserIsExternal: Boolean = false,
    /** Not on the calendar yet (e.g. Halaqa assumed from the template); the gap is kept open and the proposal says so. */
    val assumed: Boolean = false,
)

/** An imported prayer begin time. Never hardcoded; Saturday and Sunday are interpolated. */
data class PrayerAnchor(
    val date: LocalDate,
    val prayer: Prayer,
    val begin: LocalTime,
    val interpolated: Boolean = false,
)

/** Where a placement sits in the day's work window. Boundaries come from [Settings]. */
enum class Tier(val level: Int) {
    /** Wake start to Maghrib. */
    NORMAL(0),

    /** Maghrib slot end to normal end (18:00). Used only when tier 0 cannot fit the day. */
    REST(1),

    /** Normal end to extended end (18:00 to 20:00). */
    EXTENDED(2),

    /** Extended end to the hard cap (20:00 to 22:00). Only if needed, flagged on the approval card. */
    OVER_SOFT_LIMIT(3),
}

/** One placed block (or chunk) of a task. A task is one continuous block unless it is split. */
data class Placement(
    val taskId: String,
    /** 1-based. */
    val chunkIndex: Int = 1,
    val chunkCount: Int = 1,
    val start: LocalDateTime,
    val end: LocalDateTime,
    /** 15 minutes per prayer anchor in [start, end). */
    val prayerBufferMin: Int = 0,
    val coveredPrayers: List<Prayer> = emptyList(),
    val tier: Tier = Tier.NORMAL,
    /** At least one covered prayer time was interpolated (weekend). */
    val interpolatedPrayer: Boolean = false,
) {
    val label: String? get() = if (chunkCount > 1) "($chunkIndex/$chunkCount)" else null
}

enum class ExecutionOutcome { PENDING, STARTED, DONE, EXTENDED, STOPPED, SKIPPED }

/** Actual versus planned, per block. Logging Start/Done is not a calendar edit and needs no approval. */
data class ExecutionLog(
    val id: String,
    val taskId: String?,
    val eventRef: EventRef?,
    val title: String,
    val plannedStart: LocalDateTime,
    val plannedEnd: LocalDateTime,
    val actualStart: LocalDateTime? = null,
    val actualEnd: LocalDateTime? = null,
    val focus1to5: Int? = null,
    val outcome: ExecutionOutcome = ExecutionOutcome.PENDING,
)

data class NotificationPrefs(
    val checkInsEnabled: Boolean = true,
    /** Back-to-back blocks get one "Next up" ping instead of an end ping plus a start ping. */
    val mergeBackToBack: Boolean = true,
    /** Blocks shorter than this get a start ping only. */
    val startOnlyUnderMin: Int = 45,
    val quietDuringPrayer: Boolean = true,
    val midTaskPrayerPing: Boolean = true,
    val askFocusOnDone: Boolean = true,
    val snoozeMin: Int = 10,
    val extendMin: Int = 30,
    val nightBeforeReview: LocalTime = LocalTime.of(21, 30),
    val sundayPrompts: Boolean = true,
    val nightBeforeReviewEnabled: Boolean = true,
    val sundayDeadlinesAt: LocalTime = LocalTime.of(19, 0),
    val fajrPromptDay: DayOfWeek = DayOfWeek.SUNDAY,
    val fajrPromptAt: LocalTime = LocalTime.of(12, 0),
)

data class Settings(
    val wakeTime: LocalTime = LocalTime.of(9, 30),
    val normalEnd: LocalTime = LocalTime.of(18, 0),
    val extendedEnd: LocalTime = LocalTime.of(20, 0),
    val hardCap: LocalTime = LocalTime.of(22, 0),
    val prayerSlotMin: Int = 15,
    /** Per-prayer offset from the begin time, default 0 (anchor = begin time). */
    val prayerOffsetMin: Map<Prayer, Int> = emptyMap(),
    val minChunkMin: Int = 45,
    val writableCategories: Set<CalendarCategory> = CalendarCategory.entries.toSet(),
    /** Overflow goes here by default, never stacked onto the next day. */
    val catchUpDay: DayOfWeek = DayOfWeek.SUNDAY,
    /** Decision 1: scheduling edits the all-day template event into the first timed chunk (no duplicate). */
    val convertAllDayOnSchedule: Boolean = true,
    /** Decision 6 (owner): keep week tags like "w-1" on rollover. */
    val stripWeekTagsOnRollover: Boolean = false,
    /** Lecture recordings: available this long after the slot ends. */
    val recordingDelayMin: Int = 60,
    /** Sleep need for readiness (coaching only; never used by the scheduler). */
    val sleepNeedMin: Int = 420,
    val notifications: NotificationPrefs = NotificationPrefs(),
    val zone: ZoneId = ZoneId.of("Europe/London"),
    /** Model used for chat; the Worker routes cheaper runs itself. */
    val assistantModel: String = "claude-sonnet-5-5",
)

/** An all-day template event whose title did not parse; shown in Backlog under "Needs a look". */
data class UnparsedTemplate(
    val ref: EventRef,
    val title: String,
    val calendarName: String,
    val date: LocalDate,
)

/** When the app last heard from Google. */
data class SyncStamp(val at: Instant, val ok: Boolean, val message: String? = null)
