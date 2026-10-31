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
import app.planner.engine.model.SyncStamp
import app.planner.engine.model.Task
import app.planner.engine.model.TaskKind
import app.planner.engine.model.TaskStatus
import app.planner.engine.model.UnparsedTemplate
import app.planner.engine.ports.ChangeSetStore
import app.planner.engine.ports.ExecutionLogStore
import app.planner.engine.ports.SettingsStore
import app.planner.engine.ports.SnapshotStore
import app.planner.engine.ports.TaskStore
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The behaviour every implementation of the storage ports must have, written once in plain code (no test framework)
 * so it runs against the in-memory fakes on the JVM (StoreContractTest) and against the Room stores on a device
 * (an instrumented test that builds an in-memory PlannerStoreDatabase per case; see docs/redesign/notes/F.md).
 *
 * The fakes in app.planner.engine.fakes define the behaviour; a case that fails on the fakes is a bug in the case.
 * Where the ports leave order unspecified (TaskStore.all, SnapshotStore.events) the cases compare as sets.
 */
class StoreContract(private val fresh: () -> Stores) {
    class Stores(
        val tasks: TaskStore,
        val changeSets: ChangeSetStore,
        val snapshots: SnapshotStore,
        val logs: ExecutionLogStore,
        val settings: SettingsStore,
    )

    class Case(val name: String, internal val body: suspend Stores.() -> Unit) {
        override fun toString() = name
    }

    /** Runs one case on fresh, empty stores. Throws [AssertionError] when the stores misbehave. */
    suspend fun run(case: Case) = case.body(fresh())

    /** Runs every case; returns "name: problem" for each one that failed (empty when all pass). */
    suspend fun runAll(): List<String> = cases.mapNotNull { case ->
        try {
            run(case)
            null
        } catch (e: Throwable) {
            "${case.name}: ${e.message ?: e::class.simpleName}"
        }
    }

