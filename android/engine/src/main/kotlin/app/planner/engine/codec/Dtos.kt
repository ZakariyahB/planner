@file:UseSerializers(LocalDateIso::class, LocalTimeIso::class, LocalDateTimeIso::class, InstantIso::class)

package app.planner.engine.codec

import app.planner.engine.changeset.AppliedRecord
import app.planner.engine.changeset.Change
import app.planner.engine.changeset.ChangeFlag
import app.planner.engine.changeset.ChangeResult
import app.planner.engine.changeset.ChangeSet
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.changeset.ChangeStatus
import app.planner.engine.changeset.ChangeType
import app.planner.engine.changeset.EventDraft
import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.EventTime
import app.planner.engine.changeset.InverseOp
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.EventRef
import app.planner.engine.model.ExecutionLog
import app.planner.engine.model.ExecutionOutcome
import app.planner.engine.model.NotificationPrefs
import app.planner.engine.model.Prayer
import app.planner.engine.model.Settings
import app.planner.engine.model.Task
import app.planner.engine.model.TaskKind
import app.planner.engine.model.TaskStatus
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers

// The stored JSON shape. The domain types are contracts and carry no serialization annotations, so each one has a
// DTO here with a pair of mappers. Rules that make decoding tolerant:
// - every field that has a default in the domain type has the same default here (taken from the domain type, so
//   the two cannot drift), so a field missing from old data decodes to that default;
// - enum sets and enum-keyed maps are stored as names and unknown names are dropped (see enumOrNull);
// - unknown keys are ignored and unknown single enum values fall back to the default (PlannerCodec.json settings).

private val TASK_DEFAULTS = Task(id = "", title = "", category = CalendarCategory.PERSONAL, durationMin = 0)
private val SETTINGS_DEFAULTS = Settings()
private val PREFS_DEFAULTS = NotificationPrefs()
private val SNAPSHOT_DEFAULTS =
    EventSnapshot(EventRef("", ""), "", "", EventTime.AllDay(LocalDate.MIN, LocalDate.MIN), etag = "", updated = Instant.EPOCH)

// ---- Task ----

@Serializable
internal data class TaskDto(
    val id: String,
    val title: String,
    /** "Anything else -> Personal" (newplan section 3), so that is also the fallback for an unknown category. */
    val category: CalendarCategory = CalendarCategory.PERSONAL,
    val durationMin: Int,
    val assignedDay: LocalDate? = TASK_DEFAULTS.assignedDay,
    val earliestStart: LocalTime? = TASK_DEFAULTS.earliestStart,
    val afterPrayer: Prayer? = TASK_DEFAULTS.afterPrayer,
    val deadline: LocalDate? = TASK_DEFAULTS.deadline,
    val splittable: Boolean = TASK_DEFAULTS.splittable,
    val minChunkMin: Int = TASK_DEFAULTS.minChunkMin,
    val priority: Int = TASK_DEFAULTS.priority,
    val kind: TaskKind = TASK_DEFAULTS.kind,
    val status: TaskStatus = TASK_DEFAULTS.status,
    val calendarEventIds: List<EventRef> = TASK_DEFAULTS.calendarEventIds,
    val sourceEvent: EventRef? = TASK_DEFAULTS.sourceEvent,
    val notes: String? = TASK_DEFAULTS.notes,
)

internal fun Task.toDto() = TaskDto(
    id, title, category, durationMin, assignedDay, earliestStart, afterPrayer, deadline, splittable, minChunkMin,
    priority, kind, status, calendarEventIds, sourceEvent, notes,
)

internal fun TaskDto.toModel() = Task(
    id, title, category, durationMin, assignedDay, earliestStart, afterPrayer, deadline, splittable, minChunkMin,
    priority, kind, status, calendarEventIds, sourceEvent, notes,
)

// ---- Settings ----

