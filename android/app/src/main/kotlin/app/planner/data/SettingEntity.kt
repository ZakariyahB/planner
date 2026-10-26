package app.planner.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "settings")
data class SettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface SettingsDao {
    @Query("SELECT value FROM settings WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(setting: SettingEntity)

    @Query("SELECT * FROM settings")
    suspend fun all(): List<SettingEntity>

    @Query("DELETE FROM settings")
    suspend fun clear()

    /** For restoring a backup: rows keep the ids they had. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllReplacing(rows: List<SettingEntity>)
}
