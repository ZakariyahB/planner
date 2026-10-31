package app.planner.ui.shell

import android.content.Intent
import app.planner.ui.model.LaunchRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Hands a tapped notification's destination from MainActivity to the Compose shell. Stream E's notifications set
 * the extra `open` (approvals, today, week, chat, settings) and `changeSetId` for a proposal. The extras are removed
 * once read, so recreating the activity (rotation) does not open the screen again.
 */
object UiLaunch {
    private val _requests = MutableStateFlow<LaunchRequest?>(null)
    val requests: StateFlow<LaunchRequest?> = _requests

    fun handle(intent: Intent?) {
        intent ?: return
        val request = LaunchRequest.parse(intent.getStringExtra(LaunchRequest.EXTRA_OPEN), intent.getStringExtra(LaunchRequest.EXTRA_CHANGE_SET))
        intent.removeExtra(LaunchRequest.EXTRA_OPEN)
        intent.removeExtra(LaunchRequest.EXTRA_CHANGE_SET)
        if (request != null) _requests.value = request
    }

    fun open(request: LaunchRequest) {
        _requests.value = request
    }

    fun consume() {
        _requests.value = null
    }
}
