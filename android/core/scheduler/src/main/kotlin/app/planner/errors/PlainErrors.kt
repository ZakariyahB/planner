package app.planner.errors

/**
 * What to tell the user when talking to Google or Health Connect goes wrong: what happened, in plain words,
 * and what to do about it. Codes appear only where they help when asking for help, never exception text.
 */
object PlainErrors {
    const val CALENDAR_OFFLINE = "No connection to Google Calendar. Check the internet and try again."
    const val CALENDAR_UNKNOWN = "Something went wrong while syncing with Google Calendar. Try again in a moment."
    const val HEALTH_READ_FAILED =
        "Couldn't read sleep from Health Connect. Open the Health Connect app, check that Planner may read sleep, then tap Refresh."

    /** Opens every message about the Planner calendar: the plan itself is safe, only the copy in Google is behind. */
    const val PLANNER_NOT_COPIED = "Your plan is saved on this phone but not in Google Calendar yet."

    fun calendarHttp(code: Int): String = when (code) {
        401, 403 -> "Google turned down the calendar access. Reconnect Google Calendar from Settings, then try again."
        429 -> "Google says there were too many requests. Wait a few minutes, then try again."
        in 500..599 -> "Google Calendar is having trouble right now. Try again in a few minutes."
        else -> "Google Calendar sent back an error (code $code). Try again later."
    }

    /** When the plan could not be copied to the Planner calendar; the plan on the phone is unaffected. */
    fun plannerCalendar(code: Int): String = "$PLANNER_NOT_COPIED ${calendarHttp(code)}"

    /** [statusCode] is the Google Play services status code of a failed authorization, if there was one. */
    fun googleAuth(statusCode: Int?): String = when (statusCode) {
        10 -> "Google doesn't recognise this copy of the app. Check the package name and SHA-1 on the Android client in Google Cloud (see docs/SETUP.md), then try again."
        7, 15 -> "No connection to Google. Check the internet and try again."
        16, 12501 -> "Sign-in was cancelled. Tap the button to try again."
        1, 2, 3, 18 -> "Google Play services needs attention on this phone. Update it from the Play Store, then try again."
        4, 5, 6 -> "Google needs you to sign in again. Tap the button to try again."
        null -> "Google sign-in didn't work. Try again in a moment."
        else -> "Google sign-in didn't work (code $statusCode). Try again in a moment."
    }

    /** [missing] is the build setting that is blank, for example WORKER_URL. */
    fun aiNotSetUp(missing: String): String =
        "The assistant isn't set up in this build. Add $missing to android/local.properties and rebuild (see docs/SETUP.md)."
}
