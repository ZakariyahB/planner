package app.planner.ui

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.calendar.CalendarEvent
import app.planner.calendar.CalendarInfo
import app.planner.calendar.CalendarRepository
import app.planner.calendar.FixedEvent
import app.planner.calendar.FixedEvents
import app.planner.calendar.SyncResult
import app.planner.data.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SyncUi {
    data object Idle : SyncUi
    data object Syncing : SyncUi
    data class NeedsConsent(val sender: IntentSender) : SyncUi
    data class Failed(val message: String) : SyncUi
}

data class TodayCalendar(val fixed: List<FixedEvent> = emptyList(), val allDay: List<CalendarEvent> = emptyList())

@HiltViewModel
class CalendarViewModel @Inject constructor(
    private val calendar: CalendarRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {
    private val _sync = MutableStateFlow<SyncUi>(SyncUi.Idle)
    val sync: StateFlow<SyncUi> = _sync

    val lastSyncedAt: StateFlow<Instant?> = settings.lastSyncedAt
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val calendars: StateFlow<List<CalendarInfo>> = settings.calendars
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Selected calendar ids, falling back to the default rule until one has been saved. */
    val selected: StateFlow<Set<String>> = combine(settings.calendars, settings.selectedCalendarIds) { cals, chosen ->
        chosen ?: FixedEvents.defaultSelection(cals)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val today: StateFlow<TodayCalendar> = combine(calendar.cachedEvents, selected) { events, selected ->
        val date = LocalDate.now(clock)
        val dayStart = date.atStartOfDay(clock.zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(clock.zone).toInstant()
        TodayCalendar(
            fixed = FixedEvents.from(events, selected, plannerCalendarId = null)
                .filter { it.start < dayEnd && it.end > dayStart },
            allDay = FixedEvents.allDayOn(events, selected, plannerCalendarId = null, date = date),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayCalendar())

    init {
        // Re-sync silently on open, but only once the user has connected before; first consent is their tap.
        viewModelScope.launch {
            if (settings.lastSyncedAt.first() != null) refresh()
        }
    }

    fun refresh() = launchSync { calendar.sync() }

    fun setCalendarSelected(id: String, on: Boolean) = launchSync {
        val next = if (on) selected.value + id else selected.value - id
        calendar.setSelection(next)
    }

    private fun launchSync(block: suspend () -> SyncResult) = viewModelScope.launch {
        _sync.value = SyncUi.Syncing
        _sync.value = when (val r = block()) {
            SyncResult.Synced -> SyncUi.Idle
            is SyncResult.NeedsConsent -> SyncUi.NeedsConsent(r.sender)
            is SyncResult.Failed -> SyncUi.Failed(r.message)
        }
    }
}
