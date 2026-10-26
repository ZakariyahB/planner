package app.planner.routines

import app.planner.data.BlockDao
import app.planner.data.TaskRepository
import app.planner.data.toBlock
import app.planner.plan.PlanRepository
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

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
}