@Serializable
internal data class NotificationPrefsDto(
    val checkInsEnabled: Boolean = PREFS_DEFAULTS.checkInsEnabled,
    val mergeBackToBack: Boolean = PREFS_DEFAULTS.mergeBackToBack,
    val startOnlyUnderMin: Int = PREFS_DEFAULTS.startOnlyUnderMin,
    val quietDuringPrayer: Boolean = PREFS_DEFAULTS.quietDuringPrayer,
    val midTaskPrayerPing: Boolean = PREFS_DEFAULTS.midTaskPrayerPing,
    val askFocusOnDone: Boolean = PREFS_DEFAULTS.askFocusOnDone,
    val snoozeMin: Int = PREFS_DEFAULTS.snoozeMin,
    val extendMin: Int = PREFS_DEFAULTS.extendMin,
    val nightBeforeReview: LocalTime = PREFS_DEFAULTS.nightBeforeReview,
    val sundayPrompts: Boolean = PREFS_DEFAULTS.sundayPrompts,
    val nightBeforeReviewEnabled: Boolean = PREFS_DEFAULTS.nightBeforeReviewEnabled,
    val sundayDeadlinesAt: LocalTime = PREFS_DEFAULTS.sundayDeadlinesAt,
    /** DayOfWeek name; an unknown name falls back to the default. */
    val fajrPromptDay: String = PREFS_DEFAULTS.fajrPromptDay.name,
    val fajrPromptAt: LocalTime = PREFS_DEFAULTS.fajrPromptAt,
)

internal fun NotificationPrefs.toDto() = NotificationPrefsDto(
    checkInsEnabled, mergeBackToBack, startOnlyUnderMin, quietDuringPrayer, midTaskPrayerPing, askFocusOnDone,
    snoozeMin, extendMin, nightBeforeReview, sundayPrompts,
    nightBeforeReviewEnabled, sundayDeadlinesAt, fajrPromptDay.name, fajrPromptAt,
)

internal fun NotificationPrefsDto.toModel() = NotificationPrefs(
    checkInsEnabled, mergeBackToBack, startOnlyUnderMin, quietDuringPrayer, midTaskPrayerPing, askFocusOnDone,
    snoozeMin, extendMin, nightBeforeReview, sundayPrompts,
    nightBeforeReviewEnabled, sundayDeadlinesAt, enumOrNull<DayOfWeek>(fajrPromptDay) ?: PREFS_DEFAULTS.fajrPromptDay, fajrPromptAt,
)

@Serializable
internal data class SettingsDto(
    val wakeTime: LocalTime = SETTINGS_DEFAULTS.wakeTime,
    val normalEnd: LocalTime = SETTINGS_DEFAULTS.normalEnd,
    val extendedEnd: LocalTime = SETTINGS_DEFAULTS.extendedEnd,
    val hardCap: LocalTime = SETTINGS_DEFAULTS.hardCap,
    val prayerSlotMin: Int = SETTINGS_DEFAULTS.prayerSlotMin,
    /** Keyed by Prayer name; unknown names are dropped. */
    val prayerOffsetMin: Map<String, Int> = SETTINGS_DEFAULTS.prayerOffsetMin.mapKeys { it.key.name },
    val minChunkMin: Int = SETTINGS_DEFAULTS.minChunkMin,
    /** CalendarCategory names; unknown names are dropped. */
    val writableCategories: List<String> = SETTINGS_DEFAULTS.writableCategories.sortedBy { it.ordinal }.map { it.name },
    /** DayOfWeek name; an unknown name falls back to the default. */
    val catchUpDay: String = SETTINGS_DEFAULTS.catchUpDay.name,
    val convertAllDayOnSchedule: Boolean = SETTINGS_DEFAULTS.convertAllDayOnSchedule,
    val stripWeekTagsOnRollover: Boolean = SETTINGS_DEFAULTS.stripWeekTagsOnRollover,
    val recordingDelayMin: Int = SETTINGS_DEFAULTS.recordingDelayMin,
    val sleepNeedMin: Int = SETTINGS_DEFAULTS.sleepNeedMin,
    val notifications: NotificationPrefsDto = NotificationPrefsDto(),
    /** IANA zone id; an id this device does not know falls back to the default zone. */
    val zone: String = SETTINGS_DEFAULTS.zone.id,
    val assistantModel: String = SETTINGS_DEFAULTS.assistantModel,
)

internal fun Settings.toDto() = SettingsDto(
    wakeTime = wakeTime,
    normalEnd = normalEnd,
    extendedEnd = extendedEnd,
    hardCap = hardCap,
    prayerSlotMin = prayerSlotMin,
    prayerOffsetMin = prayerOffsetMin.entries.sortedBy { it.key.ordinal }.associate { it.key.name to it.value },
    minChunkMin = minChunkMin,
    writableCategories = writableCategories.sortedBy { it.ordinal }.map { it.name },
    catchUpDay = catchUpDay.name,
    convertAllDayOnSchedule = convertAllDayOnSchedule,
    stripWeekTagsOnRollover = stripWeekTagsOnRollover,
    recordingDelayMin = recordingDelayMin,
    sleepNeedMin = sleepNeedMin,
    notifications = notifications.toDto(),
    zone = zone.id,
    assistantModel = assistantModel,
)

