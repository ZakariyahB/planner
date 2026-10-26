package app.planner.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import app.planner.scheduler.Block
import app.planner.scheduler.BlockKind
import app.planner.scheduler.BlockOrigin
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "blocks", indices = [Index("date")])
data class BlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long?,
    val date: String,
    val startMin: Int,
    val endMin: Int,
    val kind: String,
    val locked: Boolean,
    val gcalEventId: String?,
    val origin: String,
    /** What the Planner calendar held at the last sync; lets the sync tell who changed a block. */
    val syncedStartMillis: Long?,
    val syncedEndMillis: Long?,
    /** Name of a commitment (kind FIXED), which has no task. */
    val label: String? = null,
)

fun Block.toEntity(syncedStartMillis: Long? = null, syncedEndMillis: Long? = null) = BlockEntity(
    id = id,
    taskId = taskId,
    date = date.toString(),
    startMin = start.hour * 60 + start.minute,
    endMin = end.hour * 60 + end.minute,
    kind = kind.name,
    locked = locked,
    gcalEventId = gcalEventId,
    origin = origin.name,
    syncedStartMillis = syncedStartMillis,
    syncedEndMillis = syncedEndMillis,
    label = label,
)

fun BlockEntity.toBlock() = Block(
    id = id,
    taskId = taskId,
    date = LocalDate.parse(date),
    start = LocalTime.of(startMin / 60, startMin % 60),
    end = LocalTime.of(endMin / 60, endMin % 60),
    kind = BlockKind.valueOf(kind),
    locked = locked,
    gcalEventId = gcalEventId,
    origin = BlockOrigin.valueOf(origin),
    label = label,
)

@Dao
interface BlockDao {
    @Query("SELECT * FROM blocks WHERE date BETWEEN :from AND :to ORDER BY date, startMin")
    fun observeBetween(from: String, to: String): Flow<List<BlockEntity>>

    @Query("SELECT * FROM blocks WHERE date >= :from ORDER BY date, startMin")
    suspend fun fromDate(from: String): List<BlockEntity>

    @Query("SELECT * FROM blocks WHERE date BETWEEN :from AND :to")
    suspend fun between(from: String, to: String): List<BlockEntity>

    @Query("SELECT * FROM blocks WHERE id = :id")
    suspend fun get(id: Long): BlockEntity?

    @Upsert
    suspend fun upsert(block: BlockEntity): Long

    @Upsert
    suspend fun upsertAll(blocks: List<BlockEntity>)

    @Query("DELETE FROM blocks WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("DELETE FROM blocks WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: Long)
}
