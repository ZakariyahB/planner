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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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
    private val installedApps: InstalledApps,
    private val notifier: SocialNotifier,
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

    val selection: StateFlow<SocialAppSelection> = settings.selection.stateIn(viewModelScope, SharingStarted.Eagerly, SocialAppSelection())
    val budget: StateFlow<SocialBudget> = repository.budget.stateIn(viewModelScope, SharingStarted.Eagerly, SocialBudget())
    val nudgesOn: StateFlow<Boolean> = settings.nudgesOn.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val summaryOn: StateFlow<Boolean> = settings.summaryOn.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    private val _notificationsAllowed = MutableStateFlow(notifier.canNotify())
    val notificationsAllowed: StateFlow<Boolean> = _notificationsAllowed

    private val installed = MutableStateFlow<List<InstalledApp>>(emptyList())

    /** Launcher apps that can still be added: not a default app and not already added. */
    val pickable: StateFlow<List<InstalledApp>> = combine(installed, selection) { apps, sel ->
        apps.filter { it.pkg !in sel.extraPackages && DefaultSocialApps.byPackage(it.pkg) == null }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The apps the user added, with their names where the launcher list knows them. */
    val extraApps: StateFlow<List<InstalledApp>> = combine(installed, selection) { apps, sel ->
        val labels = apps.associate { it.pkg to it.label }
        sel.extraPackages.sorted().map { InstalledApp(it, labels[it] ?: it) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Called whenever the app comes to the front, which is how a switch flipped in system settings is noticed.
     * With access the 15-minute job is scheduled (an existing schedule is kept) and today's minutes are refreshed.
     */
    fun onResume() {
        day.value = LocalDate.now(clock)
        _notificationsAllowed.value = notifier.canNotify()
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

    fun loadInstalledApps() {
        viewModelScope.launch { installed.value = installedApps.launchers() }
    }

    fun setDefaultApp(id: String, on: Boolean) = changeApps { settings.setDefaultEnabled(id, on) }

    fun addApp(pkg: String) = changeApps { settings.addApp(pkg) }

    fun removeApp(pkg: String) = changeApps { settings.removeApp(pkg) }

    fun setWeekdayBudget(minutes: Int) {
        viewModelScope.launch { settings.saveBudget(repository.budget.first().copy(weekdayMin = minutes)) }
    }

    fun setWeekendBudget(minutes: Int) {
        viewModelScope.launch { settings.saveBudget(repository.budget.first().copy(weekendMin = minutes)) }
    }

    fun setNudgesOn(on: Boolean) {
        viewModelScope.launch { settings.setNudgesOn(on) }
    }

    fun setSummaryOn(on: Boolean) {
        viewModelScope.launch { settings.setSummaryOn(on) }
    }

    // A different set of apps changes today's minutes, so the meter is brought up to date straight away.
    private fun changeApps(change: suspend () -> Unit) = viewModelScope.launch {
        change()
        tracker.refresh()
    }

    private fun refresh() = viewModelScope.launch { tracker.refresh() }
}
