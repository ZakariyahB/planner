package app.planner.ai

import app.planner.calendar.AllDayTasks
import app.planner.calendar.EventTiming
import app.planner.data.TaskRepository
import app.planner.model.Task
import app.planner.model.TaskStatus
import app.planner.plan.PlanRepository
import app.planner.readiness.Readiness
import app.planner.readiness.ReadinessState
import app.planner.readiness.phases
import app.planner.scheduler.Commitments
import app.planner.sleep.ReadinessRepository
import app.planner.social.SocialRepository
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the model may read: summaries of the plan, tasks, sleep, readiness and social time. Never raw data. */
@Singleton
class AiReadTools @Inject constructor(
    private val plan: PlanRepository,
    private val tasks: TaskRepository,
    private val readiness: ReadinessRepository,
    private val social: SocialRepository,
    private val clock: Clock,
) {
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    suspend fun day(date: LocalDate): JsonObject {
        val view = plan.dayViews(date, date).first().single()
        val titles = tasks.snapshot().associate { it.id to it.title }
        return buildJsonObject {
            put("date", date.toString())
            put("weekday", date.dayOfWeek.name.lowercase())
            put(
                "blocks",
                buildJsonArray {
                    view.blocks.forEach { b ->
                        add(
                            buildJsonObject {
                                put("block_id", b.id)
                                b.taskId?.let { put("task_id", it) }
                                put("title", b.taskId?.let(titles::get) ?: "Block")
                                put("start", hhmm.format(b.start))
                                put("end", hhmm.format(b.end))
                                put("locked", b.locked)
                            },
                        )
                    }
                },
            )
            put(
                "fixed",
                buildJsonArray {
                    view.fixed.forEach { e ->
                        add(
                            buildJsonObject {
                                put("title", e.title)
                                put("start", hhmm.format(e.start.atZone(clock.zone)))
                                put("end", hhmm.format(e.end.atZone(clock.zone)))
                                if (Commitments.isCommitmentEvent(e)) {
                                    put("kind", "commitment")
                                    put("block_id", e.id.removePrefix("commitment-").toLongOrNull() ?: 0L)
                                } else {
                                    put("kind", "calendar")
                                }
                            },
                        )
                    }
                },
            )
            put(
                "all_day",
                buildJsonArray {
                    view.allDay.forEach { e ->
                        add(
                            buildJsonObject {
                                put("title", e.title)
                                put("done", view.allDayTasks.any { it.key == AllDayTasks.key(e, date) && it.done })
                                (e.timing as? EventTiming.AllDay)?.let { t ->
                                    put("first_day", t.start.toString())
                                    put("last_day", t.endExclusive.minusDays(1).toString())
                                }
                            },
                        )
                    }
                },
            )
            put("prayers", buildJsonObject { view.prayers.forEach { (prayer, time) -> put(prayer.name.lowercase(), hhmm.format(time)) } })
            put(
                "asleep",
                buildJsonArray {
                    view.asleep.forEach { a ->
                        add(buildJsonObject {
                            put("from", a.start.atZone(clock.zone).toLocalDateTime().withSecond(0).withNano(0).toString())
                            put("to", a.end.atZone(clock.zone).toLocalDateTime().withSecond(0).withNano(0).toString())
                        })
                    }
                },
            )
        }
    }

    suspend fun tasks(status: String?, dueBefore: LocalDate?): JsonObject {
        val wanted: (Task) -> Boolean = when (status ?: "open") {
            "open" -> { t -> t.status == TaskStatus.OPEN || t.status == TaskStatus.PARTIAL }
            "done" -> { t -> t.status == TaskStatus.DONE }
            "all" -> { t -> t.status != TaskStatus.DROPPED }
            else -> throw IllegalArgumentException("status must be open, done or all")
        }
        val list = tasks.snapshot()
            .filter(wanted)
            .filter { t -> dueBefore == null || t.dueAt?.atZone(clock.zone)?.toLocalDate()?.let { it <= dueBefore } == true }
            .sortedWith(compareBy({ it.dueAt }, { it.title.lowercase() }))
            .take(MAX_TASKS)
        return buildJsonObject {
            put(
                "tasks",
                buildJsonArray {
                    list.forEach { t ->
                        add(
                            buildJsonObject {
                                put("task_id", t.id)
                                put("title", t.title)
                                t.module?.let { put("module", it) }
                                put("estimate_minutes", t.estMin)
                                t.dueAt?.let { put("due", it.atZone(clock.zone).toLocalDate().toString()) }
                                t.startAt?.let { put("start_time", hhmm.format(it)) }
                                put("priority", t.priority.name.lowercase())
                                put("status", t.status.name.lowercase())
                            },
                        )
                    }
                },
            )
        }
    }

    suspend fun sleep(nights: Int): JsonObject {
        val list = readiness.recentNights(nights.coerceIn(1, 14))
        return buildJsonObject {
            put(
                "nights",
                buildJsonArray {
                    list.forEach { n ->
                        add(
                            buildJsonObject {
                                put("morning_of", n.date.toString())
                                put("asleep_minutes", n.asleepMin)
                                put("lights_out", hhmm.format(n.lightsOut.atZone(clock.zone)))
                                put("woke", hhmm.format(n.lastEnd.atZone(clock.zone)))
                                put("periods", JsonArray(n.phases(clock.zone).map { JsonPrimitive(it.label()) }))
                            },
                        )
                    }
                },
            )
        }
    }

    suspend fun readiness(date: LocalDate): JsonObject = buildJsonObject {
        put("date", date.toString())
        if (date == LocalDate.now(clock)) {
            when (val state = readiness.evaluate().state) {
                ReadinessState.Waiting -> put("status", "waiting_for_sleep_data")
                is ReadinessState.Provisional -> {
                    put("status", "provisional")
                    putScore(state.readiness)
                    put("asleep_so_far_minutes", state.asleepSoFarMin)
                }
                is ReadinessState.Final -> {
                    put("status", "final")
                    putScore(state.readiness)
                }
            }
        } else {
            val stored = readiness.finalFor(date)
            if (stored == null) put("status", "no_score_recorded") else {
                put("status", "final")
                putScore(stored)
            }
        }
    }

    suspend fun socialTime(days: Int): JsonObject {
        val list = social.recent(days.coerceIn(1, 14))
        return buildJsonObject {
            put(
                "days",
                buildJsonArray {
                    list.forEach { d ->
                        add(
                            buildJsonObject {
                                put("date", d.date.toString())
                                put("minutes", d.minutes)
                                put("budget_minutes", d.budgetMin)
                                put("per_app", buildJsonObject { d.perApp.forEach { (pkg, minutes) -> put(pkg, minutes) } })
                            },
                        )
                    }
                },
            )
        }
    }

    private fun JsonObjectBuilder.putScore(r: Readiness) {
        put("score", r.score)
        put("band", r.band.name.lowercase())
        put("asleep_minutes", r.inputs.asleepMin)
        put("sleep_debt_minutes_7_nights", r.inputs.debtMin)
        put("lights_out_minutes_late", r.inputs.lightsOutDelayMin)
    }

    private companion object {
        const val MAX_TASKS = 80
    }
}
