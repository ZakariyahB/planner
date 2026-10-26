package app.planner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.routines.ForecastNote
import app.planner.routines.NotificationTexts
import app.planner.routines.PlanRow
import app.planner.routines.RoutineSettings
import app.planner.routines.TomorrowPlanRepository
import app.planner.scheduler.DayRules
import app.planner.sleep.ReadinessRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class TomorrowPlanUi(
    val date: LocalDate,
    val rows: List<PlanRow> = emptyList(),
    /** Switched off on the sheet; deferred when he approves. */
    val excluded: Set<Long> = emptySet(),
    val note: String? = null,
    val forecast: String? = null,
    val loaded: Boolean = false,
)

@HiltViewModel
class TomorrowPlanViewModel @Inject constructor(
    private val plans: TomorrowPlanRepository,
    private val routines: RoutineSettings,
    private val readiness: ReadinessRepository,
    private val clock: Clock,
) : ViewModel() {
    private val _ui = MutableStateFlow(TomorrowPlanUi(planDate()))
    val ui: StateFlow<TomorrowPlanUi> = _ui

    /** Reads the plan afresh; call each time the sheet opens. */
    fun load() {
        viewModelScope.launch {
            val date = planDate()
            val rows = plans.rowsOn(date)
            val forecast = try {
                readiness.evaluate().forecast?.let {
                    NotificationTexts.forecastLine(ForecastNote(DayRules().lightsOut, it.onTime.score, it.usualLightsOut, it.usual?.score))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _ui.value = TomorrowPlanUi(date, rows, note = routines.planNote(date), forecast = forecast, loaded = true)
        }
    }

    fun toggle(taskId: Long) {
        _ui.value = _ui.value.let { s ->
            s.copy(excluded = if (taskId in s.excluded) s.excluded - taskId else s.excluded + taskId)
        }
    }

    /** Applies the switches, then calls [onDone] with how many tasks were deferred. */
    fun approve(onDone: (deferred: Int) -> Unit) {
        val s = _ui.value
        viewModelScope.launch { onDone(plans.approve(s.date, s.excluded)) }
    }

    /** What goes into the Ask box when he wants Claude's help with tomorrow. */
    fun askDraft(): String {
        val s = _ui.value
        val kept = s.rows.filter { it.taskId !in s.excluded }
        val plan = if (kept.isEmpty()) "nothing planned" else kept.joinToString("; ") { "${timeFormat.format(it.start)} ${it.title}" }
        return "About ${dateFormat.format(s.date)}'s plan ($plan): "
    }

    /** The plan he is looking at is tomorrow's; after midnight and before 06:00 it is today's, approved late. */
    private fun planDate(): LocalDate {
        val today = LocalDate.now(clock)
        return if (LocalTime.now(clock) < LocalTime.of(6, 0)) today else today.plusDays(1)
    }
}
