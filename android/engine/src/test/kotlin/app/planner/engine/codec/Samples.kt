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
import app.planner.engine.changeset.PlannerTags
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
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Values with every field set away from its default, so a dropped field shows up as a round-trip failure. */
object Samples {
    val day: LocalDate = LocalDate.of(2026, 11, 3)
    val ref = EventRef("cal-academic", "evt0001")
    val ref2 = EventRef("cal-personal", "evt0002")

    val timed = EventTime.Timed(day.atTime(10, 15), day.atTime(12, 30, 5, 123_456_789))
    val allDay = EventTime.AllDay(day, day.plusDays(1))

    val task = Task(
        id = "6f1c2c1e-0000-4000-8000-000000000001",
        title = "Arabic hw",
        category = CalendarCategory.QURAN_ARABIC,
        durationMin = 90,
        assignedDay = day,
        earliestStart = LocalTime.of(14, 0),
        afterPrayer = Prayer.ASR,
        deadline = day.plusDays(4),
        splittable = false,
        minChunkMin = 30,
        priority = 3,
        kind = TaskKind.PASSIVE,
        status = TaskStatus.SCHEDULED,
        calendarEventIds = listOf(ref, ref2),
        sourceEvent = EventRef("cal-quran", "allday1"),
        notes = "Teacher-set, \"page 12\"\nand a newline",
    )

    val minimalTask = Task(id = "t-min", title = "Gym", category = CalendarCategory.FITNESS, durationMin = 60)

    val settings = Settings(
        wakeTime = LocalTime.of(8, 45),
        normalEnd = LocalTime.of(17, 30),
        extendedEnd = LocalTime.of(19, 45),
        hardCap = LocalTime.of(21, 30),
        prayerSlotMin = 20,
        prayerOffsetMin = mapOf(Prayer.DHUHR to 15, Prayer.ASR to -5),
        minChunkMin = 40,
        writableCategories = setOf(CalendarCategory.ACADEMIC, CalendarCategory.PERSONAL),
        catchUpDay = DayOfWeek.SATURDAY,
        convertAllDayOnSchedule = false,
        stripWeekTagsOnRollover = true,
        recordingDelayMin = 90,
        notifications = NotificationPrefs(
            checkInsEnabled = false,
            mergeBackToBack = false,
            startOnlyUnderMin = 30,
            quietDuringPrayer = false,
            midTaskPrayerPing = false,
            askFocusOnDone = false,
            snoozeMin = 5,
            extendMin = 15,
            nightBeforeReview = LocalTime.of(22, 5),
            sundayPrompts = false,
        ),
        zone = ZoneId.of("America/New_York"),
        assistantModel = "claude-test-model",
    )

    fun snapshot(
        r: EventRef = ref,
        time: EventTime = timed,
        title: String = "PintOS (1/2)",
    ) = EventSnapshot(
        ref = r,
        calendarName = "Academic ",
        title = title,
        time = time,
        description = "Task: 2h. Includes 15 min for Dhuhr.",
        etag = "\"3181161784712000\"",
        updated = Instant.parse("2026-11-01T09:10:11.123456Z"),
        recurringEventId = "series-1",
        organiserIsExternal = true,
        privateProps = mapOf(PlannerTags.PLANNER to "1", PlannerTags.TASK_ID to task.id, PlannerTags.CHUNK to "1"),
    )

    fun draft(time: EventTime = timed) = EventDraft(
        calendarId = "cal-academic",
        title = "PintOS (1/2)",
        time = time,
        description = "Extended hours (after 18:00)",
        privateProps = mapOf(PlannerTags.PLANNER to "1", PlannerTags.REV to "2"),
    )

    val add = Change(
        id = "c-add",
        type = ChangeType.ADD,
        after = draft(),
        newEventId = "abcdefghijklmnopqrstuv0123456789",
        flags = setOf(ChangeFlag.EXTENDED_DAY, ChangeFlag.INTERPOLATED_PRAYER),
        taskId = task.id,
        chunkIndex = 1,
        note = "Includes 15 min for Asr",
    )
    val move = Change(
        id = "c-move",
        type = ChangeType.MOVE,
        target = snapshot(),
        after = draft(EventTime.Timed(day.plusDays(1).atTime(14, 0), day.plusDays(1).atTime(16, 0))),
        flags = setOf(ChangeFlag.RECURRING),
        scope = RecurrenceScope.THIS_AND_FOLLOWING,
        status = ChangeStatus.APPROVED,
    )
    val edit = Change(
        id = "c-edit",
        type = ChangeType.EDIT,
        target = snapshot(time = allDay, title = "1. PintOS (3h)"),
        after = draft(),
        flags = setOf(ChangeFlag.CONVERTS_ALL_DAY),
        scope = RecurrenceScope.WHOLE_SERIES,
        status = ChangeStatus.STALE,
        problem = "etag changed",
    )
    val remove = Change(id = "c-remove", type = ChangeType.REMOVE, target = snapshot(ref2), status = ChangeStatus.REJECTED)

    val changeSet = ChangeSet(
        id = "cs-1",
        source = ChangeSource.ASSISTANT,
        summary = "Halaqa ran over: move 2 blocks",
        changes = listOf(add, move, edit, remove),
        createdAt = Instant.parse("2026-11-02T18:00:00.000000001Z"),
        status = ChangeStatus.APPROVED,
        notes = listOf("Halaqa assumed: gap kept", "1h overflow to Sunday"),
    )

    val appliedRecord = AppliedRecord(
        changeSetId = "cs-1",
        appliedAt = Instant.parse("2026-11-02T18:05:30Z"),
        results = listOf(
            ChangeResult("c-add", ChangeStatus.VERIFIED, ref),
            ChangeResult("c-move", ChangeStatus.FAILED, ref2, problem = "start differs"),
            ChangeResult("c-remove", ChangeStatus.STALE, null),
        ),
        inverse = listOf(
            InverseOp.Restore(snapshot(ref2, allDay)),
            InverseOp.Revert(ref, draft()),
            InverseOp.Delete(ref),
        ),
        undone = true,
    )

    val log = ExecutionLog(
        id = "log-1",
        taskId = task.id,
        eventRef = ref,
        title = "PintOS (1/2)",
        plannedStart = day.atTime(10, 0),
        plannedEnd = day.atTime(12, 15),
        actualStart = day.atTime(10, 7, 30),
        actualEnd = day.atTime(12, 40),
        focus1to5 = 4,
        outcome = ExecutionOutcome.EXTENDED,
    )
}
