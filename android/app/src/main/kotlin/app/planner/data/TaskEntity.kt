package app.planner.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import app.planner.model.Anchor
import app.planner.model.Prayer
import app.planner.model.Priority
import app.planner.model.Task
import app.planner.model.TaskSource
import app.planner.model.TaskStatus
import java.time.Instant
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "tasks", indices = [Index(value = ["templateKey"], unique = true)])
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val module: String?,
    val estMin: Int,
    val availableFromMillis: Long?,
    val anchorPrayer: String?,
    val anchorOffsetMin: Int?,
    val dueAtMillis: Long?,
    val priority: String,
    val source: String,
    val status: String,
    val templateKey: String?,
)

fun Task.toEntity() = TaskEntity(
    id = id,
    title = title,
    module = module,
    estMin = estMin,
    availableFromMillis = availableFrom?.toEpochMilli(),
    anchorPrayer = anchor?.prayer?.name,
    anchorOffsetMin = anchor?.offsetMin,
    dueAtMillis = dueAt?.toEpochMilli(),
    priority = priority.name,
    source = source.name,
    status = status.name,
    templateKey = templateKey,
)

fun TaskEntity.toTask() = Task(
    id = id,
    title = title,
    module = module,
    estMin = estMin,
    availableFrom = availableFromMillis?.let(Instant::ofEpochMilli),
    anchor = anchorPrayer?.let { Anchor(Prayer.valueOf(it), anchorOffsetMin ?: 0) },
    dueAt = dueAtMillis?.let(Instant::ofEpochMilli),
    priority = Priority.valueOf(priority),
    source = TaskSource.valueOf(source),
    status = TaskStatus.valueOf(status),
    templateKey = templateKey,
)

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks")
    suspend fun all(): List<TaskEntity>

    @Upsert
    suspend fun upsert(task: TaskEntity)

    /** Rows whose templateKey already exists are skipped. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(tasks: List<TaskEntity>)

    @Query("SELECT templateKey FROM tasks WHERE templateKey IS NOT NULL")
    suspend fun templateKeys(): List<String>

    @Query("UPDATE tasks SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: String)
}
