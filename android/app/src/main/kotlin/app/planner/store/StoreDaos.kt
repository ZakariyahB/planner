package app.planner.store

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TasksDao {
    @Query("SELECT * FROM tasks ORDER BY id")
    fun observeAll(): Flow<List<TaskRow>>

    @Query("SELECT * FROM tasks ORDER BY id")
    suspend fun all(): List<TaskRow>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: String): TaskRow?

    @Upsert
    suspend fun upsert(rows: List<TaskRow>)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM needs_a_look ORDER BY position")
    fun observeNeedsALook(): Flow<List<NeedsALookRow>>

    @Query("DELETE FROM needs_a_look")
    suspend fun clearNeedsALook()

    @Insert
    suspend fun insertNeedsALook(rows: List<NeedsALookRow>)
}

@Dao
interface ChangeSetsDao {
    @Query("SELECT * FROM change_sets WHERE status = :status ORDER BY createdAtKey DESC, id")
    fun observeWithStatus(status: String): Flow<List<ChangeSetRow>>

    @Query("SELECT * FROM change_sets WHERE id = :id")
    suspend fun get(id: String): ChangeSetRow?

    @Upsert
    suspend fun upsert(row: ChangeSetRow)

    @Query("SELECT MAX(seq) FROM applied_records")
    suspend fun maxAppliedSeq(): Long?

    @Upsert
    suspend fun upsertApplied(row: AppliedRecordRow)

    @Query("SELECT * FROM applied_records ORDER BY seq DESC LIMIT 1")
    suspend fun lastApplied(): AppliedRecordRow?

    @Query("SELECT * FROM applied_records ORDER BY seq DESC LIMIT 1")
    fun observeLastApplied(): Flow<AppliedRecordRow?>
}

@Dao
interface SnapshotsDao {
    @Query(
        "SELECT * FROM event_snapshots WHERE firstDay < :toExclusive AND endDay > :from " +
            "ORDER BY startKey, calendarId, eventId",
    )
    fun observeRange(from: Long, toExclusive: Long): Flow<List<EventSnapshotRow>>

    @Query(
        "SELECT * FROM event_snapshots WHERE firstDay < :toExclusive AND endDay > :from " +
            "ORDER BY startKey, calendarId, eventId",
    )
    suspend fun range(from: Long, toExclusive: Long): List<EventSnapshotRow>

    @Upsert
    suspend fun upsert(rows: List<EventSnapshotRow>)

    @Query("DELETE FROM event_snapshots WHERE calendarId = :calendarId AND eventId = :eventId")
    suspend fun delete(calendarId: String, eventId: String)

    @Query("SELECT token FROM sync_tokens WHERE calendarId = :calendarId")
    suspend fun syncToken(calendarId: String): String?

    @Upsert
    suspend fun upsertToken(row: SyncTokenRow)

    @Query("DELETE FROM sync_tokens WHERE calendarId = :calendarId")
    suspend fun deleteToken(calendarId: String)

    @Query("SELECT * FROM sync_stamp WHERE id = 0")
    fun observeStamp(): Flow<SyncStampRow?>

    @Upsert
    suspend fun upsertStamp(row: SyncStampRow)
}

@Dao
interface ExecutionLogsDao {
    @Query("SELECT * FROM execution_logs WHERE plannedDay = :day ORDER BY plannedStartKey, id")
    fun observeDay(day: Long): Flow<List<ExecutionLogRow>>

    @Query(
        "SELECT * FROM execution_logs WHERE calendarId = :calendarId AND eventId = :eventId " +
            "AND plannedStart = :plannedStart ORDER BY id LIMIT 1",
    )
    suspend fun forEvent(calendarId: String, eventId: String, plannedStart: String): ExecutionLogRow?

    @Upsert
    suspend fun upsert(row: ExecutionLogRow)

    @Query("SELECT * FROM execution_logs WHERE plannedDay >= :sinceDay ORDER BY plannedStartKey, id")
    suspend fun since(sinceDay: Long): List<ExecutionLogRow>
}

@Dao
interface SettingsRowDao {
    @Query("SELECT * FROM settings WHERE id = 0")
    fun observe(): Flow<SettingsRow?>

    @Query("SELECT * FROM settings WHERE id = 0")
    suspend fun get(): SettingsRow?

    @Upsert
    suspend fun upsert(row: SettingsRow)
}
