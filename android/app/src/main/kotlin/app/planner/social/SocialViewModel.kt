package app.planner.social

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SocialViewModel @Inject constructor(
    private val access: UsageAccess,
    private val scheduler: SocialScheduler,
    private val tracker: SocialUsageTracker,
) : ViewModel() {
    private val _accessGranted = MutableStateFlow(access.isGranted())
    val accessGranted: StateFlow<Boolean> = _accessGranted

    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note

    /**
     * Called whenever the app comes to the front, which is how a switch flipped in system settings is noticed.
     * With access the 15-minute job is scheduled (an existing schedule is kept) and today's minutes are refreshed.
     */
    fun onResume() {
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