    val cases: List<Case> = listOf(
        // ---- TaskStore ----
        Case("tasks: a fresh store is empty") {
            same(emptyList(), tasks.all(), "all")
            same(null, tasks.get(F.task.id), "get")
            same(emptyList(), tasks.observe().first(), "observe")
            same(emptyList(), tasks.observeNeedsALook().first(), "needs a look")
        },
        Case("tasks: upsert then get and all return every field") {
            tasks.upsert(listOf(F.task, F.task2))
            same(F.task, tasks.get(F.task.id), "get")
            same(setOf(F.task, F.task2), tasks.all().toSet(), "all")
            same(2, tasks.all().size, "count")
        },
        Case("tasks: upsert with an existing id replaces it") {
            tasks.upsert(listOf(F.task))
            val done = F.task.copy(status = TaskStatus.DONE, calendarEventIds = emptyList(), notes = null)
            tasks.upsert(listOf(done))
            same(done, tasks.get(F.task.id), "get")
            same(listOf(done), tasks.all(), "all")
        },
        Case("tasks: upserting an empty list changes nothing") {
            tasks.upsert(listOf(F.task))
            tasks.upsert(emptyList())
            same(listOf(F.task), tasks.all(), "all")
        },
        Case("tasks: delete removes one task and ignores unknown ids") {
            tasks.upsert(listOf(F.task, F.task2))
            tasks.delete(F.task.id)
            tasks.delete("no-such-task")
            same(null, tasks.get(F.task.id), "deleted")
            same(listOf(F.task2), tasks.all(), "rest")
        },
        Case("tasks: observe follows writes") {
            tasks.upsert(listOf(F.task))
            tasks.observe().await("observe after upsert") { it.toSet() == setOf(F.task) }
            tasks.upsert(listOf(F.task2))
            tasks.observe().await("observe after second upsert") { it.toSet() == setOf(F.task, F.task2) }
            tasks.delete(F.task.id)
            tasks.observe().await("observe after delete") { it == listOf(F.task2) }
        },
        Case("tasks: needs a look is replaced as a whole and keeps its order") {
            tasks.setNeedsALook(listOf(F.unparsed1, F.unparsed2))
            tasks.observeNeedsALook().await("first list") { it == listOf(F.unparsed1, F.unparsed2) }
            tasks.setNeedsALook(listOf(F.unparsed2))
            tasks.observeNeedsALook().await("replaced") { it == listOf(F.unparsed2) }
            tasks.setNeedsALook(emptyList())
            tasks.observeNeedsALook().await("cleared") { it.isEmpty() }
        },
        Case("tasks: needs a look is separate from tasks") {
            tasks.setNeedsALook(listOf(F.unparsed1))
            same(emptyList(), tasks.all(), "tasks")
            tasks.upsert(listOf(F.task))
            same(listOf(F.unparsed1), tasks.observeNeedsALook().first(), "needs a look")
        },

        // ---- ChangeSetStore ----
        Case("change sets: a fresh store has nothing pending or applied") {
            same(emptyList(), changeSets.observePending().first(), "pending")
            same(null, changeSets.get("cs-1"), "get")
            same(null, changeSets.lastApplied(), "last applied")
            same(null, changeSets.observeLastApplied().first(), "observe last applied")
        },
        Case("change sets: save then get returns every change and field") {
            changeSets.save(F.changeSet)
            same(F.changeSet, changeSets.get(F.changeSet.id), "get")
        },
        Case("change sets: pending holds only proposals, newest first") {
            val old = F.changeSet.copy(id = "old", status = ChangeStatus.PROPOSED, createdAt = F.t0)
            val mid = F.changeSet.copy(id = "mid", status = ChangeStatus.PROPOSED, createdAt = F.t0.plusNanos(1))
            val new = F.changeSet.copy(id = "new", status = ChangeStatus.PROPOSED, createdAt = F.t0.plusSeconds(60))
            val applied = F.changeSet.copy(id = "applied", status = ChangeStatus.APPLIED, createdAt = F.t0.plusSeconds(120))
            changeSets.save(mid)
            changeSets.save(applied)
            changeSets.save(new)
            changeSets.save(old)
            changeSets.observePending().await("pending") { it.map(ChangeSet::id) == listOf("new", "mid", "old") }
        },
        Case("change sets: saving again with a new status replaces it and leaves pending") {
            val p = F.changeSet.copy(status = ChangeStatus.PROPOSED)
            changeSets.save(p)
            changeSets.observePending().await("pending") { it == listOf(p) }
            val approved = p.copy(status = ChangeStatus.APPROVED, changes = p.changes.map { it.copy(status = ChangeStatus.APPROVED) })
            changeSets.save(approved)
            changeSets.observePending().await("pending after approval") { it.isEmpty() }
            same(approved, changeSets.get(p.id), "get")
        },
        Case("change sets: last applied is the most recently saved record") {
            val a = F.applied.copy(changeSetId = "a", appliedAt = F.t0.plusSeconds(10))
            val b = F.applied.copy(changeSetId = "b", appliedAt = F.t0)
            changeSets.saveApplied(a)
            same(a, changeSets.lastApplied(), "after a")
            changeSets.saveApplied(b)
            same(b, changeSets.lastApplied(), "after b (saved later, applied earlier)")
            changeSets.observeLastApplied().await("observe") { it == b }
        },
        Case("change sets: re-saving a record replaces it and makes it the last") {
            val a = F.applied.copy(changeSetId = "a", undone = false)
            val b = F.applied.copy(changeSetId = "b", undone = false)
            changeSets.saveApplied(a)
            changeSets.saveApplied(b)
            val undoneA = a.copy(undone = true)
            changeSets.saveApplied(undoneA)
            same(undoneA, changeSets.lastApplied(), "last")
            changeSets.observeLastApplied().await("observe") { it == undoneA }
            val undoneB = b.copy(undone = true)
            changeSets.saveApplied(undoneB)
            same(undoneB, changeSets.lastApplied(), "last after re-saving b")
        },
        Case("change sets: applied records keep results and inverse ops in order") {
            changeSets.saveApplied(F.applied)
            same(F.applied, changeSets.lastApplied(), "last applied")
        },

        // ---- SnapshotStore ----
        Case("snapshots: a fresh store is empty with no sync state") {
            same(emptyList(), snapshots.events(F.day, F.day.plusDays(7)), "events")
            same(null, snapshots.syncToken("cal-academic"), "token")
            same(null, snapshots.observeSync().first(), "sync")
        },
        Case("snapshots: events returns what overlaps the date range") {
            snapshots.upsert(F.rangeEvents.values.toList())
            val got = snapshots.events(F.day, F.day.plusDays(1)).map { it.ref.eventId }.toSet()
            same(setOf("timed-in", "all-day-in", "spans-in", "multi-day", "ends-at-midnight"), got, "one day")
            val week = snapshots.events(F.day.minusDays(1), F.day.plusDays(7)).map { it.ref.eventId }.toSet()
            same(F.rangeEvents.keys - "far-future", week, "week")
            same(emptyList(), snapshots.events(F.day.plusDays(100), F.day.plusDays(101)), "empty range")
        },
        Case("snapshots: the range end is exclusive") {
            snapshots.upsert(listOf(F.rangeEvents.getValue("next-day")))
            same(emptyList(), snapshots.events(F.day, F.day.plusDays(1)), "before")
            same(1, snapshots.events(F.day.plusDays(1), F.day.plusDays(2)).size, "on its day")
        },
        Case("snapshots: every field survives the store") {
            val all = F.rangeEvents.values.toSet()
            snapshots.upsert(all.toList())
            same(all - F.rangeEvents.getValue("far-future"), snapshots.events(F.day.minusDays(1), F.day.plusDays(7)).toSet(), "events")
        },
        Case("snapshots: upsert with the same ref replaces the event") {
            val first = F.snapshot("e1", EventTime.Timed(F.day.atTime(9, 0), F.day.atTime(10, 0)))
            snapshots.upsert(listOf(first))
            val moved = first.copy(time = EventTime.Timed(F.day.plusDays(2).atTime(9, 0), F.day.plusDays(2).atTime(10, 0)), etag = "e2")
            snapshots.upsert(listOf(moved))
            same(emptyList(), snapshots.events(F.day, F.day.plusDays(1)), "old day")
            same(listOf(moved), snapshots.events(F.day.plusDays(2), F.day.plusDays(3)), "new day")
        },
        Case("snapshots: the same event id in two calendars is two events") {
            val a = F.snapshot("same", EventTime.AllDay(F.day, F.day.plusDays(1)), calendarId = "cal-a")
            val b = F.snapshot("same", EventTime.AllDay(F.day, F.day.plusDays(1)), calendarId = "cal-b")
            snapshots.upsert(listOf(a, b))
            same(setOf(a, b), snapshots.events(F.day, F.day.plusDays(1)).toSet(), "events")
            snapshots.remove(listOf(a.ref))
            same(listOf(b), snapshots.events(F.day, F.day.plusDays(1)), "after removing a")
        },
        Case("snapshots: remove drops listed refs and ignores unknown ones") {
            snapshots.upsert(F.rangeEvents.values.toList())
            snapshots.remove(listOf(EventRef("cal-academic", "timed-in"), EventRef("cal-academic", "nope"), EventRef("x", "y")))
            snapshots.remove(emptyList())
            val got = snapshots.events(F.day, F.day.plusDays(1)).map { it.ref.eventId }.toSet()
            same(setOf("all-day-in", "spans-in", "multi-day", "ends-at-midnight"), got, "events")
        },
        Case("snapshots: observe follows writes within its range") {
            val e = F.snapshot("watched", EventTime.Timed(F.day.atTime(9, 0), F.day.atTime(10, 0)))
            val flow = snapshots.observe(F.day, F.day.plusDays(1))
            flow.await("empty") { it.isEmpty() }
            snapshots.upsert(listOf(e, F.rangeEvents.getValue("far-future")))
            flow.await("added") { it == listOf(e) }
            snapshots.remove(listOf(e.ref))
            flow.await("removed") { it.isEmpty() }
        },
        Case("snapshots: sync tokens are per calendar and can be cleared") {
            snapshots.setSyncToken("cal-a", "token-a")
            snapshots.setSyncToken("cal-b", "token-b")
            same("token-a", snapshots.syncToken("cal-a"), "a")
            same("token-b", snapshots.syncToken("cal-b"), "b")
            snapshots.setSyncToken("cal-a", "token-a2")
            same("token-a2", snapshots.syncToken("cal-a"), "a replaced")
            snapshots.setSyncToken("cal-a", null)
            same(null, snapshots.syncToken("cal-a"), "a cleared")
            same("token-b", snapshots.syncToken("cal-b"), "b kept")
        },
        Case("snapshots: the sync stamp is replaced and observed") {
            val ok = SyncStamp(Instant.parse("2026-11-03T08:00:00.123456789Z"), ok = true)
            val failed = SyncStamp(Instant.parse("2026-11-03T09:00:00Z"), ok = false, message = "Sign in again")
            snapshots.setSync(ok)
            snapshots.observeSync().await("ok") { it == ok }
            snapshots.setSync(failed)
            snapshots.observeSync().await("failed") { it == failed }
        },

        // ---- ExecutionLogStore ----
        Case("logs: a fresh store is empty") {
            same(emptyList(), logs.observeDay(F.day).first(), "day")
            same(emptyList(), logs.history(F.day.minusYears(100)), "history")
            same(null, logs.forEvent(F.ref, F.day.atTime(10, 0)), "for event")
        },
        Case("logs: for event matches the ref and the exact planned start") {
            logs.upsert(F.log)
            same(F.log, logs.forEvent(F.log.eventRef!!, F.log.plannedStart), "match")
            same(null, logs.forEvent(F.log.eventRef!!, F.log.plannedStart.plusMinutes(1)), "other start")
            same(null, logs.forEvent(F.log.eventRef!!, F.log.plannedStart.plusNanos(1)), "start off by a nanosecond")
            same(null, logs.forEvent(EventRef("cal-other", F.log.eventRef!!.eventId), F.log.plannedStart), "other calendar")
        },
        Case("logs: upsert with the same id replaces it") {
            logs.upsert(F.log.copy(outcome = ExecutionOutcome.STARTED, actualEnd = null, focus1to5 = null))
            logs.upsert(F.log)
            same(F.log, logs.forEvent(F.log.eventRef!!, F.log.plannedStart), "replaced")
            same(listOf(F.log), logs.history(F.day), "one log")
        },
        Case("logs: a day holds the logs planned that day, in planned order") {
            val late = F.log.copy(id = "late", plannedStart = F.day.atTime(16, 0), plannedEnd = F.day.atTime(17, 0))
            val early = F.log.copy(id = "early", plannedStart = F.day.atTime(9, 30), plannedEnd = F.day.atTime(10, 0))
            val nextDay = F.log.copy(id = "next", plannedStart = F.day.plusDays(1).atTime(9, 0), plannedEnd = F.day.plusDays(1).atTime(10, 0))
            val pending = ExecutionLog("pending", null, null, "Halaqa", F.day.atTime(12, 0), F.day.atTime(13, 0))
            logs.upsert(late)
            logs.upsert(nextDay)
            logs.upsert(pending)
            logs.upsert(early)
            logs.observeDay(F.day).await("day") { it.map(ExecutionLog::id) == listOf("early", "pending", "late") }
            same(listOf(pending), logs.observeDay(F.day).first().filter { it.eventRef == null }, "a log without an event")
        },
        Case("logs: history is everything planned on or after a date, oldest first") {
            val d0 = F.log.copy(id = "d0", plannedStart = F.day.minusDays(1).atTime(23, 30), plannedEnd = F.day.atTime(0, 30))
            val d1 = F.log.copy(id = "d1", plannedStart = F.day.atTime(0, 0), plannedEnd = F.day.atTime(1, 0))
            val d2 = F.log.copy(id = "d2", plannedStart = F.day.plusDays(3).atTime(8, 0), plannedEnd = F.day.plusDays(3).atTime(9, 0))
            logs.upsert(d2)
            logs.upsert(d0)
            logs.upsert(d1)
            same(listOf("d1", "d2"), logs.history(F.day).map(ExecutionLog::id), "since day")
            same(listOf("d0", "d1", "d2"), logs.history(F.day.minusDays(1)).map(ExecutionLog::id), "since the day before")
            same(emptyList(), logs.history(F.day.plusDays(4)), "since later")
        },
        Case("logs: observe day follows writes") {
            val flow = logs.observeDay(F.day)
            flow.await("empty") { it.isEmpty() }
            logs.upsert(F.log)
            flow.await("added") { it == listOf(F.log) }
            val done = F.log.copy(outcome = ExecutionOutcome.DONE)
            logs.upsert(done)
            flow.await("updated") { it == listOf(done) }
        },

        // ---- SettingsStore ----
        Case("settings: a fresh store holds the defaults") {
            same(Settings(), settings.current(), "current")
            same(Settings(), settings.observe().first(), "observe")
        },
        Case("settings: update applies the transform and keeps every field") {
            settings.update { F.settings }
            same(F.settings, settings.current(), "current")
            settings.observe().await("observe") { it == F.settings }
        },
        Case("settings: updates build on the current value") {
            settings.update { it.copy(wakeTime = LocalTime.of(8, 0)) }
            settings.update { it.copy(hardCap = LocalTime.of(21, 0)) }
            settings.update { it.copy(prayerOffsetMin = it.prayerOffsetMin + (Prayer.DHUHR to 20)) }
            val expected = Settings().copy(wakeTime = LocalTime.of(8, 0), hardCap = LocalTime.of(21, 0), prayerOffsetMin = mapOf(Prayer.DHUHR to 20))
            same(expected, settings.current(), "current")
            settings.observe().await("observe") { it == expected }
        },
        Case("settings: an identity update changes nothing") {
            settings.update { it }
            same(Settings(), settings.current(), "current")
        },
    )

