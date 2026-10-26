package app.planner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.data.SettingsRepository
import app.planner.data.TaskRepository
import app.planner.model.Task
import app.planner.model.TaskSource
import app.planner.model.TaskStatus
import app.planner.model.WeeklyTemplate
import app.planner.model.Weeks
import app.planner.plan.PlanRepository
import app.planner.routines.RoutineSettings
import app.planner.routines.SundayReview
import app.planner.routines.SundayReviewRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewUi(
    /** Monday of the week being planned. */
    val planWeek: LocalDate,
    val loaded: Boolean = false,
    /** The week that just ended, planned against done. */
    val report: SundayReview.WeekReport? = null,
    /** Deadlines already on the list for the next two weeks. */
    val knownDeadlines: List<Task> = emptyList(),
    /** Deadlines added during this review. */
    val addedDeadlines: List<Task> = emptyList(),
    val fajr: LocalTime? = null,
    val template: WeeklyTemplate = WeeklyTemplate(),
    val generating: Boolean = false,
    /** Tasks created from the template; null until he generates. */
    val generated: Int? = null,
    /** Why the last Generate did not finish, in plain words. */
    val problem: String? = null,
)

/** The Sunday review: planned against done, deadlines, Fajr, the template, then next week's tasks and plan. */
@HiltViewModel
class SundayReviewViewModel @Inject constructor(
    private val reviews: SundayReviewRepository,
    private val routines: RoutineSettings,
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
    private val plan: PlanRepository,
    private val clock: Clock,
) : ViewModel() {
    private val _ui = MutableStateFlow(ReviewUi(planWeek()))
    val ui: StateFlow<ReviewUi> = _ui

    /** Opening the review is what takes its card off Today. */
    fun start() {
        viewModelScope.launch {
            val week = planWeek()
            routines.markReviewOpened(week)
            _ui.value = ReviewUi(
                planWeek = week,
                loaded = true,
                report = reviews.report(week.minusWeeks(1)),
                knownDeadlines = knownDeadlines(week),
                fajr = settings.fajrWeeks.first()[week],
                template = settings.template.first(),
            )
        }
    }

    fun addDeadline(title: String, estMin: Int, due: LocalDate) {
        viewModelScope.launch {
            val task = Task(
                title = title.trim(),
                estMin = estMin,
                dueAt = due.atTime(LocalTime.of(23, 59)).atZone(clock.zone).toInstant(),
                source = TaskSource.SUNDAY,
            )
            tasks.save(task)
            _ui.update { it.copy(addedDeadlines = it.addedDeadlines + task) }
        }
    }

    fun saveFajr(time: LocalTime) {
        viewModelScope.launch {
            settings.saveFajr(_ui.value.planWeek, time)
            _ui.update { it.copy(fajr = time) }
        }
    }

    /** Next week's template tasks, then a replan so the week has times. Safe to repeat: nothing is duplicated. */
    fun generate() {
        if (_ui.value.generating) return
        viewModelScope.launch {
            val week = _ui.value.planWeek
            _ui.update { it.copy(generating = true, problem = null) }
            try {
                val weeksAhead = ChronoUnit.WEEKS.between(Weeks.mondayOf(LocalDate.now(clock)), week)
                val created = tasks.generateFromTemplate(settings.template.first(), weeksAhead)
                routines.markWeekGenerated(week)
                plan.replan()
                _ui.update { it.copy(generated = created) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(problem = "Couldn't plan next week just now. Nothing was lost: tap Generate and plan to try again.") }
            } finally {
                _ui.update { it.copy(generating = false) }
            }
        }
    }

    /** The coming Monday after the Sunday 19:00 review; later in that week it is that week's own Monday. */
    private fun planWeek(): LocalDate =
        SundayReview.pendingWeek(clock.instant(), clock.zone, opened = emptySet())
            ?: Weeks.mondayOf(LocalDate.now(clock)).plusWeeks(1)

    private suspend fun knownDeadlines(week: LocalDate): List<Task> {
        val end = week.plusDays(13)
        return tasks.snapshot()
            .filter { it.status == TaskStatus.OPEN || it.status == TaskStatus.PARTIAL }
            .filter { t -> t.dueAt?.atZone(clock.zone)?.toLocalDate()?.let { it >= week && it <= end } == true }
            .sortedBy { it.dueAt }
    }
}
