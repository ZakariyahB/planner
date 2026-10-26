package app.planner.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Minutes on social-media apps for one day, with the split per app as JSON. */
@Entity(tableName = "social_usage")
data class SocialUsageEntity(
    @PrimaryKey val date: String,
    val minutes: Int,
    val perAppJson: String,
)

@Dao
interface SocialUsageDao {
    @Query("SELECT * FROM social_usage WHERE date BETWEEN :from AND :to ORDER BY date")
    fun observeBetween(from: String, to: String): Flow<List<SocialUsageEntity>>

    @Query("SELECT * FROM social_usage WHERE date BETWEEN :from AND :to ORDER BY date")
    suspend fun between(from: String, to: String): List<SocialUsageEntity>

    @Query("SELECT * FROM social_usage WHERE date = :date")
    suspend fun get(date: String): SocialUsageEntity?

    @Upsert
    suspend fun put(usage: SocialUsageEntity)
}