    private fun <T> same(expected: T, actual: T, what: String) {
        if (expected != actual) throw AssertionError("$what: expected <$expected> but was <$actual>")
    }

    /** Waits (up to 5 s, for databases that notify asynchronously) until the flow emits a value matching [ok]. */
    private suspend fun <T> Flow<T>.await(what: String, ok: (T) -> Boolean): T {
        var last: Any? = "(nothing emitted)"
        return withTimeoutOrNull(5_000) { first { last = it; ok(it) } }
            ?: throw AssertionError("$what: never matched; last value <$last>")
    }

    /** Fixtures: every field away from its default, so a dropped column or JSON field fails a case. */
    private object F {
        val day: LocalDate = LocalDate.of(2026, 11, 3)
        val t0: Instant = Instant.parse("2026-11-02T18:00:00.000000001Z")
        val ref = EventRef("cal-academic", "evt0001")

        val task = Task(
            id = "task-1", title = "Arabic hw", category = CalendarCategory.QURAN_ARABIC, durationMin = 90,
            assignedDay = day, earliestStart = LocalTime.of(14, 0), afterPrayer = Prayer.ASR, deadline = day.plusDays(4),
            splittable = false, minChunkMin = 30, priority = 3, kind = TaskKind.PASSIVE, status = TaskStatus.SCHEDULED,
            calendarEventIds = listOf(ref, EventRef("cal-academic", "evt0002")), sourceEvent = EventRef("cal-quran", "allday1"),
            notes = "page 12",
        )
        val task2 = Task(id = "task-2", title = "Gym", category = CalendarCategory.FITNESS, durationMin = 60)

