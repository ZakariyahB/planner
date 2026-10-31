package app.planner.store

import android.util.Log
import androidx.room.withTransaction
import app.planner.engine.changeset.AppliedRecord
import app.planner.engine.changeset.ChangeSet
import app.planner.engine.changeset.ChangeStatus
import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.codec.JsonCodec
import app.planner.engine.codec.PlannerCodec
import app.planner.engine.codec.StoreKeys
import app.planner.engine.model.EventRef
import app.planner.engine.model.ExecutionLog
import app.planner.engine.model.Settings
import app.planner.engine.model.SyncStamp
import app.planner.engine.model.Task
import app.planner.engine.model.UnparsedTemplate
import app.planner.engine.ports.ChangeSetStore
import app.planner.engine.ports.ExecutionLogStore
import app.planner.engine.ports.SettingsStore
import app.planner.engine.ports.SnapshotStore
import app.planner.engine.ports.TaskStore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// Room implementations of the storage ports. Behaviour matches the in-memory fakes (app.planner.engine.fakes);
// app.planner.engine.codec.StoreContract checks that, and docs/redesign/notes/F.md says how to run it on a device.

private const val TAG = "PlannerStore"

/** A row that no longer decodes (should not happen: decoding is tolerant) is skipped and logged, not a crash. */
private fun <T> JsonCodec<T>.decodeOrNull(text: String, what: String): T? =
    try {
        decode(text)
    } catch (e: Exception) {
        Log.w(TAG, "Skipping unreadable $what", e)
        null
    }

class RoomTaskStore(private val db: PlannerStoreDatabase) : TaskStore {
    private val dao = db.tasks()
    private val codec = PlannerCodec.task

    private fun decode(rows: List<TaskRow>): List<Task> = rows.mapNotNull { codec.decodeOrNull(it.body, "task ${it.id}") }

    override fun observe(): Flow<List<Task>> = dao.observeAll().map(::decode)
    override suspend fun all(): List<Task> = decode(dao.all())
    override suspend fun get(id: String): Task? = dao.get(id)?.let { codec.decodeOrNull(it.body, "task $id") }
    override suspend fun upsert(tasks: List<Task>) = dao.upsert(tasks.map { TaskRow(it.id, codec.encode(it)) })
    override suspend fun delete(id: String) = dao.delete(id)

    override fun observeNeedsALook(): Flow<List<UnparsedTemplate>> = dao.observeNeedsALook().map { rows ->
        rows.map { UnparsedTemplate(EventRef(it.calendarId, it.eventId), it.title, it.calendarName, LocalDate.ofEpochDay(it.date)) }
    }

    override suspend fun setNeedsALook(items: List<UnparsedTemplate>) = db.withTransaction {
        dao.clearNeedsALook()
        dao.insertNeedsALook(
            items.mapIndexed { i, t -> NeedsALookRow(i, t.ref.calendarId, t.ref.eventId, t.title, t.calendarName, t.date.toEpochDay()) },
        )
    }
}

class RoomChangeSetStore(private val db: PlannerStoreDatabase) : ChangeSetStore {
    private val dao = db.changeSets()
    private val sets = PlannerCodec.changeSet
    private val records = PlannerCodec.appliedRecord

    private fun record(row: AppliedRecordRow?): AppliedRecord? = row?.let { records.decodeOrNull(it.body, "applied record ${it.changeSetId}") }

    override fun observePending(): Flow<List<ChangeSet>> = dao.observeWithStatus(ChangeStatus.PROPOSED.name).map { rows ->
        rows.mapNotNull { sets.decodeOrNull(it.body, "change set ${it.id}") }
    }

    override suspend fun get(id: String): ChangeSet? = dao.get(id)?.let { sets.decodeOrNull(it.body, "change set $id") }

    override suspend fun save(changeSet: ChangeSet) = dao.upsert(
        ChangeSetRow(changeSet.id, changeSet.status.name, StoreKeys.instantNanos(changeSet.createdAt), sets.encode(changeSet)),
    )

    override suspend fun saveApplied(record: AppliedRecord) = db.withTransaction {
        val seq = (dao.maxAppliedSeq() ?: 0L) + 1
        dao.upsertApplied(AppliedRecordRow(record.changeSetId, seq, records.encode(record)))
    }

