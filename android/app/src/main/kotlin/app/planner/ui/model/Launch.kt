package app.planner.ui.model

/**
 * Where a notification wants the app to open. Stream E's notifications put a string extra `open` on the intent
 * (approvals, today, week, chat, settings) and, for approvals, `changeSetId` naming the proposal.
 */
sealed interface LaunchRequest {
    data class Approvals(val changeSetId: String?) : LaunchRequest
    data object Today : LaunchRequest
    data object Week : LaunchRequest
    data object Chat : LaunchRequest
    data object Settings : LaunchRequest

    companion object {
        const val EXTRA_OPEN = "open"
        const val EXTRA_CHANGE_SET = "changeSetId"

        fun parse(open: String?, changeSetId: String?): LaunchRequest? = when (open?.trim()?.lowercase()) {
            "approvals", "approval" -> Approvals(changeSetId?.takeIf { it.isNotBlank() })
            "today" -> Today
            "week" -> Week
            "chat", "ask" -> Chat
            "settings" -> Settings
            else -> null
        }
    }
}
