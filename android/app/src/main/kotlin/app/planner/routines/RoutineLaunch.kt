package app.planner.routines

import android.content.Context
import android.content.Intent
import app.planner.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where a notification (or a card on Today) wants the UI to go. [TODAY] just opens the app. */
enum class LaunchTarget { TODAY, TOMORROW_PLAN, SUNDAY_REVIEW }

/**
 * Hands a tapped notification's destination from [MainActivity] to the Compose UI. The request stays set until
 * the screen it opened is dismissed, so a rotation does not lose it.
 */
object RoutineLaunch {
    private const val EXTRA_TARGET = "app.planner.routines.TARGET"

    private val _requests = MutableStateFlow<LaunchTarget?>(null)
    val requests: StateFlow<LaunchTarget?> = _requests

    /** An intent that brings the running app to the front (or starts it) and asks for [target]. */
    fun intent(context: Context, target: LaunchTarget): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_TARGET, target.name)

    /** Call from onCreate and onNewIntent. The extra is removed so recreating the activity does not reopen it. */
    fun handle(intent: Intent?) {
        val name = intent?.getStringExtra(EXTRA_TARGET) ?: return
        intent.removeExtra(EXTRA_TARGET)
        LaunchTarget.entries.firstOrNull { it.name == name }?.let { _requests.value = it }
    }

    fun open(target: LaunchTarget) {
        _requests.value = target
    }

    fun consume() {
        _requests.value = null
    }
}
