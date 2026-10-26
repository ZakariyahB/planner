package app.planner.model

/** The words and checks behind the "log progress" sheet, so what it promises is what saving does. */
object ProgressPreview {
    val QUICK_MINUTES = listOf(15, 30, 45, 60)

    private const val REPLANNED = "It will be planned again at the next free time."

    /** One line saying what saving will do, for example "50 min left. It will be planned again at the next free time." */
    fun line(task: Task, minutes: Int, outcome: ProgressOutcome): String = when (outcome) {
        ProgressOutcome.PARTIAL -> {
            val left = TaskProgress.apply(task, minutes, outcome).estMin
            val floor = task.estMin - minutes < left
            "${duration(left)} left${if (floor) ", the least a task can take" else ""}. $REPLANNED"
        }
        ProgressOutcome.DONE -> "Done${logged(minutes)}. It won't be planned again."
        ProgressOutcome.DROPPED -> "Dropped${logged(minutes)}. It leaves the plan."
    }

    /** Why this combination can't be saved, in plain words, or null if it can. */
    fun problem(outcome: ProgressOutcome, minutes: Int): String? = when {
        minutes > Task.MAX_EST_MIN -> "That is more than 12 hours. Check the number of minutes."
        outcome == ProgressOutcome.PARTIAL && minutes <= 0 -> "Say how many minutes you worked, so the rest can be planned."
        else -> null
    }

    /** The minutes typed into the "Other" box: a whole number of zero or more, or null. */
    fun parseMinutes(text: String): Int? = text.trim().takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toIntOrNull()

    fun duration(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    private fun logged(minutes: Int): String = if (minutes > 0) ", with ${duration(minutes)} logged" else ""
}
