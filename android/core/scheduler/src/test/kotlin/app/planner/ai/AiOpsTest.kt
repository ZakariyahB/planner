package app.planner.ai

import app.planner.scheduler.Actor
import app.planner.scheduler.Op
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiOpsTest {
    private fun parse(json: String) = AiOps.parse(Json.parseToJsonElement(json).jsonArray)

    @Test
    fun `a move names the block, the day and the new times`() {
        val parsed = parse("""[{"op":"move_block","block_id":12,"date":"2026-10-28","start":"15:00","end":"16:30"}]""")
        assertTrue(parsed.ok)
        assertEquals(AiOp.MoveBlock(12, LocalDate.of(2026, 10, 28), LocalTime.of(15, 0), LocalTime.of(16, 30)), parsed.ops.single())
    }

    @Test
    fun `removing a block and dropping a task are understood`() {
        val parsed = parse("""[{"op":"remove_block","block_id":3},{"op":"drop_task","task_id":9}]""")
        assertEquals(listOf(AiOp.RemoveBlock(3), AiOp.DropTask(9)), parsed.ops)
    }

    @Test
    fun `ids may arrive as numeric strings`() {
        assertEquals(AiOp.RemoveBlock(3), parse("""[{"op":"remove_block","block_id":"3"}]""").ops.single())
    }

    @Test
    fun `an unknown op is reported with its position`() {
        val parsed = parse("""[{"op":"remove_block","block_id":1},{"op":"teleport"}]""")
        assertFalse(parsed.ok)
        assertTrue("op 2" in parsed.errors.single() && "teleport" in parsed.errors.single())
    }

    @Test
    fun `missing or malformed fields are reported`() {
        assertTrue("block_id" in parse("""[{"op":"remove_block"}]""").errors.single())
        assertTrue("start" in parse("""[{"op":"move_block","block_id":1,"date":"2026-10-28","start":"3pm","end":"16:30"}]""").errors.single())
        assertTrue("date" in parse("""[{"op":"move_block","block_id":1,"date":"tomorrow","start":"15:00","end":"16:30"}]""").errors.single())
        assertTrue("end" in parse("""[{"op":"move_block","block_id":1,"date":"2026-10-28","start":"15:00"}]""").errors.single())
    }

    @Test
    fun `an end that is not after the start is reported`() {
        assertTrue("after" in parse("""[{"op":"move_block","block_id":1,"date":"2026-10-28","start":"16:00","end":"15:00"}]""").errors.single())
    }

    @Test
    fun `an empty or missing list is not a proposal`() {
        assertFalse(parse("[]").ok)
        assertFalse(AiOps.parse(null).ok)
    }

    @Test
    fun `too many ops are refused`() {
        val many = (1..21).joinToString(",", "[", "]") { """{"op":"remove_block","block_id":$it}""" }
        assertTrue("20" in parse(many).errors.single())
    }

    @Test
    fun `good ops are kept even when another op is bad, so the model can be told what to fix`() {
        val parsed = parse("""[{"op":"remove_block","block_id":1},{"op":"remove_block"}]""")
        assertEquals(1, parsed.ops.size)
        assertEquals(1, parsed.errors.size)
        assertFalse(parsed.ok)
    }

    @Test
    fun `block ops become an AI change set and task drops are kept apart`() {
        val ops = listOf(
            AiOp.MoveBlock(1, LocalDate.of(2026, 10, 28), LocalTime.of(9, 0), LocalTime.of(10, 0)),
            AiOp.RemoveBlock(2),
            AiOp.DropTask(7),
        )
        val changeSet = AiOps.toChangeSet(ops)
        assertEquals(Actor.AI, changeSet.actor)
        assertEquals(
            listOf(Op.Move(1, LocalDate.of(2026, 10, 28), LocalTime.of(9, 0), LocalTime.of(10, 0)), Op.Drop(2)),
            changeSet.ops,
        )
        assertEquals(listOf(7L), AiOps.droppedTaskIds(ops))
    }
}
