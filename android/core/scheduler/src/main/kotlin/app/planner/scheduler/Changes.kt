package app.planner.scheduler

import app.planner.model.Task
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs

enum class Actor { USER, AI, SCHEDULER }

sealed interface Op {
    /** Moves and/or resizes a block, possibly to another day. */
    data class Move(val blockId: Long, val date: LocalDate, val start: LocalTime, val end: LocalTime) : Op

    data class Drop(val blockId: Long) : Op
}

data class ChangeSet(val ops: List<Op>, val actor: Actor)

enum class ChangeSize { SMALL, BIG }

enum class ViolationKind {
    UNKNOWN_BLOCK, UNKNOWN_DAY, LOCKED, TOO_SHORT, OUTSIDE_WINDOW,
    OVERLAP_FIXED, OVERLAP_PRAYER, OVERLAP_SLEEP, OVERLAP_BLOCK, BEFORE_AVAILABLE, BEFORE_ANCHOR, NO_BUFFER,
}

/** [hard] violations reject the change; soft ones (a missing buffer) are only warnings. */
data class Violation(val blockId: Long, val kind: ViolationKind, val message: String, val hard: Boolean = true)

data class Validation(val violations: List<Violation>, val size: ChangeSize) {
    val ok: Boolean get() = violations.none { it.hard }
}

/** The state a change is checked against. */
data class PlanState(
    val blocks: List<Block>,
    val contexts: Map<LocalDate, DayContext>,
    val tasks: Map<Long, Task>,
)

data class BlockDiff(val before: Block?, val after: Block?)

data class AppliedChange(val blocks: List<Block>, val diffs: List<BlockDiff>)

private const val MIN_BLOCK_MIN = 15
private const val SMALL_SHIFT_MIN = 60

fun Scheduler.validate(changeSet: ChangeSet, state: PlanState): Validation {
    val byId = state.blocks.associateBy { it.id }
    val violations = mutableListOf<Violation>()
    var after = state.blocks
    val moved = mutableSetOf<Long>()

    for (op in changeSet.ops) {
        when (op) {
            is Op.Move -> {
                val block = byId[op.blockId]
                if (block == null) {
                    violations += Violation(op.blockId, ViolationKind.UNKNOWN_BLOCK, "That block no longer exists")
                    continue
                }
                if (block.locked) {
                    violations += Violation(block.id, ViolationKind.LOCKED, "That block is locked. Unlock it to move it")
                    continue
                }
                if (op.end <= op.start || op.end.toMinuteOfDay() - op.start.toMinuteOfDay() < MIN_BLOCK_MIN) {
                    violations += Violation(block.id, ViolationKind.TOO_SHORT, "A block must be at least $MIN_BLOCK_MIN minutes")
                    continue
                }
                after = after.map { if (it.id == block.id) it.copy(date = op.date, start = op.start, end = op.end) else it }
                moved += block.id
            }
            is Op.Drop -> {
                if (byId[op.blockId] == null) {
                    violations += Violation(op.blockId, ViolationKind.UNKNOWN_BLOCK, "That block no longer exists")
                } else {
                    after = after.filterNot { it.id == op.blockId }
                }
            }
        }
    }

    for (block in after.filter { it.id in moved }) {
        violations += checkBlock(block, after, state)
    }
    return Validation(violations, sizeOf(changeSet, byId))
}

