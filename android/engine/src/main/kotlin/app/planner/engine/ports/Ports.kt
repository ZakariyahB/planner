package app.planner.engine.ports

import app.planner.engine.changeset.AppliedRecord
import app.planner.engine.changeset.ChangeSet
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.EventRef
import app.planner.engine.model.ExecutionLog
import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Settings
import app.planner.engine.model.SyncStamp
import app.planner.engine.model.Task
import app.planner.engine.model.Tier
import app.planner.engine.model.UnparsedTemplate
import app.planner.engine.schedule.DayFlag
import app.planner.engine.schedule.PlanWarning
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow

// Contracts (Stream 0). Change only through docs/redesign/CHANGES.md.
// Every layer codes against these interfaces; in-memory fakes live in app.planner.engine.fakes.

// ---- Storage (implemented by Stream F in Room) ----

interface TaskStore {
    fun observe(): Flow<List<Task>>
    suspend fun all(): List<Task>
    suspend fun get(id: String): Task?
    suspend fun upsert(tasks: List<Task>)
    suspend fun delete(id: String)
    fun observeNeedsALook(): Flow<List<UnparsedTemplate>>
    suspend fun setNeedsALook(items: List<UnparsedTemplate>)
}

interface ChangeSetStore {
    /** Proposals waiting for the approval card, newest first. */
    fun observePending(): Flow<List<ChangeSet>>
    suspend fun get(id: String): ChangeSet?
    suspend fun save(changeSet: ChangeSet)
    suspend fun saveApplied(record: AppliedRecord)
    suspend fun lastApplied(): AppliedRecord?
    fun observeLastApplied(): Flow<AppliedRecord?>
}

/** Local cache of calendar events from sync, and the snapshots used to detect the user's own edits. */
interface SnapshotStore {
    fun observe(from: LocalDate, toExclusive: LocalDate): Flow<List<EventSnapshot>>
    suspend fun events(from: LocalDate, toExclusive: LocalDate): List<EventSnapshot>
    suspend fun upsert(events: List<EventSnapshot>)
    suspend fun remove(refs: List<EventRef>)
    suspend fun syncToken(calendarId: String): String?
    suspend fun setSyncToken(calendarId: String, token: String?)
    fun observeSync(): Flow<SyncStamp?>
    suspend fun setSync(stamp: SyncStamp)
}

interface ExecutionLogStore {
    fun observeDay(date: LocalDate): Flow<List<ExecutionLog>>
    suspend fun forEvent(ref: EventRef, plannedStart: LocalDateTime): ExecutionLog?
    suspend fun upsert(log: ExecutionLog)
    /** For learning actual vs planned durations. */
    suspend fun history(since: LocalDate): List<ExecutionLog>
}

interface SettingsStore {
    fun observe(): Flow<Settings>
    suspend fun current(): Settings
    suspend fun update(transform: (Settings) -> Settings)
}

// ---- Calendar reading (implemented by Stream B; the read side is visible to the assistant) ----

data class CalendarInfo(
    val id: String,
    val name: String,
    /** Null when the name is not one of the category calendars. */
    val category: CalendarCategory?,
    val isPrayerTimes: Boolean,
    /** Category calendar the user allows the app to write to (with approval). Prayer times never. */
    val writable: Boolean,
)

enum class AuthState { SIGNED_IN, SIGNED_OUT, EXPIRED }

/** Read-only calendar access. Never modifies anything. */
interface CalendarReader {
    suspend fun calendars(): List<CalendarInfo>
    suspend fun events(from: LocalDate, toExclusive: LocalDate): List<EventSnapshot>
    suspend fun event(ref: EventRef): EventSnapshot?
}

// ---- Screen state (produced by Stream B's PlannerService, consumed by D, C and E) ----

data class TodayBlock(
    val ref: EventRef?,
    val taskId: String?,
    val title: String,
    val calendarName: String,
    val category: CalendarCategory?,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val tier: Tier,
    val prayerBufferMin: Int,
    val coveredPrayers: List<Prayer>,
    /** A fixed event (class, halaqa) rather than one of the app's task blocks. */
    val isFixed: Boolean,
    val chunkLabel: String? = null,
    val execution: ExecutionLog? = null,
)

data class TodayState(
    val date: LocalDate,
    val blocks: List<TodayBlock>,
    /** Index into [blocks] of the block happening now, if any. */
    val nowIndex: Int?,
    /** All-day items that are still unscheduled template tasks for today. */
    val unscheduled: List<Task>,
    val flags: Set<DayFlag>,
    val anchors: List<PrayerAnchor>,
    val auth: AuthState,
    val lastSync: SyncStamp?,
)

data class WeekDay(
    val date: LocalDate,
    val blocks: List<TodayBlock>,
    val endTime: LocalTime?,
    val maxTier: Tier,
    val flags: Set<DayFlag>,
    val overflowCount: Int,
)

data class WeekState(val start: LocalDate, val days: List<WeekDay>)

