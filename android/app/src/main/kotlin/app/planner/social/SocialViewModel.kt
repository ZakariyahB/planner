package app.planner.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Today's social time against its budget, for the meter on Today. */
data class SocialMeter(val usedMin: Int, val budgetMin: Int, val noApps: Boolean) {
    val over: Boolean get() = usedMin > budgetMin
    val fraction: Float get() = (usedMin.toFloat() / budgetMin).coerceIn(0f, 1f)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SocialViewModel @Inject constructor(
    private val access: UsageAccess,
    private val scheduler: SocialScheduler,
    private val tracker: SocialUsageTracker,
    private val repository: SocialRepository,
    private val settings: SocialSettings,
    private val clock: Clock,
) : ViewModel() {
    private val _accessGranted = MutableStateFlow(access.isGranted())
    val accessGranted: StateFlow<Boolean> = _accessGranted

    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note

    // The repository's "today" is fixed when it is asked, so a new day needs a new ask.
    private val day = MutableStateFlow(LocalDate.now(clock))

    val meter: StateFlow<SocialMeter?> = day
        .flatMapLatest { repository.lastDays(1) }
        .combine(settings.selection) { days, selection -> days.last().let { SocialMeter(it.minutes, it.budgetMin, selection.packages().isEmpty()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Called whenever the app comes to the front, which is how a switch flipped in system settings is noticed.
     * With access the 15-minute job is scheduled (an existing schedule is kept) and today's minutes are refreshed.
     */
    fun onResume() {
        day.value = LocalDate.now(clock)
        val granted = access.isGranted()
        _accessGranted.value = granted
        if (granted) {
            scheduler.ensureScheduled()
            refresh()
        } else {
            scheduler.cancel()
        }
    }

    fun openAccessSettings() {
        _note.value = if (access.openSettings()) null else "This phone has no usage access screen. Look for it under Settings → Apps → Special access."
    }

    private fun refresh() = viewModelScope.launch { tracker.refresh() }
}
