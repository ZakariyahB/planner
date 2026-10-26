package app.planner.model

enum class ProgressOutcome {
    DONE, PARTIAL, DROPPED;

    companion object {
        fun parse(text: String?): ProgressOutcome? = entries.firstOrNull { it.name.equals(text, ignoreCase = true) }
    }
}

object TaskProgress {
    /**
     * The task after some work on it. Partial work leaves the rest to do (never under five minutes, the
     * smallest a task can be); finishing marks it done; giving up drops it.
     */
    fun apply(task: Task, minutes: Int, outcome: ProgressOutcome): Task {
        require(minutes >= 0) { "minutes must not be negative" }
        return when (outcome) {
            ProgressOutcome.DONE -> task.copy(status = TaskStatus.DONE)
            ProgressOutcome.DROPPED -> task.copy(status = TaskStatus.DROPPED)
            ProgressOutcome.PARTIAL -> task.copy(status = TaskStatus.PARTIAL, estMin = (task.estMin - minutes).coerceAtLeast(5))
        }
    }
}
