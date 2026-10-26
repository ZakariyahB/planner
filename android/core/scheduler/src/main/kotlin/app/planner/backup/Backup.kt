package app.planner.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.format.DateTimeParseException

/** What a backup file holds: when it was made and the rows of each table, as JSON. */
data class BackupFile(val createdAt: Instant, val tables: Map<String, JsonArray>) {
    /** A short description for the confirmation screen, for example "64 tasks, 212 blocks". */
    fun describe(): String =
        Backup.LABELS.mapNotNull { (table, label) ->
            val count = tables[table]?.size ?: 0
            if (count == 0) null else "$count ${if (count == 1) label.first else label.second}"
        }.joinToString(", ")
}

sealed interface BackupParse {
    data class Ok(val file: BackupFile) : BackupParse

    data class Rejected(val reason: String) : BackupParse
}

/**
 * The backup file format: one JSON object with the app name, a format version, when it was made, and a list of
 * rows per table. Tables a file does not contain are left alone by a restore, and tables this version does not
 * know are ignored.
 */
object Backup {
    const val APP = "planner"
    const val FORMAT_VERSION = 1

    /** In the order a restore should write them. */
    val TABLES = listOf("settings", "tasks", "blocks", "change_log", "sleep_sessions", "readiness", "chat_messages", "social_usage", "day_log")

    private val REQUIRED = listOf("tasks", "blocks", "settings")

    /** Singular and plural names, in the order they are listed on the confirmation screen. */
    internal val LABELS = listOf(
        "tasks" to ("task" to "tasks"),
        "blocks" to ("block" to "blocks"),
        "sleep_sessions" to ("sleep record" to "sleep records"),
        "social_usage" to ("day of social time" to "days of social time"),
        "change_log" to ("change in the history" to "changes in the history"),
        "chat_messages" to ("chat message" to "chat messages"),
    )

    private const val NOT_A_BACKUP = "That isn't a Planner backup."

    fun encode(createdAt: Instant, tables: Map<String, JsonArray>): String = buildJsonObject {
        put("app", APP)
        put("version", FORMAT_VERSION)
        put("createdAt", createdAt.toString())
        put("tables", JsonObject(tables.filterKeys { it in TABLES }))
    }.toString()

    fun parse(text: String): BackupParse {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return BackupParse.Rejected(NOT_A_BACKUP)

        if (text.isBlank() || root.string("app") != APP) return BackupParse.Rejected(NOT_A_BACKUP)
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: return BackupParse.Rejected(NOT_A_BACKUP)
        if (version > FORMAT_VERSION) {
            return BackupParse.Rejected("This backup was made by a newer version of Planner. Update the app, then try again.")
        }
        val createdAt = try {
            Instant.parse(root.string("createdAt") ?: return BackupParse.Rejected(NOT_A_BACKUP))
        } catch (e: DateTimeParseException) {
            return BackupParse.Rejected(NOT_A_BACKUP)
        }
        val raw = root["tables"] as? JsonObject ?: return BackupParse.Rejected(NOT_A_BACKUP)

        val tables = mutableMapOf<String, JsonArray>()
        for ((name, rows) in raw) {
            if (name !in TABLES) continue
            tables[name] = rows as? JsonArray ?: return BackupParse.Rejected("The backup's $name aren't readable.")
        }
        REQUIRED.firstOrNull { it !in tables }?.let { return BackupParse.Rejected("The backup is missing its $it.") }
        return BackupParse.Ok(BackupFile(createdAt, tables))
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
