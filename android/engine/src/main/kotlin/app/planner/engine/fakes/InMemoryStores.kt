package app.planner.engine.fakes

import app.planner.engine.changeset.AppliedRecord
import app.planner.engine.changeset.ChangeSet
import app.planner.engine.changeset.ChangeStatus
import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.EventTime
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
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

// In-memory implementations of the storage ports, for tests, previews and the UI's demo mode.

class InMemoryTaskStore(initial: List<Task> = emptyList()) : TaskStore {
    private val tasks = MutableStateFlow(initial.associateBy { it.id })
    private val needsALook = MutableStateFlow<List<UnparsedTemplate>>(emptyList())

    override fun observe(): Flow<List<Task>> = tasks.map { it.values.toList() }
    override suspend fun all(): List<Task> = tasks.value.values.toList()
    override suspend fun get(id: String): Task? = tasks.value[id]
    override suspend fun upsert(tasks: List<Task>) = this.tasks.update { it + tasks.associateBy(Task::id) }
    override suspend fun delete(id: String) = tasks.update { it - id }
    override fun observeNeedsALook(): Flow<List<UnparsedTemplate>> = needsALook
    override suspend fun setNeedsALook(items: List<UnparsedTemplate>) { needsALook.value = items }
}

class InMemoryChangeSetStore : ChangeSetStore {
    private val sets = MutableStateFlow<Map<String, ChangeSet>>(emptyMap())
    private val applied = MutableStateFlow<List<AppliedRecord>>(emptyList())

    override fun observePending(): Flow<List<ChangeSet>> =
        sets.map { all -> all.values.filter { it.status == ChangeStatus.PROPOSED }.sortedByDescending { it.createdAt } }
    override suspend fun get(id: String): ChangeSet? = sets.value[id]
    override suspend fun save(changeSet: ChangeSet) = sets.update { it + (changeSet.id to changeSet) }
    override suspend fun saveApplied(record: AppliedRecord) =
        applied.update { list -> list.filter { it.changeSetId != record.changeSetId } + record }
    override suspend fun lastApplied(): AppliedRecord? = applied.value.lastOrNull()
    override fun observeLastApplied(): Flow<AppliedRecord?> = applied.map { it.lastOrNull() }
}

class InMemorySnapshotStore(initial: List<EventSnapshot> = emptyList()) : SnapshotStore {
    private val events = MutableStateFlow(initial.associateBy { it.ref })
    private val tokens = mutableMapOf<String, String?>()
    private val sync = MutableStateFlow<SyncStamp?>(null)

    private fun EventSnapshot.dates(): Pair<LocalDate, LocalDate> = when (val t = time) {
        is EventTime.Timed -> t.start.toLocalDate() to t.end.toLocalDate().plusDays(1)
        is EventTime.AllDay -> t.start to t.endExclusive
    }

    private fun inRange(e: EventSnapshot, from: LocalDate, to: LocalDate): Boolean {
        val (s, end) = e.dates()
        return s < to && end > from
    }

    override fun observe(from: LocalDate, toExclusive: LocalDate): Flow<List<EventSnapshot>> =
        events.map { all -> all.values.filter { inRange(it, from, toExclusive) } }
    override suspend fun events(from: LocalDate, toExclusive: LocalDate): List<EventSnapshot> =
        events.value.values.filter { inRange(it, from, toExclusive) }
    override suspend fun upsert(events: List<EventSnapshot>) = this.events.update { it + events.associateBy(EventSnapshot::ref) }
    override suspend fun remove(refs: List<EventRef>) = events.update { it - refs.toSet() }
    override suspend fun syncToken(calendarId: String): String? = tokens[calendarId]
    override suspend fun setSyncToken(calendarId: String, token: String?) { tokens[calendarId] = token }
    override fun observeSync(): Flow<SyncStamp?> = sync
    override suspend fun setSync(stamp: SyncStamp) { sync.value = stamp }
}

class InMemoryExecutionLogStore : ExecutionLogStore {
    private val logs = MutableStateFlow<Map<String, ExecutionLog>>(emptyMap())

    override fun observeDay(date: LocalDate): Flow<List<ExecutionLog>> =
        logs.map { all -> all.values.filter { it.plannedStart.toLocalDate() == date }.sortedBy { it.plannedStart } }
    override suspend fun forEvent(ref: EventRef, plannedStart: LocalDateTime): ExecutionLog? =
        logs.value.values.firstOrNull { it.eventRef == ref && it.plannedStart == plannedStart }
    override suspend fun upsert(log: ExecutionLog) = logs.update { it + (log.id to log) }
    override suspend fun history(since: LocalDate): List<ExecutionLog> =
        logs.value.values.filter { it.plannedStart.toLocalDate() >= since }.sortedBy { it.plannedStart }
}

class InMemorySettingsStore(initial: Settings = Settings()) : SettingsStore {
    private val settings = MutableStateFlow(initial)
    override fun observe(): Flow<Settings> = settings
    override suspend fun current(): Settings = settings.value
    override suspend fun update(transform: (Settings) -> Settings) = settings.update(transform)
}
