package app.planner.routines

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

/** Everything the routines need at process start. Each step is independent, so one failing never blocks the rest. */
object RoutineBootstrap {
    private const val TAG = "RoutineBootstrap"

    fun start(app: Application) {
        attempt("channels") { RoutineChannels.create(app) }
        attempt("unlock receiver") {
            ContextCompat.registerReceiver(
                app,
                UserPresentReceiver(),
                IntentFilter(Intent.ACTION_USER_PRESENT),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        attempt("periodic job") { RoutineWork.startPeriodic(app) }
        attempt("alarms") { RoutineWork.reschedule(app) }
    }

    private fun attempt(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Could not set up $what", e)
        }
    }
}
