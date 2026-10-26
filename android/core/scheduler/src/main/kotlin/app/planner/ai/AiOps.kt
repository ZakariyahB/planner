package app.planner.ai

import app.planner.scheduler.Actor
import app.planner.scheduler.ChangeSet
import app.planner.scheduler.Op
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** A change the model proposes. It never sets times directly: the scheduler validates every one. */
sealed interface AiOp {
    data class MoveBlock(val blockId: Long, val date: LocalDate, val start: LocalTime, val end: LocalTime) : AiOp

    /** Takes a block off the plan; its task stays open and is placed again later. */
    data class RemoveBlock(val blockId: Long) : AiOp

    /** Gives up on a task altogether. */
    data class DropTask(val taskId: Long) : AiOp
}

/** The ops that parsed, and a message for each one that did not, so the model can fix its proposal. */
data class ParsedOps(val ops: List<AiOp>, val errors: List<String>) {
    val ok: Boolean get() = ops.isNotEmpty() && errors.isEmpty()
}

object AiOps {
    const val MAX_OPS = 20

    fun parse(array: JsonArray?): ParsedOps {
        if (array == null || array.isEmpty()) return ParsedOps(emptyList(), listOf("ops must be a non-empty list"))
        if (array.size > MAX_OPS) return ParsedOps(emptyList(), listOf("at most $MAX_OPS ops in one proposal"))

        val ops = mutableListOf<AiOp>()
        val errors = mutableListOf<String>()
        array.forEachIndexed { index, element ->
            val label = "op ${index + 1}"
            val obj = element as? JsonObject
            if (obj == null) {
                errors += "$label: must be an object"
                return@forEachIndexed
            }
            try {
                ops += parseOp(obj)
            } catch (e: IllegalArgumentException) {
                errors += "$label: ${e.message}"
            }
        }
        return ParsedOps(ops, errors)
    }

    private fun parseOp(obj: JsonObject): AiOp = when (val kind = obj.text("op")) {
        "move_block" -> {
            val start = obj.time("start")
            val end = obj.time("end")
            require(end > start) { "end must be after start" }
            AiOp.MoveBlock(obj.id("block_id"), obj.date("date"), start, end)
        }
        "remove_block" -> AiOp.RemoveBlock(obj.id("block_id"))
        "drop_task" -> AiOp.DropTask(obj.id("task_id"))
        else -> throw IllegalArgumentException("unknown op \"${kind ?: ""}\"")
    }

    /** Block moves and removals, as the scheduler's own change set. */
    fun toChangeSet(ops: List<AiOp>): ChangeSet = ChangeSet(
        ops.mapNotNull { op ->
            when (op) {
                is AiOp.MoveBlock -> Op.Move(op.blockId, op.date, op.start, op.end)
                is AiOp.RemoveBlock -> Op.Drop(op.blockId)
                is AiOp.DropTask -> null
            }
        },
        Actor.AI,
    )

    fun droppedTaskIds(ops: List<AiOp>): List<Long> = ops.filterIsInstance<AiOp.DropTask>().map { it.taskId }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.id(key: String): Long =
        text(key)?.toLongOrNull() ?: throw IllegalArgumentException("$key must be a number")

    private fun JsonObject.date(key: String): LocalDate = try {
        LocalDate.parse(text(key) ?: throw IllegalArgumentException("$key is required (YYYY-MM-DD)"))
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("$key must be YYYY-MM-DD")
    }

    private fun JsonObject.time(key: String): LocalTime = try {
        LocalTime.parse(text(key) ?: throw IllegalArgumentException("$key is required (HH:mm)"))
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("$key must be HH:mm")
    }
}
