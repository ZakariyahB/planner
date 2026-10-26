package app.planner.setup

/** The things the app needs from the user before it is fully useful, in the order to suggest them. */
enum class SetupStep(val title: String, val why: String, val action: String) {
    CALENDAR("Connect Google Calendar", "Your classes and commitments to plan around", "Connect"),
    HEALTH("Allow Health Connect", "Reads your sleep from Fitbit", "Allow"),
    FAJR("Set this week's Fajr time", "A rough time is enough", "Set"),
    NOTIFICATIONS("Allow notifications", "Plan ready, lights-out, work stops", "Allow"),
    EXACT_ALARMS("Allow exact alarms", "So reminders arrive on time", "Open"),
    USAGE_ACCESS("Allow usage access", "Counts your social-media minutes", "Open"),
}

data class SetupState(
    val calendarConnected: Boolean,
    val healthConnected: Boolean,
    val fajrSet: Boolean,
    val notificationsAllowed: Boolean,
    val exactAlarmsAllowed: Boolean,
    val usageAccessGranted: Boolean,
    /** False on a phone without Health Connect, which then isn't asked for. */
    val healthAvailable: Boolean = true,
)

data class SetupItem(val step: SetupStep, val done: Boolean)

object SetupChecklist {
    fun items(state: SetupState): List<SetupItem> = buildList {
        add(SetupItem(SetupStep.CALENDAR, state.calendarConnected))
        if (state.healthAvailable) add(SetupItem(SetupStep.HEALTH, state.healthConnected))
        add(SetupItem(SetupStep.FAJR, state.fajrSet))
        add(SetupItem(SetupStep.NOTIFICATIONS, state.notificationsAllowed))
        add(SetupItem(SetupStep.EXACT_ALARMS, state.exactAlarmsAllowed))
        add(SetupItem(SetupStep.USAGE_ACCESS, state.usageAccessGranted))
    }

    fun doneCount(items: List<SetupItem>): Int = items.count { it.done }

    fun isComplete(items: List<SetupItem>): Boolean = items.all { it.done }

    fun progress(items: List<SetupItem>): Double = if (items.isEmpty()) 1.0 else doneCount(items).toDouble() / items.size

    fun next(items: List<SetupItem>): SetupStep? = items.firstOrNull { !it.done }?.step
}
