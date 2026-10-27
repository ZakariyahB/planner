package app.planner.ai

/** The tools the model may call. Their schemas are in docs/prompts/tools.json. */
object AiToolNames {
    const val GET_DAY = "get_day"
    const val GET_TASKS = "get_tasks"
    const val GET_SLEEP = "get_sleep"
    const val GET_READINESS = "get_readiness"
    const val GET_SOCIAL_TIME = "get_social_time"
    const val PROPOSE_CHANGES = "propose_changes"
    const val APPLY_CHANGES = "apply_changes"
    const val ADD_TASK = "add_task"
    const val ADD_COMMITMENT = "add_commitment"
    const val UPDATE_TASK = "update_task"
    const val LOG_PROGRESS = "log_progress"
    const val UNDO = "undo"

    val all = listOf(
        GET_DAY, GET_TASKS, GET_SLEEP, GET_READINESS, GET_SOCIAL_TIME,
        PROPOSE_CHANGES, APPLY_CHANGES, ADD_TASK, ADD_COMMITMENT, UPDATE_TASK, LOG_PROGRESS, UNDO,
    )

    /** Tools that only read; the app shows them as quiet activity chips. */
    val readOnly = setOf(GET_DAY, GET_TASKS, GET_SLEEP, GET_READINESS, GET_SOCIAL_TIME)
}
