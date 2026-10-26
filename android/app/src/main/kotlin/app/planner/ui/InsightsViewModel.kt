package app.planner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.data.BlockDao
import app.planner.data.TaskRepository
import app.planner.insights.DoneRow
import app.planner.insights.LightsOutInsights
import app.planner.insights.LightsOutRow
import app.planner.insights.PlannedVsDone
import app.planner.insights.SleepInsights
import app.planner.insights.SleepRow
import app.planner.insights.SocialInsights
import app.planner.insights.SocialPoint
import app.planner.insights.SocialRow
import app.planner.insights.WeeklyReview
import app.planner.insights.WeeklyReviews
import app.planner.insights.WorkItem
import app.planner.model.TaskStatus
import app.planner.sleep.ReadinessRepository
import app.planner.social.SocialRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class InsightsUi(
    val loaded: Boolean = false,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val sleep: List<SleepRow> = emptyList(),
    val lights: List<LightsOutRow> = emptyList(),
    val done: List<DoneRow> = emptyList(),
    /** Empty when there is no social-media data at all (usage access not given), so nothing misleading is said. */
    val social: List<SocialRow> = emptyList(),
    val review: WeeklyReview? = null,
) {
    val hasAnyData: Boolean get() = sleep.isNotEmpty() || done.any { it.plannedMin > 0 } || social.isNotEmpty()
}

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val readiness: ReadinessRepository,
    private val blocks: BlockDao,
    private val tasks: TaskRepository,
    private val social: SocialRepository,
    private val clock: Clock,
) : ViewModel() {
    private val _ui = MutableStateFlow(InsightsUi())
    val ui: StateFlow<InsightsUi> = _ui

    /** Recomputes the last seven days; call each time the tab opens. */
    fun refresh() {
        viewModelScope.launch {
            val today = LocalDate.now(clock)
            val from = today.minusDays(DAYS - 1L)
            val dates = (0 until DAYS).map { from.plusDays(it.toLong()) }

            val nights = readiness.recentNights(14).filter { it.date in from..today }
            val sleepRows = SleepInsights.rows(nights, clock.zone, DAYS)
            val lightRows = LightsOutInsights.rows(nights, clock.zone, DAYS)

            val finished = tasks.snapshot().filter { it.status == TaskStatus.DONE }.map { it.id }.toSet()
            val items = blocks.between(from.toString(), today.toString())
                .filter { it.kind == "TASK" }
                .map { WorkItem(LocalDate.parse(it.date), it.endMin - it.startMin, it.taskId in finished) }
            val doneRows = PlannedVsDone.rows(items, dates)

            val days = social.recent(DAYS)
            val socialRows = if (days.all { it.minutes == 0 }) emptyList() else SocialInsights.rows(days.map { SocialPoint(it.date, it.minutes, it.budgetMin) })

            _ui.value = InsightsUi(
                loaded = true,
                from = from,
                to = today,
                sleep = sleepRows,
                lights = lightRows,
                done = doneRows,
                social = socialRows,
                review = WeeklyReviews.build(sleepRows, lightRows, doneRows, socialRows),
            )
        }
    }

    private companion object {
        const val DAYS = 7
    }
}