internal fun SettingsDto.toModel() = Settings(
    wakeTime = wakeTime,
    normalEnd = normalEnd,
    extendedEnd = extendedEnd,
    hardCap = hardCap,
    prayerSlotMin = prayerSlotMin,
    prayerOffsetMin = prayerOffsetMin.mapNotNull { (k, v) -> enumOrNull<Prayer>(k)?.let { it to v } }.toMap(),
    minChunkMin = minChunkMin,
    writableCategories = writableCategories.mapNotNull { enumOrNull<CalendarCategory>(it) }.toSet(),
    catchUpDay = enumOrNull<DayOfWeek>(catchUpDay) ?: SETTINGS_DEFAULTS.catchUpDay,
    convertAllDayOnSchedule = convertAllDayOnSchedule,
    stripWeekTagsOnRollover = stripWeekTagsOnRollover,
    recordingDelayMin = recordingDelayMin,
    sleepNeedMin = sleepNeedMin,
    notifications = notifications.toModel(),
    zone = zoneOrDefault(zone),
    assistantModel = assistantModel,
)

private fun zoneOrDefault(id: String): ZoneId =
    try {
        ZoneId.of(id)
    } catch (e: DateTimeException) {
        SETTINGS_DEFAULTS.zone
    }

// ---- Event time, snapshot, draft ----

@Serializable
internal sealed class EventTimeDto {
    @Serializable
    @SerialName("timed")
    data class Timed(val start: LocalDateTime, val end: LocalDateTime) : EventTimeDto()

    @Serializable
    @SerialName("allDay")
    data class AllDay(val start: LocalDate, val endExclusive: LocalDate) : EventTimeDto()
}

internal fun EventTime.toDto(): EventTimeDto = when (this) {
    is EventTime.Timed -> EventTimeDto.Timed(start, end)
    is EventTime.AllDay -> EventTimeDto.AllDay(start, endExclusive)
}

internal fun EventTimeDto.toModel(): EventTime = when (this) {
    is EventTimeDto.Timed -> EventTime.Timed(start, end)
    is EventTimeDto.AllDay -> EventTime.AllDay(start, endExclusive)
}

@Serializable
internal data class EventSnapshotDto(
    val ref: EventRef,
    val calendarName: String = SNAPSHOT_DEFAULTS.calendarName,
    val title: String = SNAPSHOT_DEFAULTS.title,
    val time: EventTimeDto,
    val description: String? = SNAPSHOT_DEFAULTS.description,
    /** A missing etag or updated time never matches Google's, so pre-flight marks the change STALE: the safe side. */
    val etag: String = SNAPSHOT_DEFAULTS.etag,
    val updated: Instant = SNAPSHOT_DEFAULTS.updated,
    val recurringEventId: String? = SNAPSHOT_DEFAULTS.recurringEventId,
    val organiserIsExternal: Boolean = SNAPSHOT_DEFAULTS.organiserIsExternal,
    val privateProps: Map<String, String> = SNAPSHOT_DEFAULTS.privateProps,
)

internal fun EventSnapshot.toDto() = EventSnapshotDto(
    ref, calendarName, title, time.toDto(), description, etag, updated, recurringEventId, organiserIsExternal,
    privateProps.toSortedMap(),
)

internal fun EventSnapshotDto.toModel() = EventSnapshot(
    ref, calendarName, title, time.toModel(), description, etag, updated, recurringEventId, organiserIsExternal,
    privateProps,
)

@Serializable
internal data class EventDraftDto(
    val calendarId: String,
    val title: String = "",
    val time: EventTimeDto,
    val description: String? = null,
    val privateProps: Map<String, String> = emptyMap(),
)

internal fun EventDraft.toDto() = EventDraftDto(calendarId, title, time.toDto(), description, privateProps.toSortedMap())

internal fun EventDraftDto.toModel() = EventDraft(calendarId, title, time.toModel(), description, privateProps)

// ---- Change, ChangeSet ----

@Serializable
internal data class ChangeDto(
    val id: String,
    val type: ChangeType,
    val target: EventSnapshotDto? = null,
    val after: EventDraftDto? = null,
    val newEventId: String? = null,
    /** ChangeFlag names; unknown names are dropped. */
    val flags: List<String> = emptyList(),
    val scope: RecurrenceScope = RecurrenceScope.THIS_INSTANCE,
    val status: ChangeStatus = ChangeStatus.PROPOSED,
    val taskId: String? = null,
    val chunkIndex: Int? = null,
    val note: String? = null,
    val problem: String? = null,
)