        val unparsed1 = UnparsedTemplate(EventRef("cal-academic", "u1"), "PintOS sometime", "Academic ", day)
        val unparsed2 = UnparsedTemplate(EventRef("cal-personal", "u2"), "???", "Personal", day.plusDays(1))

        fun snapshot(id: String, time: EventTime, calendarId: String = "cal-academic") = EventSnapshot(
            ref = EventRef(calendarId, id), calendarName = "Academic ", title = "Block $id", time = time,
            description = "Task: 2h.", etag = "\"etag-$id\"", updated = Instant.parse("2026-11-01T09:10:11.123456Z"),
            recurringEventId = "series-$id", organiserIsExternal = true, privateProps = mapOf("planner" to "1", "taskId" to "task-1"),
        )

        /** Keyed by event id. Relative to [day]; see the snapshot cases for which ones each range should return. */
        val rangeEvents: Map<String, EventSnapshot> = listOf(
            snapshot("timed-in", EventTime.Timed(day.atTime(10, 0), day.atTime(11, 0))),
            snapshot("all-day-in", EventTime.AllDay(day, day.plusDays(1))),
            snapshot("spans-in", EventTime.Timed(day.minusDays(1).atTime(22, 0), day.atTime(1, 0))),
            snapshot("multi-day", EventTime.AllDay(day.minusDays(1), day.plusDays(2))),
            // Ends exactly at midnight starting the range: the fakes count it on both dates.
            snapshot("ends-at-midnight", EventTime.Timed(day.minusDays(1).atTime(23, 0), day.atTime(0, 0))),
            snapshot("prev-day", EventTime.AllDay(day.minusDays(1), day)),
            snapshot("next-day", EventTime.Timed(day.plusDays(1).atTime(9, 0), day.plusDays(1).atTime(10, 0))),
            snapshot("far-future", EventTime.AllDay(day.plusDays(30), day.plusDays(31))),
        ).associateBy { it.ref.eventId }

