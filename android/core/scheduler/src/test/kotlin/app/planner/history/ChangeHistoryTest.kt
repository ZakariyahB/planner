package app.planner.history

import app.planner.scheduler.Block
import app.planner.scheduler.BlockDiff
import app.planner.scheduler.BlockKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChangeHistoryTest {
    private val zone = ZoneId.of("Europe/London")
    private val now = Instant.parse("2026-10-28T15:00:00Z") // Wednesday

    private fun at(date: String, time: String) = LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    private fun block(date: String, from: String, to: String, locked: Boolean = false, kind: BlockKind = BlockKind.TASK, label: String? = null) =
        Block(id = 1, taskId = if (kind == BlockKind.TASK) 5 else null, date = LocalDate.parse(date), start = LocalTime.parse(from), end = LocalTime.parse(to), locked = locked, kind = kind, label = label)

    private fun entry(id: Long, ts: Instant, actor: String = "USER", summary: String = "Moved OS", diffs: List<BlockDiff> = emptyList(), undone: Boolean = false) =
        HistoryEntry(id, ts.toEpochMilli(), actor, summary, diffs, undone)

    @Test
    fun `entries are grouped by day, newest first, with friendly day names`() {
        val days = ChangeHistory.group(
            listOf(
                entry(1, at("2026-10-27", "21:14")),
                entry(2, at("2026-10-28", "09:41")),
                entry(3, at("2026-10-28", "14:07")),
                entry(4, at("2026-10-25", "10:00")),
            ),
            now, zone,
        )
        assertEquals(listOf("Today", "Yesterday", "Sun 25 Oct"), days.map { it.title })
        assertEquals(listOf(3L, 2L), days[0].items.map { it.id })
    }

    @Test
    fun `an item has a title, time, who and whether it was undone`() {
        val item = ChangeHistory.group(listOf(entry(7, at("2026-10-28", "14:07"), actor = "AI", summary = "Moved OS worksheet", undone = true)), now, zone)
            .single().items.single()
        assertEquals("Moved OS worksheet", item.title)
        assertEquals("14:07", item.time)
        assertEquals("Assistant", item.who)
        assertTrue(item.undone)
    }

    @Test
    fun `who made the change is named plainly`() {
        assertEquals("You", ChangeHistory.who("USER"))
        assertEquals("Assistant", ChangeHistory.who("AI"))
        assertEquals("Planner", ChangeHistory.who("SCHEDULER"))
        assertEquals("Planner", ChangeHistory.who("something else"))
    }

    @Test
    fun `a move shows the old and new times`() {
        val diff = BlockDiff(block("2026-10-28", "10:30", "11:30"), block("2026-10-28", "11:30", "12:30"))
        assertEquals("10:30–11:30 to 11:30–12:30", ChangeHistory.describe(listOf(diff)))
    }

    @Test
    fun `a move to another day names both days`() {
        val diff = BlockDiff(block("2026-10-28", "10:30", "11:30"), block("2026-10-29", "09:00", "10:00"))
        assertEquals("Wed 28 Oct 10:30–11:30 to Thu 29 Oct 09:00–10:00", ChangeHistory.describe(listOf(diff)))
    }

    @Test
    fun `a removed block says when it was`() {
        assertEquals("Was Wed 28 Oct 16:50–18:05", ChangeHistory.describe(listOf(BlockDiff(block("2026-10-28", "16:50", "18:05"), null))))
    }

    @Test
    fun `an added commitment says when and that it is locked`() {
        val added = block("2026-10-29", "14:00", "15:00", locked = true, kind = BlockKind.FIXED, label = "Interview")
        assertEquals("Thu 29 Oct 14:00–15:00, locked", ChangeHistory.describe(listOf(BlockDiff(null, added))))
    }

    @Test
    fun `several blocks are summarised by count`() {
        val diffs = listOf(
            BlockDiff(block("2026-10-28", "10:00", "11:00"), block("2026-10-28", "11:00", "12:00")),
            BlockDiff(block("2026-10-28", "13:00", "14:00"), block("2026-10-28", "14:00", "15:00")),
            BlockDiff(block("2026-10-28", "16:00", "17:00"), null),
        )
        assertEquals("3 blocks changed", ChangeHistory.describe(diffs))
    }

    @Test
    fun `no recorded blocks gives no detail`() {
        assertEquals("", ChangeHistory.describe(emptyList()))
    }

    @Test
    fun `an empty history has no days`() {
        assertEquals(emptyList(), ChangeHistory.group(emptyList(), now, zone))
    }
}
