package app.planner.backup

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import app.planner.data.AppDatabase
import app.planner.data.BlockEntity
import app.planner.data.ChangeLogEntity
import app.planner.data.ChatMessageEntity
import app.planner.data.DayLogEntity
import app.planner.data.ReadinessEntity
import app.planner.data.SettingEntity
import app.planner.data.SleepEntity
import app.planner.data.SocialUsageEntity
import app.planner.data.TaskEntity
import app.planner.plan.PlanRepository
import app.planner.routines.RoutineScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

/** A backup ready to be written: the file's text and a one-line description of what is in it. */
class Exported(val json: String, val summary: String)

sealed interface RestoreResult {
    /** [replanned] is false when the data is back but the plan could not be rebuilt just now. */
    data class Restored(val replanned: Boolean) : RestoreResult

    /** Nothing was changed. */
    data class Failed(val reason: String) : RestoreResult
}

/**
 * Writes the user's data out as one JSON file and puts it back. A restore decodes every table before it
 * touches anything and then replaces them in a single transaction, so a damaged file or a failure half way
 * leaves the phone exactly as it was.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val plan: PlanRepository,
    private val routines: RoutineScheduler,
    private val clock: Clock,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class Table<T>(
        val name: String,
        val serializer: KSerializer<T>,
        val read: suspend () -> List<T>,
        val clear: suspend () -> Unit,
        val write: suspend (List<T>) -> Unit,
    )

    private val tables: List<Table<*>> = listOf(
        Table("settings", SettingEntity.serializer(), { db.settings().all() }, { db.settings().clear() }, { db.settings().insertAllReplacing(it) }),
        Table("tasks", TaskEntity.serializer(), { db.tasks().all() }, { db.tasks().clear() }, { db.tasks().insertAllReplacing(it) }),
        Table("blocks", BlockEntity.serializer(), { db.blocks().all() }, { db.blocks().clear() }, { db.blocks().insertAllReplacing(it) }),
        Table("change_log", ChangeLogEntity.serializer(), { db.changeLog().all() }, { db.changeLog().clear() }, { db.changeLog().insertAllReplacing(it) }),
        Table("sleep_sessions", SleepEntity.serializer(), { db.sleep().all() }, { db.sleep().clear() }, { db.sleep().insertAllReplacing(it) }),
        Table("readiness", ReadinessEntity.serializer(), { db.readiness().all() }, { db.readiness().clear() }, { db.readiness().insertAllReplacing(it) }),
        Table("chat_messages", ChatMessageEntity.serializer(), { db.chat().all() }, { db.chat().clear() }, { db.chat().insertAllReplacing(it) }),
        Table("social_usage", SocialUsageEntity.serializer(), { db.social().all() }, { db.social().clear() }, { db.social().insertAllReplacing(it) }),
        Table("day_log", DayLogEntity.serializer(), { db.dayLog().all() }, { db.dayLog().clear() }, { db.dayLog().insertAllReplacing(it) }),
    )

    /** Everything the app keeps, as one file's worth of text. The assistant conversation is left out unless asked for. */
    suspend fun export(includeChat: Boolean): Exported = withContext(Dispatchers.IO) {
        val rows = db.withTransaction { // one moment in time, even if a sync lands while this runs
            tables.filter { includeChat || it.name != CHAT }.associate { it.name to encode(it) }
        }
        val now = clock.instant()
        Exported(Backup.encode(now, rows), BackupFile(now, rows).describe())
    }

    /** Says whether [text] is a backup this version can restore, and what is in it. */
    fun inspect(text: String): BackupParse = Backup.parse(text)

    suspend fun restore(file: BackupFile): RestoreResult {
        val steps = try {
            withContext(Dispatchers.Default) {
                tables.filter { it.name in file.tables }.map { prepare(it, file.tables.getValue(it.name)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RestoreResult.Failed("This backup is damaged, so nothing was changed. Try an older copy.")
        }

        try {
            withContext(Dispatchers.IO) { db.withTransaction { steps.forEach { it() } } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RestoreResult.Failed("The restore didn't finish, so nothing was changed. Free some space on the phone and try again.")
        }

        // The data is back; rebuilding the plan and the reminders is best effort and can be redone from Today.
        val replanned = try {
            plan.replan()
            routines.rescheduleAll()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        return RestoreResult.Restored(replanned)
    }

    /** The text of the file the user picked. Reads at most [MAX_BYTES] so a wrong file can't fill the memory. */
    suspend fun read(uri: Uri): String = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri) ?: throw IOException("The file could not be opened.")
        stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_BYTES) throw IOException("That file is too big to be a Planner backup.")
                out.write(buffer, 0, n)
            }
            out.toString(Charsets.UTF_8.name())
        }
    }

    suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("The file could not be opened.")
        stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    private suspend fun <T> encode(table: Table<T>): JsonArray =
        json.encodeToJsonElement(ListSerializer(table.serializer), table.read()) as JsonArray

    /** Decodes now; the returned step swaps the table's rows for the decoded ones. */
    private fun <T> prepare(table: Table<T>, rows: JsonArray): suspend () -> Unit {
        val decoded = json.decodeFromJsonElement(ListSerializer(table.serializer), rows)
        return {
            table.clear()
            table.write(decoded)
        }
    }

    private companion object {
        const val CHAT = "chat_messages"
        const val MAX_BYTES = 64 * 1024 * 1024
        const val BUFFER_BYTES = 16 * 1024
    }
}
