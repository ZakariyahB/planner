package app.planner.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/** Work actually done on a task on a day, for the planned-versus-done insights. */
@Entity(tableName = "day_log")
data class DayLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val taskId: Long,
    val plannedMin: Int,
    val actualMin: Int,
    val outcome: String,
    val note: String?,
)

@Dao
interface DayLogDao {
    @Insert
    suspend fun insert(entry: DayLogEntity): Long

    @Query("SELECT * FROM day_log WHERE date BETWEEN :from AND :to ORDER BY date, id")
    suspend fun between(from: String, to: String): List<DayLogEntity>
}
