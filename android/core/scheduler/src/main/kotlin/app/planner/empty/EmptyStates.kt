package app.planner.empty

data class EmptyCard(val title: String, val body: String)

/** What the second button of an empty task list does: fill the week from the template, or go and make one. */
enum class TemplateAction(val label: String) {
    GENERATE("Use weekly template"),
    SET_UP("Set up weekly template"),
}

data class EmptyTasks(val card: EmptyCard, val template: TemplateAction)

/** The words for places that have nothing to show yet, each saying what to do next. */
object EmptyStates {
    /** For the Today list ([today] true) and the Tasks tab. */
    fun tasks(templateItems: Int, today: Boolean): EmptyTasks {
        val hasTemplate = templateItems > 0
        val title = if (today) "Nothing due today" else "No tasks yet"
        val body = if (hasTemplate) {
            "Add a task, or fill the week from your weekly template."
        } else {
            "Add a task, or set up a weekly template for the things you repeat every week."
        }
        return EmptyTasks(EmptyCard(title, body), if (hasTemplate) TemplateAction.GENERATE else TemplateAction.SET_UP)
    }

    /** For a stretch of days in the Calendar tab with no planned blocks. */
    fun calendar(openTasks: Int, calendarConnected: Boolean): EmptyCard {
        val waiting = when (openTasks) {
            0 -> "Add a task or use your weekly template, then Replan fits it around your commitments."
            1 -> "1 task is waiting. Replan places it around your commitments."
            else -> "$openTasks tasks are waiting. Replan places them around your commitments."
        }
        val connect = if (calendarConnected) "" else " Connect Google Calendar in Settings so your classes are planned around too."
        return EmptyCard("Nothing planned for these days", waiting + connect)
    }

    /** Shown after "Use weekly template". */
    fun generated(created: Int, templateItems: Int): String = when {
        created > 0 -> "Added $created task${if (created == 1) "" else "s"}"
        templateItems == 0 -> "Your weekly template is empty. Add the things you repeat every week in Settings, under Weekly template."
        else -> "Nothing new: those tasks already exist"
    }
}