        private val draft = EventDraft(
            "cal-academic", "PintOS (1/2)", EventTime.Timed(day.atTime(14, 0), day.atTime(16, 15)), "Includes 15 min for Asr.",
            mapOf("planner" to "1", "rev" to "2"),
        )
        val changeSet = ChangeSet(
            id = "cs-1",
            source = ChangeSource.REPLAN,
            summary = "Halaqa ran over: move 2 blocks",
            changes = listOf(
                Change("c1", ChangeType.ADD, after = draft, newEventId = "abc0123", flags = setOf(ChangeFlag.EXTENDED_DAY), taskId = "task-1", chunkIndex = 1, note = "n"),
                Change("c2", ChangeType.MOVE, target = rangeEvents.getValue("timed-in"), after = draft, scope = RecurrenceScope.WHOLE_SERIES, flags = setOf(ChangeFlag.RECURRING, ChangeFlag.EXTERNAL_ORGANISER)),
                Change("c3", ChangeType.EDIT, target = rangeEvents.getValue("all-day-in"), after = draft, flags = setOf(ChangeFlag.CONVERTS_ALL_DAY), status = ChangeStatus.STALE, problem = "etag"),
                Change("c4", ChangeType.REMOVE, target = rangeEvents.getValue("next-day"), status = ChangeStatus.REJECTED),
            ),
            createdAt = t0,
            status = ChangeStatus.APPROVED,
            notes = listOf("Halaqa assumed: gap kept"),
        )
        val applied = AppliedRecord(
            changeSetId = "cs-1",
            appliedAt = t0.plusSeconds(30),
            results = listOf(ChangeResult("c1", ChangeStatus.VERIFIED, ref), ChangeResult("c2", ChangeStatus.FAILED, null, "start differs")),
            inverse = listOf(InverseOp.Revert(ref, draft), InverseOp.Delete(ref), InverseOp.Restore(rangeEvents.getValue("next-day"))),
            undone = false,
        )

