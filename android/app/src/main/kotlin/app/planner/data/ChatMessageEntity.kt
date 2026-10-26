package app.planner.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** One saved turn of the assistant conversation. [toolJson] lists the tools that ran for an assistant turn. */
@Serializable
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val role: String,
    val content: String,
    val toolJson: String?,
)

@Dao
interface ChatDao {
    @Insert
    suspend fun insert(message: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages ORDER BY ts, id")
    fun observeAll(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM (SELECT * FROM chat_messages ORDER BY ts DESC, id DESC LIMIT :limit) ORDER BY ts, id")
    suspend fun recent(limit: Int): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages")
    suspend fun clear()

    @Query("SELECT * FROM chat_messages ORDER BY ts, id")
    suspend fun all(): List<ChatMessageEntity>

    /** For restoring a backup: rows keep the ids they had. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllReplacing(rows: List<ChatMessageEntity>)
}
