package app.planner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.routines.Routine
import app.planner.routines.RoutineRunner
import app.planner.routines.RoutineScheduler
import app.planner.routines.RoutineSettings
import app.planner.sleep.SleepRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RoutinePrefsViewModel @Inject constructor(
    private val routines: RoutineSettings,
    private val scheduler: RoutineScheduler,
    private val sleep: SleepRepository,
    private val runner: RoutineRunner,
) : ViewModel() {
    val enabled: StateFlow<Map<Routine, Boolean>> =
        combine(Routine.entries.map { r -> routines.observeEnabled(r).map { r to it } }) { pairs -> pairs.toMap() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Routine.entries.associateWith { true })

    /** Null until read, so the permission explainer never flashes up before we know whether it was shown. */
    val promptSeen: StateFlow<Boolean?> = routines.observeNotificationPromptSeen()
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setEnabled(routine: Routine, on: Boolean) = viewModelScope.launch {
        routines.setEnabled(routine, on)
        scheduler.rescheduleAll() // sets or cancels this routine's alarm right away
    }

    /** Sets every alarm again, e.g. after the user allows exact alarms. */
    fun reschedule() = viewModelScope.launch { scheduler.rescheduleAll() }

    /** Runs the routine now, so it can be seen without waiting for its time. */
    fun sendNow(routine: Routine) = viewModelScope.launch { runner.sendNow(routine) }

    suspend fun backgroundSleepGranted(): Boolean? = sleep.backgroundReadGranted()

    fun markPromptSeen() = viewModelScope.launch { routines.markNotificationPromptSeen() }
}
