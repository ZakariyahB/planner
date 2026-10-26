package app.planner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.data.ChangeLogDao
import app.planner.data.ChangeLogEntity
import app.planner.data.decodeDiffs
import app.planner.history.ChangeHistory
import app.planner.history.HistoryDay
import app.planner.history.HistoryEntry
import app.planner.plan.PlanRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HistoryViewModel @Inject constructor(
    changeLog: ChangeLogDao,
    private val plan: PlanRepository,
    private val clock: Clock,
) : ViewModel() {
    val days: StateFlow<List<HistoryDay>> = changeLog.observeRecent(LIMIT)
        .map { rows -> ChangeHistory.group(rows.map(::entry), clock.instant(), clock.zone) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun undo(id: Long) {
        viewModelScope.launch {
            _message.value = if (plan.undo(id)) "Undone" else "That change can't be undone any more"
        }
    }

    private fun entry(row: ChangeLogEntity): HistoryEntry = HistoryEntry(
        id = row.id,
        tsMillis = row.ts,
        actor = row.actor,
        summary = row.summary,
        // An entry whose details can't be read is still listed, just without the detail line.
        diffs = try {
            decodeDiffs(row.diffJson)
        } catch (e: Exception) {
            emptyList()
        },
        undone = row.undone,
    )

    private companion object {
        const val LIMIT = 100
    }
}
