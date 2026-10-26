package app.planner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.data.DayRulesRepository
import app.planner.plan.PlanRepository
import app.planner.scheduler.DayRules
import app.planner.scheduler.DayRulesCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class DayRulesViewModel @Inject constructor(
    private val repository: DayRulesRepository,
    private val plan: PlanRepository,
) : ViewModel() {
    val rules: StateFlow<DayRules> = repository.rules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayRules())

    /** Why the last edit was refused, in plain words; cleared by the next edit that works. */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem

    /** Applies one edit. Rules that don't make sense together are refused with a reason and nothing changes. */
    fun edit(change: (DayRules) -> DayRules) {
        viewModelScope.launch {
            val next = try {
                change(repository.current())
            } catch (e: IllegalArgumentException) {
                _problem.value = e.message ?: "That doesn't fit with the other rules."
                return@launch
            }
            val problems = DayRulesCodec.problems(next)
            if (problems.isNotEmpty()) {
                _problem.value = problems.first()
                return@launch
            }
            _problem.value = null
            repository.save(next)
            plan.replan() // the new rules apply to the plan straight away; locked and pinned blocks stay put
        }
    }

    fun reset() {
        viewModelScope.launch {
            repository.reset()
            _problem.value = null
            plan.replan()
        }
    }
}
