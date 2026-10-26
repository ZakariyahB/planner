package app.planner.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import app.planner.scheduler.Block
import app.planner.scheduler.BlockDiff
import app.planner.scheduler.BlockKind
import app.planner.scheduler.BlockOrigin
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/** ChangeLog from the plan: powers Undo and the change history. */
@Serializable
@Entity(tableName = "change_log")
data class ChangeLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val actor: String,
    val summary: String,
    val diffJson: String,
    val undone: Boolean,
)

@Serializable
private data class BlockDto(
    val id: Long,
    val taskId: Long?,
    val date: String,
    val start: String,
    val end: String,
    val kind: String,
    val locked: Boolean,
    val gcalEventId: String?,
    val origin: String,
)

@Serializable
private data class DiffDto(val before: BlockDto?, val after: BlockDto?)

private val json = Json { ignoreUnknownKeys = true }

private fun Block.toDto() = BlockDto(id, taskId, date.toString(), start.toString(), end.toString(), kind.name, locked, gcalEventId, origin.name)

private fun BlockDto.toBlock() = Block(
    id, taskId, LocalDate.parse(date), LocalTime.parse(start), LocalTime.parse(end),
    BlockKind.valueOf(kind), locked, gcalEventId, BlockOrigin.valueOf(origin),
)

fun encodeDiffs(diffs: List<BlockDiff>): String =
    json.encodeToString(diffs.map { DiffDto(it.before?.toDto(), it.after?.toDto()) })

fun decodeDiffs(text: String): List<BlockDiff> =
    json.decodeFromString<List<DiffDto>>(text).map { BlockDiff(it.before?.toBlock(), it.after?.toBlock()) }

@Dao
interface ChangeLogDao {
    @Insert
    suspend fun insert(entry: ChangeLogEntity): Long

    @Query("SELECT * FROM change_log WHERE id = :id")
    suspend fun get(id: Long): ChangeLogEntity?

    @Query("UPDATE change_log SET undone = 1 WHERE id = :id")
    suspend fun markUndone(id: Long)

    @Query("SELECT * FROM change_log ORDER BY ts DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ChangeLogEntity>>

    @Query("SELECT * FROM change_log")
    suspend fun all(): List<ChangeLogEntity>

    @Query("DELETE FROM change_log")
    suspend fun clear()

    /** For restoring a backup: rows keep the ids they had. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllReplacing(rows: List<ChangeLogEntity>)
}
