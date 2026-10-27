package app.planner.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import app.planner.readiness.AwakePeriod
import app.planner.readiness.SleepSession
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val SLEEP_SOURCE_HEALTH_CONNECT = "HC"
const val SLEEP_SOURCE_MANUAL = "MANUAL"

@Serializable
@Entity(tableName = "sleep_sessions")
data class SleepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMillis: Long,
    val endMillis: Long,
    val asleepMin: Int,
    val deepMin: Int,
    val remMin: Int,
    val source: String,
    /** The times he was awake inside this record, as start and end epoch millis one after the other; null when none. */
    val awakeJson: String? = null,
)

private fun encodeAwake(awake: List<AwakePeriod>): String? =
    awake.takeIf { it.isNotEmpty() }?.let { list -> Json.encodeToString(list.flatMap { listOf(it.start.toEpochMilli(), it.end.toEpochMilli()) }) }

private fun decodeAwake(text: String?): List<AwakePeriod> = try {
    text?.let { Json.decodeFromString<List<Long>>(it) }.orEmpty()
        .chunked(2).filter { it.size == 2 && it[1] > it[0] }
        .map { AwakePeriod(Instant.ofEpochMilli(it[0]), Instant.ofEpochMilli(it[1])) }
} catch (e: SerializationException) {
    emptyList()
}

fun SleepEntity.toSession() = SleepSession(
    start = Instant.ofEpochMilli(startMillis),
    end = Instant.ofEpochMilli(endMillis),
    asleepMin = asleepMin,
    deepMin = deepMin,
    remMin = remMin,
    manual = source == SLEEP_SOURCE_MANUAL,
    awake = decodeAwake(awakeJson),
)

fun SleepSession.toEntity(source: String) = SleepEntity(
    startMillis = start.toEpochMilli(),
    endMillis = end.toEpochMilli(),
    asleepMin = asleepMin,
    deepMin = deepMin,
    remMin = remMin,
    source = source,
    awakeJson = encodeAwake(awake),
)

@Dao
interface SleepDao {
    @Query("SELECT * FROM sleep_sessions WHERE endMillis >= :sinceMillis ORDER BY startMillis")
    fun observeSince(sinceMillis: Long): Flow<List<SleepEntity>>

    @Query("SELECT * FROM sleep_sessions WHERE endMillis >= :sinceMillis ORDER BY startMillis")
    suspend fun since(sinceMillis: Long): List<SleepEntity>

    /** Health Connect is the source of truth for its own window; manual entries are never touched. */
    @Query("DELETE FROM sleep_sessions WHERE source = 'HC' AND endMillis >= :sinceMillis")
    suspend fun deleteHealthConnectSince(sinceMillis: Long)

    @Insert
    suspend fun insertAll(sessions: List<SleepEntity>)

    @Insert
    suspend fun insert(session: SleepEntity)

    @Query("SELECT * FROM sleep_sessions")
    suspend fun all(): List<SleepEntity>

    @Query("DELETE FROM sleep_sessions")
    suspend fun clear()

    /** For restoring a backup: rows keep the ids they had. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllReplacing(rows: List<SleepEntity>)
}
