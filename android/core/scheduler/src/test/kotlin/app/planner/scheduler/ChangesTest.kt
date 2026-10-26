package app.planner.scheduler

import app.planner.model.Anchor
import app.planner.model.Prayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChangesTest {
    private val scheduler = Scheduler()
    private val day = date("2026-10-28")

    private val ctx = DayContext(
        date = day,
        zone = london,
        fixed = listOf(fixed("bol", "2026-10-28", "10:15", "12:45"), fixed("halaqa", "2026-10-28", "14:00", "16:30")),
        prayers = prayers("12:40", "14:55", "16:35", "18:05"),
    )

    // OS 08:45-10:15, P&S 16:50-18:05, CW 18:20-21:20 (as planned in SchedulerTest).
    private val os = Block(1, taskId = 1, date = day, start = time("08:45"), end = time("10:15"))
    private val ps = Block(2, taskId = 2, date = day, start = time("16:50"), end = time("18:05"))
    private val cw = Block(3, taskId = 3, date = day, start = time("18:20"), end = time("21:20"))
    private val blocks = listOf(os, ps, cw)

    private val tasks = mapOf(
        1L to task(1, "OS worksheet", 90, due = "2026-10-28"),
        2L to task(2, "P&S", 75, due = "2026-10-28", anchor = Anchor(Prayer.ASR, 15)),
        3L to task(3, "CW", 180, due = "2026-10-28", from = instant("2026-10-28", "18:00")),
    )

    private val nextDay = DayContext(date("2026-10-29"), london)
    private val state = PlanState(blocks, mapOf(day to ctx, nextDay.date to nextDay), tasks)

    private fun move(id: Long, start: String, end: String, date: String = "2026-10-28", actor: Actor = Actor.USER) =
        ChangeSet(listOf(Op.Move(id, date(date), time(start), time(end))), actor)

    private fun kinds(v: Validation) = v.violations.filter { it.hard }.map { it.kind }.toSet()

    @Test
    fun `a clean small move is ok and small`() {
        val v = scheduler.validate(move(3, "18:30", "21:30"), state)
        assertTrue(v.ok)
        assertEquals(ChangeSize.SMALL, v.size)
    }

    @Test
    fun `moving onto a fixed event is rejected`() {
        assertTrue(ViolationKind.OVERLAP_FIXED in kinds(scheduler.validate(move(3, "14:30", "17:30"), state)))
    }

    @Test
    fun `moving onto prayer time is rejected`() {
        assertTrue(ViolationKind.OVERLAP_PRAYER in kinds(scheduler.validate(move(1, "12:45", "13:45"), state))) // Dhuhr 12:40-12:55
        assertTrue(ViolationKind.OVERLAP_PRAYER in kinds(scheduler.validate(move(2, "18:00", "18:15"), state))) // Isha 18:05-18:20
    }

    @Test
    fun `outside the work window is rejected`() {
        assertTrue(ViolationKind.OUTSIDE_WINDOW in kinds(scheduler.validate(move(1, "07:00", "08:30"), state)))
        assertTrue(ViolationKind.OUTSIDE_WINDOW in kinds(scheduler.validate(move(3, "21:00", "22:30"), state)))
    }

    @Test
    fun `overlapping another block is rejected`() {
        assertTrue(ViolationKind.OVERLAP_BLOCK in kinds(scheduler.validate(move(3, "17:50", "19:00"), state)))
    }

    @Test
    fun `a missing buffer between task blocks warns without rejecting`() {
        val a = Block(1, 1, day, time("08:45"), time("09:45"))
        val b = Block(2, 2, day, time("10:00"), time("10:10"))
        val st = PlanState(listOf(a, b), state.contexts, emptyMap())
        val v = scheduler.validate(move(2, "09:50", "10:10"), st)
        assertTrue(v.ok)
        assertTrue(v.violations.any { it.kind == ViolationKind.NO_BUFFER && !it.hard })
    }

    @Test
    fun `before availability or before the anchor is rejected`() {
        val late = state.copy(tasks = tasks + (3L to task(3, "CW", 180, from = instant("2026-10-28", "19:00"))))
        assertTrue(ViolationKind.BEFORE_AVAILABLE in kinds(scheduler.validate(move(3, "18:25", "21:25"), late)))
        assertTrue(ViolationKind.BEFORE_ANCHOR in kinds(scheduler.validate(move(2, "13:00", "14:00"), state)))
    }

    @Test
    fun `a locked block cannot be moved or resized by anyone until it is unlocked`() {
        val locked = state.copy(blocks = listOf(os.copy(locked = true), ps, cw))
        for (actor in listOf(Actor.USER, Actor.AI, Actor.SCHEDULER)) {
            val moved = scheduler.validate(move(1, "08:50", "10:05", actor = actor), locked)
            assertTrue(ViolationKind.LOCKED in kinds(moved), "move by $actor")
            val resized = scheduler.validate(move(1, "08:45", "10:00", actor = actor), locked)
            assertTrue(ViolationKind.LOCKED in kinds(resized), "resize by $actor")
        }
        assertTrue("Unlock" in scheduler.validate(move(1, "08:50", "10:05"), locked).violations.first { it.kind == ViolationKind.LOCKED }.message)
    }

    @Test
    fun `an unlocked block can be moved and resized`() {
        assertTrue(scheduler.validate(move(1, "08:50", "10:05"), state).ok)
        assertTrue(scheduler.validate(move(1, "08:45", "10:00"), state).ok)
    }

    @Test
    fun `a shift over an hour, another day or a drop is big`() {
        assertEquals(ChangeSize.BIG, scheduler.validate(move(3, "19:25", "22:25"), state).size)
        assertEquals(ChangeSize.BIG, scheduler.validate(move(3, "18:20", "21:20", date = "2026-10-29"), state).size)
        assertEquals(ChangeSize.BIG, scheduler.validate(ChangeSet(listOf(Op.Drop(3)), Actor.AI), state).size)
    }

    @Test
    fun `unknown blocks and days are reported`() {
        assertTrue(ViolationKind.UNKNOWN_BLOCK in kinds(scheduler.validate(move(99, "09:00", "10:00"), state)))
        assertTrue(ViolationKind.UNKNOWN_DAY in kinds(scheduler.validate(move(3, "18:20", "21:20", date = "2026-11-20"), state)))
    }

    @Test
    fun `too short blocks are rejected`() {
        assertTrue(ViolationKind.TOO_SHORT in kinds(scheduler.validate(move(1, "09:00", "09:10"), state)))
    }

    @Test
    fun `moving a block does not lock it, and apply then undo restores everything`() {
        val cs = move(3, "18:30", "21:30")
        val applied = apply(blocks, cs)
        val moved = applied.blocks.single { it.id == 3L }
        assertEquals(time("18:30"), moved.start)
        assertFalse(moved.locked)
        assertEquals(BlockOrigin.USER, moved.origin)

        val restored = undo(applied.blocks, applied.diffs)
        assertEquals(blocks.sortedBy { it.id }, restored.sortedBy { it.id })
    }

    @Test
    fun `an AI move keeps the block unlocked and a drop is undoable`() {
        val ai = apply(blocks, move(3, "18:30", "21:30", actor = Actor.AI))
        val moved = ai.blocks.single { it.id == 3L }
        assertFalse(moved.locked)
        assertEquals(BlockOrigin.AI, moved.origin)

        val dropped = apply(blocks, ChangeSet(listOf(Op.Drop(2)), Actor.USER))
        assertEquals(listOf(1L, 3L), dropped.blocks.map { it.id })
        assertEquals(blocks.sortedBy { it.id }, undo(dropped.blocks, dropped.diffs).sortedBy { it.id })
    }
}
