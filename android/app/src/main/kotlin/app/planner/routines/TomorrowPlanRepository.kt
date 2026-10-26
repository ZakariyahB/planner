package app.planner.routines

import app.planner.data.BlockDao
import app.planner.data.TaskRepository
import app.planner.data.toBlock
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Reads the plan of one day as the rows the 23:30 sheet and the notifications describe. */
@Singleton
class TomorrowPlanRepository @Inject constructor(
    private val blocks: BlockDao,
    private val tasks: TaskRepository,
    private val clock: Clock,
) {
    suspend fun rowsOn(date: LocalDate): List<PlanRow> =
        TomorrowPlan.rows(
            date = date,
            blocks = blocks.between(date.toString(), date.toString()).map { it.toBlock() },
            tasks = tasks.snapshot().associateBy { it.id },
            zone = clock.zone,
        )
}
