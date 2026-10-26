package app.planner.social

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@HiltViewModel
class SocialViewModel @Inject constructor(
    private val access: UsageAccess,
) : ViewModel() {
    private val _accessGranted = MutableStateFlow(access.isGranted())
    val accessGranted: StateFlow<Boolean> = _accessGranted

    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note

    /** Called whenever the app comes to the front, which is how a switch flipped in system settings is noticed. */
    fun onResume() {
        _accessGranted.value = access.isGranted()
    }

    fun openAccessSettings() {
        _note.value = if (access.openSettings()) null else "This phone has no usage access screen. Look for it under Settings → Apps → Special access."
    }
}
