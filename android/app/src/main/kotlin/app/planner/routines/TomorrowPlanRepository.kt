package app.planner.routines

import app.planner.data.BlockDao
import app.planner.data.TaskRepository
import app.planner.data.toBlock
import app.planner.plan.PlanRepository
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/** Reads the plan of one day as the rows the 23:30 sheet and the notifications describe, and approves it. */
@Singleton
class TomorrowPlanRepository @Inject constructor(
    private val blocks: BlockDao,
    private val tasks: TaskRepository,
    private val plan: PlanRepository,
    private val routines: RoutineSettings,
    private val notifier: Notifier,
    private val clock: Clock,
) {
    suspend fun rowsOn(date: LocalDate): List<PlanRow> =
        TomorrowPlan.rows(
            date = date,
            blocks = blocks.between(date.toString(), date.toString()).map { it.toBlock() },
            tasks = tasks.snapshot().associateBy { it.id },
            zone = clock.zone,
        )

    /**
     * What [date] holds besides its task blocks: calendar events at fixed times (and commitments), and tasks the
     * planner could not give a time. The note and the notification count these too, so a day with only an
     * event or a task waiting for room is never described as empty.
     */
    suspend fun factsOn(date: LocalDate): DayFacts {
        val zone = clock.zone
        val fixed = plan.dayViews(date, date).first().singleOrNull()?.fixed.orEmpty().map { e ->
            val start = e.start.atZone(zone)
            val end = e.end.atZone(zone)
            FixedLine(
                start = if (start.toLocalDate() < date) LocalTime.MIN else start.toLocalTime(),
                end = if (end.toLocalDate() > date) LocalTime.of(23, 59) else end.toLocalTime(),
                title = e.title,
            )
        }.sortedBy { it.start }
        val placed = blocks.fromDate(LocalDate.now(clock).toString()).mapNotNull { it.taskId }.toSet()
        val unplaced = TomorrowPlan.unplaced(tasks.snapshot(), placed, date, zone).take(MAX_UNPLACED).map { it.title }
        return DayFacts(fixed, unplaced)
    }

    /**
     * "Approve and sleep". Every switched-off task is deferred to the day after [date]: its block is dropped,
     * it becomes available from then, and the plan is rebuilt. Returns how many tasks were deferred.
     * The rows are read again here, so a plan that changed while the sheet was open is handled correctly.
     */
    suspend fun approve(date: LocalDate, excluded: Set<Long>): Int {
        val rows = rowsOn(date)
        val deferred = TomorrowPlan.deferrals(rows, excluded, tasks.snapshot().associateBy { it.id }, date, clock.zone)
        if (deferred.isNotEmpty()) {
            deferred.forEach { plan.forgetBlocks(it.id) }
            tasks.saveAll(deferred)
            plan.replan()
        }
        routines.markPlanApproved(date)
        notifier.cancel(Routine.PLAN_READY)
        return deferred.size
    }

    private companion object {
        const val MAX_UNPLACED = 8
    }
}