    override suspend fun lastApplied(): AppliedRecord? = record(dao.lastApplied())
    override fun observeLastApplied(): Flow<AppliedRecord?> = dao.observeLastApplied().map(::record)
}

class RoomSnapshotStore(private val db: PlannerStoreDatabase) : SnapshotStore {
    private val dao = db.snapshots()
    private val codec = PlannerCodec.eventSnapshot

    private fun decode(rows: List<EventSnapshotRow>): List<EventSnapshot> =
        rows.mapNotNull { codec.decodeOrNull(it.body, "event ${it.calendarId}/${it.eventId}") }

    private fun row(e: EventSnapshot): EventSnapshotRow {
        val days = StoreKeys.eventDays(e.time)
        return EventSnapshotRow(
            e.ref.calendarId, e.ref.eventId, days.firstDay, days.endDayExclusive, StoreKeys.eventStartKey(e.time), codec.encode(e),
        )
    }

    override fun observe(from: LocalDate, toExclusive: LocalDate): Flow<List<EventSnapshot>> =
        dao.observeRange(from.toEpochDay(), toExclusive.toEpochDay()).map(::decode)

    override suspend fun events(from: LocalDate, toExclusive: LocalDate): List<EventSnapshot> =
        decode(dao.range(from.toEpochDay(), toExclusive.toEpochDay()))

    override suspend fun upsert(events: List<EventSnapshot>) = dao.upsert(events.map(::row))

    override suspend fun remove(refs: List<EventRef>) = db.withTransaction {
        refs.forEach { dao.delete(it.calendarId, it.eventId) }
    }

    override suspend fun syncToken(calendarId: String): String? = dao.syncToken(calendarId)

    override suspend fun setSyncToken(calendarId: String, token: String?) {
        if (token == null) dao.deleteToken(calendarId) else dao.upsertToken(SyncTokenRow(calendarId, token))
    }

    override fun observeSync(): Flow<SyncStamp?> = dao.observeStamp().map { row ->
        row?.let { SyncStamp(Instant.parse(it.at), it.ok, it.message) }
    }

    override suspend fun setSync(stamp: SyncStamp) = dao.upsertStamp(SyncStampRow(0, stamp.at.toString(), stamp.ok, stamp.message))
}

class RoomExecutionLogStore(db: PlannerStoreDatabase) : ExecutionLogStore {
    private val dao = db.executionLogs()
    private val codec = PlannerCodec.executionLog

    private fun decode(rows: List<ExecutionLogRow>): List<ExecutionLog> = rows.mapNotNull { codec.decodeOrNull(it.body, "log ${it.id}") }

    override fun observeDay(date: LocalDate): Flow<List<ExecutionLog>> = dao.observeDay(date.toEpochDay()).map(::decode)

    override suspend fun forEvent(ref: EventRef, plannedStart: LocalDateTime): ExecutionLog? =
        dao.forEvent(ref.calendarId, ref.eventId, plannedStart.toString())?.let { codec.decodeOrNull(it.body, "log ${it.id}") }

    override suspend fun upsert(log: ExecutionLog) = dao.upsert(
        ExecutionLogRow(
            id = log.id,
            calendarId = log.eventRef?.calendarId,
            eventId = log.eventRef?.eventId,
            plannedStart = log.plannedStart.toString(),
            plannedStartKey = StoreKeys.wallNanos(log.plannedStart),
            plannedDay = log.plannedStart.toLocalDate().toEpochDay(),
            body = codec.encode(log),
        ),
    )

    override suspend fun history(since: LocalDate): List<ExecutionLog> = decode(dao.since(since.toEpochDay()))
}

class RoomSettingsStore(private val db: PlannerStoreDatabase) : SettingsStore {
    private val dao = db.settings()
    private val codec = PlannerCodec.settings

    private fun decode(row: SettingsRow?): Settings = row?.let { codec.decodeOrNull(it.body, "settings") } ?: Settings()

    override fun observe(): Flow<Settings> = dao.observe().map(::decode).distinctUntilChanged()
    override suspend fun current(): Settings = decode(dao.get())

    override suspend fun update(transform: (Settings) -> Settings) = db.withTransaction {
        val next = transform(decode(dao.get()))
        dao.upsert(SettingsRow(0, codec.encode(next)))
    }
}