private fun Scheduler.checkBlock(block: Block, all: List<Block>, state: PlanState): List<Violation> {
    val ctx = state.contexts[block.date]
        ?: return listOf(Violation(block.id, ViolationKind.UNKNOWN_DAY, "That day isn't planned"))
    val layout = DayLayout(ctx, rules, honourNotBefore = false)
    val slot = Slot(block.start.toMinuteOfDay(), block.end.toMinuteOfDay())
    val out = mutableListOf<Violation>()

    if (slot.start < layout.windowStart || slot.end > layout.windowEnd) {
        out += Violation(
            block.id, ViolationKind.OUTSIDE_WINDOW,
            "Work is planned between ${layout.windowStart.toLocalTime()} and ${layout.windowEnd.toLocalTime()}",
        )
    }
    if (layout.fixedSlots.any { it.overlaps(slot) }) {
        out += Violation(block.id, ViolationKind.OVERLAP_FIXED, "That clashes with a fixed commitment")
    }
    if (layout.prayerSlots.any { it.overlaps(slot) }) {
        out += Violation(block.id, ViolationKind.OVERLAP_PRAYER, "That clashes with prayer time")
    }
    if (layout.sleepSlots.any { it.overlaps(slot) }) {
        out += Violation(block.id, ViolationKind.OVERLAP_SLEEP, "That's while you're asleep")
    }
    val others = all.filter { it.id != block.id && it.date == block.date }
    if (others.any { Slot(it.start.toMinuteOfDay(), it.end.toMinuteOfDay()).overlaps(slot) }) {
        out += Violation(block.id, ViolationKind.OVERLAP_BLOCK, "That overlaps another block")
    } else if (others.any { gapTo(it, slot) in 0 until rules.blockBufferMin }) {
        out += Violation(block.id, ViolationKind.NO_BUFFER, "Less than ${rules.blockBufferMin} minutes between blocks", hard = false)
    }

    block.taskId?.let(state.tasks::get)?.let { task ->
        task.availableFrom?.atZone(ctx.zone)?.let { from ->
            if (from.toLocalDate() > block.date ||
                (from.toLocalDate() == block.date && from.toLocalTime().toMinuteOfDay() > slot.start)
            ) {
                out += Violation(block.id, ViolationKind.BEFORE_AVAILABLE, "That isn't available until ${from.toLocalTime().withSecond(0).withNano(0)}")
            }
        }
        task.anchor?.let { a ->
            ctx.prayers[a.prayer]?.let { t ->
                if (slot.start < t.toMinuteOfDay() + a.offsetMin) {
                    out += Violation(block.id, ViolationKind.BEFORE_ANCHOR, "That must come after ${a.prayer.name.lowercase()}")
                }
            }
        }
    }
    return out
}

private fun gapTo(other: Block, slot: Slot): Int {
    val o = Slot(other.start.toMinuteOfDay(), other.end.toMinuteOfDay())
    return if (o.end <= slot.start) slot.start - o.end else o.start - slot.end
}

private fun sizeOf(changeSet: ChangeSet, byId: Map<Long, Block>): ChangeSize {
    val small = changeSet.ops.all { op ->
        op is Op.Move && byId[op.blockId]?.let { b ->
            b.date == op.date &&
                abs(op.start.toMinuteOfDay() - b.start.toMinuteOfDay()) <= SMALL_SHIFT_MIN &&
                abs(op.end.toMinuteOfDay() - b.end.toMinuteOfDay()) <= SMALL_SHIFT_MIN
        } == true
    }
    return if (small) ChangeSize.SMALL else ChangeSize.BIG
}

/**
 * Applies a change that [validate] accepted. Locking is only ever done on purpose, so a moved block keeps
 * its lock state; a user move marks it user-placed. Returns the diffs for the change log.
 */
fun apply(blocks: List<Block>, changeSet: ChangeSet): AppliedChange {
    val byId = blocks.associateBy { it.id }
    val diffs = mutableListOf<BlockDiff>()
    var result = blocks
    for (op in changeSet.ops) {
        when (op) {
            is Op.Move -> {
                val before = byId[op.blockId] ?: continue
                val after = before.copy(
                    date = op.date,
                    start = op.start,
                    end = op.end,
                    locked = before.locked,
                    origin = when (changeSet.actor) {
                        Actor.USER -> BlockOrigin.USER
                        Actor.AI -> BlockOrigin.AI
                        Actor.SCHEDULER -> before.origin
                    },
                )
                result = result.map { if (it.id == before.id) after else it }
                diffs += BlockDiff(before, after)
            }
            is Op.Drop -> {
                val before = byId[op.blockId] ?: continue
                result = result.filterNot { it.id == before.id }
                diffs += BlockDiff(before, null)
            }
        }
    }
    return AppliedChange(result, diffs)
}

/** Restores the state before [diffs] were applied. */
fun undo(blocks: List<Block>, diffs: List<BlockDiff>): List<Block> {
    var result = blocks
    for (d in diffs.asReversed()) {
        result = when {
            d.before != null && d.after != null -> result.map { if (it.id == d.after.id) d.before else it }
            d.before != null -> result + d.before
            d.after != null -> result.filterNot { it.id == d.after.id }
            else -> result
        }
    }
    return result
}
