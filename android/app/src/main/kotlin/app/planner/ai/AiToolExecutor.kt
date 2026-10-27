package app.planner.ai

import app.planner.data.DayLogDao
import app.planner.data.DayLogEntity
import app.planner.data.TaskRepository
import app.planner.model.Priority
import app.planner.model.ProgressOutcome
import app.planner.model.Task
import app.planner.model.TaskProgress
import app.planner.model.TaskStatus
import app.planner.model.TaskSource
import app.planner.model.onDates
import app.planner.plan.MoveResult
import app.planner.plan.PlanMessage
import app.planner.plan.PlanRepository
import app.planner.scheduler.Actor
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Runs the model's tool calls against the phone's own data. Every change goes through the planner's rules. */
@Singleton
class AiToolExecutor @Inject constructor(
    private val reads: AiReadTools,
    private val changes: AiPlanService,
    private val plan: PlanRepository,
    private val tasks: TaskRepository,
    private val dayLog: DayLogDao,
    private val clock: Clock,
) : ToolExecutor {

    override suspend fun execute(name: String, input: JsonObject): ToolOutcome = try {
        when (name) {
            AiToolNames.GET_DAY -> ok(reads.day(input.date("date") ?: LocalDate.now(clock)))
            AiToolNames.GET_TASKS -> ok(reads.tasks(input.text("status"), input.date("due_before")))
            AiToolNames.GET_SLEEP -> ok(reads.sleep(input.int("nights") ?: 7))
            AiToolNames.GET_READINESS -> ok(reads.readiness(input.date("date") ?: LocalDate.now(clock)))
            AiToolNames.GET_SOCIAL_TIME -> ok(reads.socialTime(input.int("days") ?: 7))
            AiToolNames.PROPOSE_CHANGES -> changes.propose(input.text("summary").orEmpty(), input["ops"] as? JsonArray)
            AiToolNames.APPLY_CHANGES -> changes.apply(input.text("proposal_id") ?: throw IllegalArgumentException("proposal_id is required"))
            AiToolNames.ADD_TASK -> addTask(input)
            AiToolNames.ADD_COMMITMENT -> addCommitment(input)
            AiToolNames.UPDATE_TASK -> updateTask(input)
            AiToolNames.LOG_PROGRESS -> logProgress(input)
            AiToolNames.UNDO -> undo(input)
            else -> fail("Unknown tool \"$name\"")
        }
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "Invalid input")
    } catch (e: DateTimeParseException) {
        fail("Dates must be YYYY-MM-DD and times HH:mm")
    }

    private suspend fun addTask(input: JsonObject): ToolOutcome {
        val base = Task(
            title = input.text("title") ?: throw IllegalArgumentException("title is required"),
            module = input.text("module"),
            estMin = input.int("estimate_minutes") ?: throw IllegalArgumentException("estimate_minutes is required"),
            startAt = input.time("start_time"),
            priority = input.text("priority")?.let { p ->
                Priority.entries.firstOrNull { it.name.equals(p, ignoreCase = true) } ?: throw IllegalArgumentException("priority must be low, normal or high")
            } ?: Priority.NORMAL,
            source = TaskSource.CHAT,
        )
        val dates = (input["dates"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.map(LocalDate::parse)
        val created = when {
            dates.isNotEmpty() -> base.onDates(dates, clock.zone)
            else -> listOf(input.date("due_date")?.let { base.onDates(listOf(it), clock.zone).single() } ?: base)
        }
        tasks.saveAll(created)
        plan.replan()
        return ok(buildJsonObject { put("added", created.size) })
    }

    private suspend fun addCommitment(input: JsonObject): ToolOutcome {
        val title = input.text("title") ?: throw IllegalArgumentException("title is required")
        val date = input.date("date") ?: throw IllegalArgumentException("date is required")
        val start = input.time("start") ?: throw IllegalArgumentException("start is required")
        val end = input.time("end") ?: throw IllegalArgumentException("end is required")
        return when (val result = plan.addCommitment(title, date, start, end, Actor.AI)) {
            is MoveResult.Rejected -> fail(result.reasons.joinToString("; "))
            is MoveResult.Applied -> {
                changes.announce(PlanMessage("Added $title on ${date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}", result.changeId))
                ok(buildJsonObject { put("change_id", result.changeId) })
            }
        }
    }

    /**
     * Changes a task's length, chosen start time (on its own day), priority or title, then plans again. This is how
     * an all-day calendar item gets the time the assistant picked for it. Moving a task to another day is a
     * proposal instead, so the user approves it.
     */
    private suspend fun updateTask(input: JsonObject): ToolOutcome {
        val id = input.int("task_id")?.toLong() ?: throw IllegalArgumentException("task_id is required")
        val task = tasks.snapshot().firstOrNull { it.id == id } ?: throw IllegalArgumentException("No task with id $id")
        require(task.status == TaskStatus.OPEN || task.status == TaskStatus.PARTIAL) { "That task is already ${task.status.name.lowercase()}" }
        require(plan.placementOf(id)?.locked != true) { "That task's block is locked: ask the user to unlock it first" }

        var updated = task
        input.int("estimate_minutes")?.let { minutes ->
            require(minutes in 5..Task.MAX_EST_MIN) { "estimate_minutes must be 5 to ${Task.MAX_EST_MIN}" }
            updated = updated.copy(estMin = minutes)
        }
        input.text("title")?.let { updated = updated.copy(title = it) }
        input.text("priority")?.let { p ->
            val priority = Priority.entries.firstOrNull { it.name.equals(p, ignoreCase = true) } ?: throw IllegalArgumentException("priority must be low, normal or high")
            updated = updated.copy(priority = priority)
        }
        input.text("start_time")?.let { t ->
            updated = updated.copy(startAt = if (t.equals("none", ignoreCase = true) || t.equals("any", ignoreCase = true)) null else LocalTime.parse(t))
        }
        require(updated != task) { "Nothing to change: give estimate_minutes, start_time, priority or title" }

        tasks.save(updated)
        plan.forgetBlocks(id) // placed afresh below, at the chosen time if it can have it
        val summary = plan.replan()
        val conflict = summary.pinConflicts.firstOrNull { it.task.id == id }
        val placed = plan.placementOf(id)
        return ok(
            buildJsonObject {
                put("task", updated.title)
                put("estimate_minutes", updated.estMin)
                if (placed != null) {
                    put("date", placed.date.toString())
                    put("start", placed.start.toString())
                    put("end", placed.end.toString())
                } else {
                    put("status", "not placed: no room before it is due")
                }
                conflict?.let { put("time_conflict", "Could not use ${updated.startAt}: ${it.reason}. It was planned at the next free time instead.") }
            },
        )
    }

    private suspend fun logProgress(input: JsonObject): ToolOutcome {
        val id = input.int("task_id")?.toLong() ?: throw IllegalArgumentException("task_id is required")
        val task = tasks.snapshot().firstOrNull { it.id == id } ?: throw IllegalArgumentException("No task with id $id")
        val minutes = input.int("minutes") ?: throw IllegalArgumentException("minutes is required")
        val outcome = ProgressOutcome.parse(input.text("outcome")) ?: throw IllegalArgumentException("outcome must be done, partial or dropped")
        val updated = TaskProgress.apply(task, minutes, outcome)

        dayLog.insert(DayLogEntity(date = LocalDate.now(clock).toString(), taskId = id, plannedMin = task.estMin, actualMin = minutes, outcome = outcome.name, note = input.text("note")))
        if (outcome != ProgressOutcome.DONE) plan.forgetBlocks(id) // the rest is placed afresh
        tasks.save(updated)
        plan.replan()
        return ok(
            buildJsonObject {
                put("task", task.title)
                put("status", updated.status.name.lowercase())
                put("minutes_left", updated.estMin)
            },
        )
    }

    private suspend fun undo(input: JsonObject): ToolOutcome {
        val id = input.int("change_id")?.toLong() ?: throw IllegalArgumentException("change_id is required")
        return if (plan.undo(id)) ok(buildJsonObject { put("status", "undone") }) else fail("That change can't be undone (already undone, or not found)")
    }

    private fun ok(json: JsonObject) = ToolOutcome(json.toString())

    private fun fail(message: String) = ToolOutcome(buildJsonObject { put("error", message) }.toString(), isError = true)

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? = text(key)?.toDoubleOrNull()?.toInt()

    private fun JsonObject.date(key: String): LocalDate? = text(key)?.let(LocalDate::parse)

    private fun JsonObject.time(key: String): LocalTime? = text(key)?.let(LocalTime::parse)
}
