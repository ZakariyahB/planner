package app.planner.assistant.tools

import app.planner.engine.changeset.ChangeSource
import app.planner.engine.ports.ChangeDraft
import app.planner.engine.ports.NowProvider
import app.planner.engine.ports.PlannerQueries
import app.planner.engine.ports.ProposalOutcome
import app.planner.engine.ports.ProposalPort
import app.planner.engine.ports.ReplanReason
import app.planner.engine.ports.TaskStore
import app.planner.engine.model.TaskStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The assistant's six tools over the ports. Reads go to [PlannerQueries] and [TaskStore]; the two proposing tools
 * go to [ProposalPort], which builds a ChangeSet for the approval card and writes nothing. No tool here can reach a
 * calendar writer: this module cannot even see one (AssistantIsolationTest).
 *
 * The model never computes times. It passes intent (which event, which day, how long, a time the user said) and
 * the engine answers with times.
 */
class AssistantTools(
    private val queries: PlannerQueries,
    private val proposals: ProposalPort,
    private val tasks: TaskStore,
    private val now: NowProvider,
) : ToolExecutor {

    override suspend fun execute(name: String, input: JsonObject): ToolOutcome {
        val tool = ToolNames.all.firstOrNull { it.equals(name.trim(), ignoreCase = true) }
            ?: return ToolOutcome(
                ToolResults.error("There is no tool called \"$name\". The tools are: ${ToolNames.all.joinToString()}. Nothing can be applied from chat: proposals wait for the user's approval."),
                isError = true,
            )
        return try {
            val i = Input(input)
            when (tool) {
                ToolNames.GET_WEEK -> ok(getWeek(i))
                ToolNames.GET_TASKS -> ok(getTasks(i))
                ToolNames.GET_PRAYER_ANCHORS -> ok(getAnchors(i))
                ToolNames.FIND_EVENTS -> ok(findEvents(i))
                ToolNames.PROPOSE_CHANGES -> proposed(proposeChanges(i))
                ToolNames.REPLAN -> proposed(replan(i))
                else -> error("unreachable")
            }
        } catch (e: ToolInputException) {
            ToolOutcome(ToolResults.error(e.message ?: "Bad input"), isError = true)
        }
    }

    private fun ok(json: JsonObject) = ToolOutcome(json.toString())

    private fun proposed(outcome: ProposalOutcome) = ToolOutcome(
        ToolResults.outcome(outcome).toString(),
        proposalId = (outcome as? ProposalOutcome.Proposed)?.changeSet?.id,
    )

    private fun today(): LocalDate = now.now().toLocalDate()

    // ---- read-only ----

    private suspend fun getWeek(i: Input): JsonObject {
        val from = i.date("from") ?: today()
        val to = i.date("to") ?: from.plusDays(6)
        if (to < from) throw ToolInputException("to must not be before from")
        val span = ChronoUnit.DAYS.between(from, to) + 1
        if (span > MAX_WEEK_DAYS) throw ToolInputException("Ask for at most $MAX_WEEK_DAYS days at a time")
        val starts = generateSequence(from) { it.plusDays(7) }.takeWhile { it <= to }.toList()
        val days = starts.flatMap { queries.week(it).days }
            .filter { it.date in from..to }
            .distinctBy { it.date }
            .sortedBy { it.date }
        return buildJsonObject {
            put("from", from.toString())
            put("to", to.toString())
            put("days", buildJsonArray { days.forEach { add(ToolResults.day(it)) } })
        }
    }

    private suspend fun getTasks(i: Input): JsonObject {
        val status = i.string("status")?.lowercase()
        val all = tasks.all()
        val chosen = when (status) {
            null -> all.filter { it.status == TaskStatus.BACKLOG || it.status == TaskStatus.SCHEDULED }
            "all" -> all
            else -> {
                val s = TaskStatus.entries.firstOrNull { it.name.equals(status, ignoreCase = true) }
                    ?: throw ToolInputException("status must be one of: backlog, scheduled, done, skipped, all")
                all.filter { it.status == s }
            }
        }.sortedWith(compareBy({ it.assignedDay ?: LocalDate.MAX }, { it.title }))
        val needsALook = tasks.observeNeedsALook().first()
        return buildJsonObject {
            put("tasks", buildJsonArray { chosen.forEach { add(ToolResults.task(it)) } })
            if (needsALook.isNotEmpty()) {
                put(
                    "needs_a_look",
                    buildJsonArray {
                        needsALook.forEach { u ->
                            add(buildJsonObject { put("title", u.title); put("calendar", u.calendarName.trim()); put("date", u.date.toString()) })
                        }
                    },
                )
            }
        }
    }

    private suspend fun getAnchors(i: Input): JsonObject {
        val date = i.requireDate("date")
        val anchors = queries.anchors(date)
        return buildJsonObject {
            put("date", date.toString())
            put("weekday", ToolResults.weekday(date))
            put("anchors", buildJsonArray { anchors.sortedBy { it.begin }.forEach { add(ToolResults.anchor(it)) } })
            put("rule", "Each prayer is a 15-minute slot from its begin time. The engine adds the buffer; never add it yourself.")
        }
    }

    private suspend fun findEvents(i: Input): JsonObject {
        val query = i.requireString("query")
        val from = i.date("from") ?: today().minusDays(7)
        val to = i.date("to") ?: today().plusDays(35)
        if (to < from) throw ToolInputException("to must not be before from")
        val found = queries.findEvents(query, from, to.plusDays(1))
        return buildJsonObject {
            put("query", query)
            put("from", from.toString())
            put("to", to.toString())
            put("candidates", buildJsonArray { found.take(MAX_CANDIDATES).forEach { add(ToolResults.snapshot(it)) } })
            if (found.size > MAX_CANDIDATES) put("more", found.size - MAX_CANDIDATES)
            if (found.isEmpty()) put("next", "No event matched. Try a shorter query or a wider date range, or ask the user.")
            if (found.size > 1) put("next", "More than one event matched. If the user's words do not pick exactly one, ask which.")
        }
    }

    // ---- proposing (never writing) ----

    private suspend fun proposeChanges(i: Input): ProposalOutcome {
        val summary = i.requireString("summary")
        val changes = i.objects("changes")
        if (changes.isEmpty()) throw ToolInputException("changes must list at least one change")
        if (changes.size > MAX_CHANGES) throw ToolInputException("Propose at most $MAX_CHANGES changes at a time")
        // Every draft is checked before anything is sent, so a bad one never leaves a half proposal.
        val drafts = changes.map(::draft)
        return proposals.propose(drafts, ChangeSource.ASSISTANT, summary)
    }

    private fun draft(c: Input): ChangeDraft = when (val action = c.requireString("action").lowercase()) {
        ToolNames.Actions.ADD -> ChangeDraft.AddTask(
            title = c.requireString("title"),
            category = c.category("category"),
            durationMin = duration(c) ?: throw ToolInputException("duration_min is required for add"),
            day = c.date("day"),
            earliestStart = c.time("earliest_start"),
            afterPrayer = c.afterPrayer("after_prayer"),
            deadline = c.date("deadline"),
            splittable = c.boolean("splittable") ?: true,
        )
        ToolNames.Actions.SCHEDULE_TASK -> ChangeDraft.ScheduleTask(c.requireString("task_id"), c.date("day"))
        ToolNames.Actions.MOVE -> ChangeDraft.MoveEvent(
            ref = c.requireEventRef(),
            toDay = c.date("to_day") ?: throw ToolInputException("to_day is required for move"),
            earliestStart = c.time("earliest_start"),
        )
        ToolNames.Actions.EDIT -> {
            val title = c.string("title")
            val duration = duration(c)
            val category = c.category("category")
            if (title == null && duration == null && category == null) {
                throw ToolInputException("edit needs at least one of title, duration_min or category")
            }
            ChangeDraft.EditEvent(c.requireEventRef(), title, duration, category, c.scope("scope"))
        }
        ToolNames.Actions.REMOVE -> ChangeDraft.RemoveEvent(c.requireEventRef(), c.scope("scope"))
        else -> throw ToolInputException("action must be one of: ${ToolNames.Actions.all.joinToString()} (got \"$action\")")
    }

    private fun duration(c: Input): Int? = c.int("duration_min")?.also {
        if (it !in 5..MAX_DURATION_MIN) throw ToolInputException("duration_min must be between 5 and $MAX_DURATION_MIN")
    }

    private suspend fun replan(i: Input): ProposalOutcome {
        val source = ChangeSource.ASSISTANT
        return when (val reason = i.requireString("reason").lowercase()) {
            ToolNames.Reasons.OVERRUN -> {
                val ref = i.requireEventRef()
                val extra = i.int("extra_min") ?: throw ToolInputException("extra_min is required for overrun")
                if (extra !in 1..MAX_DURATION_MIN) throw ToolInputException("extra_min must be between 1 and $MAX_DURATION_MIN")
                proposals.replan(ReplanReason.Overrun(ref, extra), source)
            }
            ToolNames.Reasons.STOPPED_EARLY -> {
                val ref = i.requireEventRef()
                val at = i.dateTime("at", today()) ?: throw ToolInputException("at is required for stopped_early (the time the user said)")
                proposals.replan(ReplanReason.StoppedEarly(ref, at), source)
            }
            ToolNames.Reasons.PLAN_DAYS -> {
                val days = i.dates("days")?.distinct()?.sorted()
                if (days.isNullOrEmpty()) throw ToolInputException("days is required for plan_days")
                if (days.size > MAX_WEEK_DAYS) throw ToolInputException("Plan at most $MAX_WEEK_DAYS days at a time")
                proposals.planDays(days, source)
            }
            ToolNames.Reasons.ROLLOVER -> {
                val start = i.date("target_week_start") ?: throw ToolInputException("target_week_start is required for rollover")
                if (start.dayOfWeek != DayOfWeek.MONDAY) throw ToolInputException("target_week_start must be the Monday of the target week")
                proposals.rollover(start, source)
            }
            ToolNames.Reasons.OTHER -> proposals.replan(ReplanReason.Free(i.requireString("text")), source)
            else -> throw ToolInputException("reason must be one of: ${ToolNames.Reasons.all.joinToString()} (got \"$reason\")")
        }
    }

    private companion object {
        const val MAX_WEEK_DAYS = 14L
        const val MAX_CANDIDATES = 20
        const val MAX_CHANGES = 20
        const val MAX_DURATION_MIN = 720
    }
}