internal fun Change.toDto() = ChangeDto(
    id, type, target?.toDto(), after?.toDto(), newEventId, flags.sortedBy { it.ordinal }.map { it.name }, scope,
    status, taskId, chunkIndex, note, problem,
)

internal fun ChangeDto.toModel() = Change(
    id, type, target?.toModel(), after?.toModel(), newEventId,
    flags.mapNotNull { enumOrNull<ChangeFlag>(it) }.toSet(), scope, status, taskId, chunkIndex, note, problem,
)

@Serializable
internal data class ChangeSetDto(
    val id: String,
    val source: ChangeSource = ChangeSource.USER,
    val summary: String = "",
    val changes: List<ChangeDto> = emptyList(),
    val createdAt: Instant,
    val status: ChangeStatus = ChangeStatus.PROPOSED,
    val notes: List<String> = emptyList(),
)

internal fun ChangeSet.toDto() = ChangeSetDto(id, source, summary, changes.map { it.toDto() }, createdAt, status, notes)

internal fun ChangeSetDto.toModel() = ChangeSet(id, source, summary, changes.map { it.toModel() }, createdAt, status, notes)

// ---- Applied records and undo ----

@Serializable
internal sealed class InverseOpDto {
    @Serializable
    @SerialName("delete")
    data class Delete(val ref: EventRef) : InverseOpDto()

    @Serializable
    @SerialName("restore")
    data class Restore(val snapshot: EventSnapshotDto) : InverseOpDto()

    @Serializable
    @SerialName("revert")
    data class Revert(val ref: EventRef, val before: EventDraftDto) : InverseOpDto()
}

internal fun InverseOp.toDto(): InverseOpDto = when (this) {
    is InverseOp.Delete -> InverseOpDto.Delete(ref)
    is InverseOp.Restore -> InverseOpDto.Restore(snapshot.toDto())
    is InverseOp.Revert -> InverseOpDto.Revert(ref, before.toDto())
}

internal fun InverseOpDto.toModel(): InverseOp = when (this) {
    is InverseOpDto.Delete -> InverseOp.Delete(ref)
    is InverseOpDto.Restore -> InverseOp.Restore(snapshot.toModel())
    is InverseOpDto.Revert -> InverseOp.Revert(ref, before.toModel())
}

@Serializable
internal data class ChangeResultDto(
    val changeId: String,
    /** An unknown status reads as FAILED: never claim a write was verified when we cannot tell. */
    val status: ChangeStatus = ChangeStatus.FAILED,
    val ref: EventRef? = null,
    val problem: String? = null,
)

internal fun ChangeResult.toDto() = ChangeResultDto(changeId, status, ref, problem)

internal fun ChangeResultDto.toModel() = ChangeResult(changeId, status, ref, problem)

@Serializable
internal data class AppliedRecordDto(
    val changeSetId: String,
    val appliedAt: Instant,
    val results: List<ChangeResultDto> = emptyList(),
    val inverse: List<InverseOpDto> = emptyList(),
    val undone: Boolean = false,
)

internal fun AppliedRecord.toDto() =
    AppliedRecordDto(changeSetId, appliedAt, results.map { it.toDto() }, inverse.map { it.toDto() }, undone)

internal fun AppliedRecordDto.toModel() =
    AppliedRecord(changeSetId, appliedAt, results.map { it.toModel() }, inverse.map { it.toModel() }, undone)

// ---- Execution log ----

@Serializable
internal data class ExecutionLogDto(
    val id: String,
    val taskId: String? = null,
    val eventRef: EventRef? = null,
    val title: String = "",
    val plannedStart: LocalDateTime,
    val plannedEnd: LocalDateTime,
    val actualStart: LocalDateTime? = null,
    val actualEnd: LocalDateTime? = null,
    val focus1to5: Int? = null,
    val outcome: ExecutionOutcome = ExecutionOutcome.PENDING,
)

internal fun ExecutionLog.toDto() =
    ExecutionLogDto(id, taskId, eventRef, title, plannedStart, plannedEnd, actualStart, actualEnd, focus1to5, outcome)

internal fun ExecutionLogDto.toModel() =
    ExecutionLog(id, taskId, eventRef, title, plannedStart, plannedEnd, actualStart, actualEnd, focus1to5, outcome)