interface PlannerQueries {
    fun observeToday(): Flow<TodayState>
    fun observeWeek(start: LocalDate): Flow<WeekState>
    suspend fun week(start: LocalDate): WeekState
    suspend fun anchors(date: LocalDate): List<PrayerAnchor>
    /** Candidate events with ids; never modifies. */
    suspend fun findEvents(query: String, from: LocalDate, toExclusive: LocalDate): List<EventSnapshot>
    /** Pull from Google now (app open, foreground, pull-to-refresh, after apply). */
    suspend fun refresh(): SyncStamp
}

// ---- Proposing (implemented by Stream B over the engine; used by C, D, E). Builds ChangeSets; never writes. ----

/** Intent, not times. The engine works out every time. */
sealed interface ChangeDraft {
    data class AddTask(
        val title: String,
        /** Null: route by the category rules. */
        val category: CalendarCategory? = null,
        val durationMin: Int,
        val day: LocalDate? = null,
        /** A time the user said ("from 15:00"); passed through, never computed by the AI. */
        val earliestStart: LocalTime? = null,
        val afterPrayer: Prayer? = null,
        val deadline: LocalDate? = null,
        val splittable: Boolean = true,
    ) : ChangeDraft

    /** Plan an existing backlog task (onto [day], or wherever it fits). */
    data class ScheduleTask(val taskId: String, val day: LocalDate? = null) : ChangeDraft

    /** "Push tomorrow's lectures to Thursday": the engine finds the time on [toDay]. */
    data class MoveEvent(val ref: EventRef, val toDay: LocalDate, val earliestStart: LocalTime? = null) : ChangeDraft

    data class EditEvent(
        val ref: EventRef,
        val title: String? = null,
        val durationMin: Int? = null,
        val category: CalendarCategory? = null,
        val scope: RecurrenceScope = RecurrenceScope.THIS_INSTANCE,
    ) : ChangeDraft

    data class RemoveEvent(val ref: EventRef, val scope: RecurrenceScope = RecurrenceScope.THIS_INSTANCE) : ChangeDraft
}

sealed interface ReplanReason {
    /** "Halaqa ran over by 1h", or "Need 30 more" on an end check-in. */
    data class Overrun(val ref: EventRef, val extraMin: Int) : ReplanReason

    /** "Stop here" on an end check-in: the rest of the block becomes remaining work. */
    data class StoppedEarly(val ref: EventRef, val at: LocalDateTime) : ReplanReason

    /** The user moved blocks in Google Calendar and that broke a constraint. Their edit is final. */
    data class UserEdited(val refs: List<EventRef>) : ReplanReason

    data class Free(val text: String) : ReplanReason
}

sealed interface ProposalOutcome {
    /** Saved to ChangeSetStore and waiting for the approval card. */
    data class Proposed(val changeSet: ChangeSet, val warnings: List<PlanWarning>) : ProposalOutcome

    /** The request matched more than one event: ask which. */
    data class NeedsChoice(val question: String, val candidates: List<EventSnapshot>) : ProposalOutcome

    /** Breaks a hard rule; nothing was proposed. */
    data class Rejected(val violations: List<String>) : ProposalOutcome

    data class NothingToDo(val reason: String) : ProposalOutcome
}

interface ProposalPort {
    suspend fun propose(drafts: List<ChangeDraft>, source: ChangeSource, summary: String): ProposalOutcome
    suspend fun replan(reason: ReplanReason, source: ChangeSource): ProposalOutcome
    /** Plan unscheduled tasks into [days]. */
    suspend fun planDays(days: List<LocalDate>, source: ChangeSource): ProposalOutcome
    /** "Copy schedule template into next week". */
    suspend fun rollover(targetWeekStart: LocalDate, source: ChangeSource): ProposalOutcome
    /** Re-read the all-day template events for [from, toExclusive) into tasks; unparseable ones go to Needs a look. */
    suspend fun importTemplate(from: LocalDate, toExclusive: LocalDate): ImportReport
}

data class ImportReport(val imported: Int, val updated: Int, val needsALook: Int)

/** Clock seam so time-dependent code is testable. */
fun interface NowProvider {
    fun now(): LocalDateTime
}

// ---- Assistant (implemented by Stream C, consumed by D) ----

enum class ChatRole { USER, ASSISTANT, NOTE }

data class ChatTurn(
    val id: String,
    val role: ChatRole,
    val text: String,
    val at: java.time.Instant,
    /** A ChangeSet this turn proposed; the chat shows its approval card inline. */
    val proposalId: String? = null,
)

data class AssistantReply(val text: String, val proposalIds: List<String> = emptyList(), val error: String? = null)

interface AssistantPort {
    fun observeConversation(): Flow<List<ChatTurn>>
    suspend fun send(text: String): AssistantReply
    suspend fun clear()
}

// ---- Check-ins (implemented by Stream E; called by B after every sync or apply that changes events) ----

fun interface CheckInRescheduler {
    suspend fun reschedule()
}
