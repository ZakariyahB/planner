package app.planner.assistant.tools

/**
 * Every tool the model may call (docs/prompts/tools-v2.json defines their schemas; PromptFilesTest keeps the two in
 * step). Four read, two propose. There is deliberately no tool that applies, approves or writes anything: a
 * proposal waits for the user on the approval card, and only that card can let CalendarWriter write.
 */
object ToolNames {
    const val GET_WEEK = "get_week"
    const val GET_TASKS = "get_tasks"
    const val GET_PRAYER_ANCHORS = "get_prayer_anchors"
    const val FIND_EVENTS = "find_events"
    const val PROPOSE_CHANGES = "propose_changes"
    const val REPLAN = "replan"

    val readOnly: Set<String> = setOf(GET_WEEK, GET_TASKS, GET_PRAYER_ANCHORS, FIND_EVENTS)
    val proposing: Set<String> = setOf(PROPOSE_CHANGES, REPLAN)
    val all: Set<String> = readOnly + proposing

    /** propose_changes `changes[].action` values. */
    object Actions {
        const val ADD = "add"
        const val SCHEDULE_TASK = "schedule_task"
        const val MOVE = "move"
        const val EDIT = "edit"
        const val REMOVE = "remove"
        val all = listOf(ADD, SCHEDULE_TASK, MOVE, EDIT, REMOVE)
    }

    /** replan `reason` values. */
    object Reasons {
        const val OVERRUN = "overrun"
        const val STOPPED_EARLY = "stopped_early"
        const val PLAN_DAYS = "plan_days"
        const val ROLLOVER = "rollover"
        const val OTHER = "other"
        val all = listOf(OVERRUN, STOPPED_EARLY, PLAN_DAYS, ROLLOVER, OTHER)
    }

    /** Recurring-event scope values; the default is this instance. */
    object Scopes {
        const val THIS_INSTANCE = "this_instance"
        const val THIS_AND_FOLLOWING = "this_and_following"
        const val WHOLE_SERIES = "whole_series"
        val all = listOf(THIS_INSTANCE, THIS_AND_FOLLOWING, WHOLE_SERIES)
    }

    /** Prayers a task may follow (Fajr is not a work-window anchor). */
    val afterPrayers = listOf("Dhuhr", "Asr", "Maghrib", "Isha")
}
