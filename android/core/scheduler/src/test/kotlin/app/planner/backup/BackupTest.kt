package app.planner.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackupTest {
    private val created = Instant.parse("2026-10-26T20:30:00Z")

    private fun rows(n: Int): JsonArray = buildJsonArray { repeat(n) { i -> add(buildJsonObject { put("id", i) }) } }

    private fun encode(tables: Map<String, JsonArray> = mapOf("tasks" to rows(2), "blocks" to rows(3), "settings" to rows(1))) =
        Backup.encode(created, tables)

    @Test
    fun `a backup round trips through text`() {
        val result = Backup.parse(encode(mapOf("tasks" to rows(2), "blocks" to rows(3), "settings" to rows(1), "sleep_sessions" to rows(4))))
        assertIs<BackupParse.Ok>(result)
        assertEquals(created, result.file.createdAt)
        assertEquals(2, result.file.tables.getValue("tasks").size)
        assertEquals(4, result.file.tables.getValue("sleep_sessions").size)
    }

    @Test
    fun `text that is not a backup is refused in plain words`() {
        for (text in listOf("", "not json", "[]", """{"hello":"world"}""")) {
            val result = Backup.parse(text)
            assertIs<BackupParse.Rejected>(result, text)
            assertTrue("isn't a Planner backup" in result.reason, result.reason)
        }
    }

    @Test
    fun `a backup from a newer app version is refused with advice`() {
        val text = encode().replace("\"version\":${Backup.FORMAT_VERSION}", "\"version\":${Backup.FORMAT_VERSION + 1}")
        val result = Backup.parse(text)
        assertIs<BackupParse.Rejected>(result)
        assertTrue("newer version" in result.reason, result.reason)
    }

    @Test
    fun `a backup missing a core table is refused`() {
        val result = Backup.parse(encode(mapOf("tasks" to rows(1), "settings" to rows(1))))
        assertIs<BackupParse.Rejected>(result)
        assertTrue("blocks" in result.reason, result.reason)
    }

    @Test
    fun `a table that is not a list is refused`() {
        val text = encode().replace("\"tasks\":[", "\"tasks\":{\"a\":[")
        assertIs<BackupParse.Rejected>(Backup.parse(text))
    }

    @Test
    fun `optional tables may be left out and unknown ones are ignored`() {
        val result = Backup.parse(encode(mapOf("tasks" to rows(1), "blocks" to rows(1), "settings" to rows(1), "from_the_future" to rows(9))))
        assertIs<BackupParse.Ok>(result)
        assertEquals(setOf("tasks", "blocks", "settings"), result.file.tables.keys)
    }

    @Test
    fun `the app name guards against someone else's json`() {
        val text = encode().replace("\"app\":\"planner\"", "\"app\":\"other\"")
        assertIs<BackupParse.Rejected>(Backup.parse(text))
    }

    @Test
    fun `the summary says what is inside, skipping empty tables`() {
        val result = Backup.parse(
            encode(mapOf("tasks" to rows(64), "blocks" to rows(212), "settings" to rows(5), "sleep_sessions" to rows(21), "social_usage" to rows(14), "chat_messages" to rows(0))),
        ) as BackupParse.Ok
        val summary = result.file.describe()
        assertEquals("64 tasks, 212 blocks, 21 sleep records, 14 days of social time", summary)
    }

    @Test
    fun `a singular count is not pluralised`() {
        val result = Backup.parse(encode(mapOf("tasks" to rows(1), "blocks" to rows(1), "settings" to rows(1)))) as BackupParse.Ok
        assertEquals("1 task, 1 block", result.file.describe())
    }

    @Test
    fun `the tables an app restore replaces are only those present in the file`() {
        val result = Backup.parse(encode(mapOf("tasks" to rows(1), "blocks" to rows(1), "settings" to rows(1)))) as BackupParse.Ok
        assertEquals(listOf("settings", "tasks", "blocks"), Backup.TABLES.filter { it in result.file.tables })
    }

    @Test
    fun `the suggested file name carries the date`() {
        assertEquals("planner-backup-2026-10-26.json", Backup.fileName(LocalDate.of(2026, 10, 26)))
        assertEquals("planner-backup-2026-01-05.json", Backup.fileName(LocalDate.of(2026, 1, 5)))
    }
}
