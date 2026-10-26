package app.planner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.data.SettingsRepository
import app.planner.model.Weeks
import app.planner.plan.PlanRepository
import app.planner.sleep.HealthStatus
import app.planner.sleep.ReadinessRepository
import app.planner.sleep.ReadinessView
import app.planner.sleep.SleepRepository
import app.planner.sleep.SleepSyncResult
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ReadinessViewModel @Inject constructor(
    private val sleep: SleepRepository,
    private val readiness: ReadinessRepository,
    private val settings: SettingsRepository,
    private val plan: PlanRepository,
    private val clock: Clock,
) : ViewModel() {
    private val _view = MutableStateFlow<ReadinessView?>(null)
    val view: StateFlow<ReadinessView?> = _view

    private val _health = MutableStateFlow<HealthStatus?>(null)
    val health: StateFlow<HealthStatus?> = _health

    private val _syncNote = MutableStateFlow<String?>(null)
    val syncNote: StateFlow<String?> = _syncNote

    /** This week's Fajr, typed in once a week; null until set. */
    val fajrThisWeek: StateFlow<LocalTime?> = settings.fajrWeeks
        .map { it[Weeks.mondayOf(LocalDate.now(clock))] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { sleep.sessions.collect { refresh() } }
        syncSleep()
    }

    /** Reads Health Connect (if allowed), then recomputes readiness. */
    fun syncSleep() = viewModelScope.launch {
        _health.value = sleep.healthStatus()
        _syncNote.value = when (val r = sleep.syncFromHealthConnect()) {
            is SleepSyncResult.Synced -> if (r.sessions == 0) "Health Connect has no sleep yet" else null
            is SleepSyncResult.NotReady -> null
            is SleepSyncResult.Failed -> r.message
        }
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val v = readiness.evaluate()
        _view.value = v
        if (v.newlyFinal) plan.replan() // the day's load just became known
    }

    fun saveFajr(time: LocalTime) = viewModelScope.launch {
        settings.saveFajr(Weeks.mondayOf(LocalDate.now(clock)), time)
    }

    /** Bed time before noon counts as after midnight today, otherwise last evening. */
    fun addManualSleep(bed: LocalTime, wake: LocalTime) = viewModelScope.launch {
        val today = LocalDate.now(clock)
        val bedDate = if (bed >= LocalTime.NOON) today.minusDays(1) else today
        val start: Instant = bedDate.atTime(bed).atZone(clock.zone).toInstant()
        val end: Instant = today.atTime(wake).atZone(clock.zone).toInstant()
        if (end > start) {
            sleep.addManual(start, end)
        } else {
            _syncNote.value = "Wake time must be after bed time"
        }
    }
}
