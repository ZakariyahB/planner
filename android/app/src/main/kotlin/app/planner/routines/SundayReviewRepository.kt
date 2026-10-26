package app.planner.routines

import app.planner.data.BlockDao
import app.planner.data.TaskRepository
import app.planner.data.toBlock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SundayReviewRepository @Inject constructor(
    private val blocks: BlockDao,
    private val tasks: TaskRepository,
) {
    /** Planned against done for the week starting [weekMonday]. */
    suspend fun report(weekMonday: LocalDate): SundayReview.WeekReport =
        SundayReview.report(
            weekMonday = weekMonday,
            blocks = blocks.between(weekMonday.toString(), weekMonday.plusDays(6).toString()).map { it.toBlock() },
            tasks = tasks.snapshot().associateBy { it.id },
        )
}