        val log = ExecutionLog(
            id = "log-1", taskId = "task-1", eventRef = ref, title = "PintOS (1/2)",
            plannedStart = day.atTime(10, 0), plannedEnd = day.atTime(12, 15),
            actualStart = day.atTime(10, 7, 30), actualEnd = day.atTime(12, 40), focus1to5 = 4, outcome = ExecutionOutcome.EXTENDED,
        )

        val settings = Settings(
            wakeTime = LocalTime.of(8, 45), normalEnd = LocalTime.of(17, 30), extendedEnd = LocalTime.of(19, 45),
            hardCap = LocalTime.of(21, 30), prayerSlotMin = 20, prayerOffsetMin = mapOf(Prayer.DHUHR to 15, Prayer.ASR to -5),
            minChunkMin = 40, writableCategories = setOf(CalendarCategory.ACADEMIC), catchUpDay = DayOfWeek.SATURDAY,
            convertAllDayOnSchedule = false, stripWeekTagsOnRollover = true, recordingDelayMin = 90,
            notifications = NotificationPrefs(checkInsEnabled = false, snoozeMin = 5, nightBeforeReview = LocalTime.of(22, 5)),
            zone = ZoneId.of("Asia/Riyadh"), assistantModel = "claude-test",
        )
    }
}
