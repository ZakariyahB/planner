package app.planner.store

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Tables of planner_v2.db. Each record is stored whole as JSON in `body` (app.planner.engine.codec.PlannerCodec,
// tolerant decoding, tested on the JVM); the other columns are only the keys the queries need, derived by
// app.planner.engine.codec.StoreKeys so ordering and date ranges match the in-memory fakes exactly.
// Adding a field to a domain type needs no migration: old rows decode with the field's default.

@Entity(tableName = "tasks")
data class TaskRow(
    @PrimaryKey val id: String,
    val body: String,
)

/** The Backlog's "Needs a look" list, replaced as a whole; [position] keeps its order. */
@Entity(tableName = "needs_a_look")
data class NeedsALookRow(
    @PrimaryKey val position: Int,
    val calendarId: String,
    val eventId: String,
    val title: String,
    val calendarName: String,
    /** Epoch day. */
    val date: Long,
)

@Entity(tableName = "change_sets", indices = [Index(value = ["status", "createdAtKey"])])
data class ChangeSetRow(
    @PrimaryKey val id: String,
    /** ChangeStatus name of the set. */
    val status: String,
    /** StoreKeys.instantNanos(createdAt). */
    val createdAtKey: Long,
    val body: String,
)

@Entity(tableName = "applied_records", indices = [Index(value = ["seq"])])
data class AppliedRecordRow(
    @PrimaryKey val changeSetId: String,
    /** Grows on every save, so the last saved record (an undo re-saves it) is the last applied. */
    val seq: Long,
    val body: String,
)

@Entity(
    tableName = "event_snapshots",
    primaryKeys = ["calendarId", "eventId"],
    indices = [Index(value = ["firstDay", "endDay"])],
)
data class EventSnapshotRow(
    val calendarId: String,
    val eventId: String,
    /** StoreKeys.eventDays: epoch days [firstDay, endDay). */
    val firstDay: Long,
    val endDay: Long,
    /** StoreKeys.eventStartKey, for a stable order. */
    val startKey: Long,
    val body: String,
)

@Entity(tableName = "sync_tokens")
data class SyncTokenRow(
    @PrimaryKey val calendarId: String,
    val token: String,
)

/** A single row (id 0): when the app last heard from Google. */
@Entity(tableName = "sync_stamp")
data class SyncStampRow(
    @PrimaryKey val id: Int = 0,
    /** ISO-8601 instant, exact to the nanosecond. */
    val at: String,
    val ok: Boolean,
    val message: String?,
)

@Entity(
    tableName = "execution_logs",
    indices = [Index(value = ["plannedDay", "plannedStartKey"]), Index(value = ["calendarId", "eventId", "plannedStart"])],
)
data class ExecutionLogRow(
    @PrimaryKey val id: String,
    val calendarId: String?,
    val eventId: String?,
    /** LocalDateTime.toString(): canonical, so equal strings mean equal times (ExecutionLogStore.forEvent). */
    val plannedStart: String,
    /** StoreKeys.wallNanos(plannedStart), for ordering. */
    val plannedStartKey: Long,
    /** Epoch day of plannedStart. */
    val plannedDay: Long,
    val body: String,
)

/** A single row (id 0) holding the whole Settings as JSON. No row means the defaults. */
@Entity(tableName = "settings")
data class SettingsRow(
    @PrimaryKey val id: Int = 0,
    val body: String,
)
