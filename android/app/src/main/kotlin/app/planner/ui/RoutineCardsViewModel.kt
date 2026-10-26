package app.planner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.routines.Routine
import app.planner.routines.RoutineSettings
import app.planner.routines.TomorrowPlan
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** What Today shows for the routines: tomorrow's plan while it waits for approval. */
@HiltViewModel
class RoutineCardsViewModel @Inject constructor(
    routines: RoutineSettings,
    private val clock: Clock,
) : ViewModel() {
    // The cards appear at fixed times of day, so look at the clock once a minute.
    private val minutes = flow {
        while (true) {
            emit(clock.instant())
            delay(60_000)
        }
    }

    /** The day whose plan waits for approval, or null. */
    val planPending: StateFlow<LocalDate?> =
        combine(routines.observeSentOn(Routine.PLAN_READY), routines.observePlanApprovedFor(), minutes) { sent, approved, now ->
            TomorrowPlan.pendingFor(sent, approved, now, clock.zone)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
