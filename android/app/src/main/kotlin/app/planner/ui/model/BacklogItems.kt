package app.planner.ui.model

import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.Prayer
import app.planner.engine.model.Task
import app.planner.engine.model.TaskKind
import app.planner.engine.model.TaskStatus
import app.planner.engine.model.UnparsedTemplate
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

// Backlog: tasks with their constraints as chips, the "Needs a look" list, and the add/edit form's validation.

data class BacklogTaskUi(
    val task: Task,
    val title: String,
    /** "3h". */
    val duration: String,
    val category: String,
    /** "Tue 10 Nov", "from 15:00", "after Asr", "due Fri 13 Nov", "one block", "priority 2". */
    val chips: List<String>,
    val overdue: Boolean,
    val description: String,
)

data class NeedsALookUi(
    val item: UnparsedTemplate,
    /** The title as written, in quotes. */
    val quoted: String,
    /** "Academic · Thu 12 Nov · no duration". */
    val detail: String,
)

data class BacklogUi(
    val toPlan: List<BacklogTaskUi>,
    val planned: List<BacklogTaskUi>,
    val needsALook: List<NeedsALookUi>,
    /** "7 to plan · 2 need a look". */
    val summary: String,
)

/** The add/edit form. Text fields stay text until saved so half-typed values never crash anything. */
data class TaskDraft(
    val id: String? = null,
    val title: String = "",
    val category: CalendarCategory = CalendarCategory.PERSONAL,
    val durationMin: Int = 60,
    val day: LocalDate? = null,
    val earliestStart: LocalTime? = null,
    val afterPrayer: Prayer? = null,
    val deadline: LocalDate? = null,
    val splittable: Boolean = true,
    val priority: Int = 0,
    val kind: TaskKind = TaskKind.DEEP,
    val notes: String = "",
    /** Set when fixing a Needs a look item: it is removed from that list on save. */
    val fromTemplate: UnparsedTemplate? = null,
) {
    val problems: List<String>
        get() = buildList {
            if (title.isBlank()) add("Give it a title")
            if (durationMin < 15) add("At least 15 minutes")
            if (durationMin > 12 * 60) add("At most 12 hours")
        }

    val canSave: Boolean get() = problems.isEmpty()
}

object BacklogItems {
    fun chips(t: Task, today: LocalDate): List<String> = buildList {
        t.assignedDay?.let { add(Fmt.shortDate(it)) }
        t.earliestStart?.let { add("from ${Fmt.time(it)}") }
        t.afterPrayer?.let { add("after ${Fmt.prayer(it)}") }
        t.deadline?.let { add(if (it.isBefore(today)) "was due ${Fmt.shortDate(it)}" else "due ${Fmt.shortDate(it)}") }
        if (!t.splittable) add("one block")
        if (t.priority > 0) add("priority ${t.priority}")
        if (t.kind == TaskKind.PASSIVE) add("passive")
        if (t.kind == TaskKind.OVERFLOW) add("catch-up")
    }

    fun item(t: Task, today: LocalDate): BacklogTaskUi {
        val chips = chips(t, today)
        return BacklogTaskUi(
            task = t,
            title = t.title,
            duration = Fmt.duration(t.durationMin),
            category = t.category.calendarName,
            chips = chips,
            overdue = t.deadline?.isBefore(today) == true && t.status != TaskStatus.DONE,
            description = listOf(t.title, Fmt.duration(t.durationMin), t.category.calendarName).plus(chips).joinToString(", "),
        )
    }

    /** Why a template title did not parse, in a few words. */
    fun reason(title: String): String {
        val t = title.trim()
        return when {
            !Regex("""^\d+\.""").containsMatchIn(t) -> "not numbered"
            !t.contains('(') -> "no duration"
            !Regex("""\(\s*\d""").containsMatchIn(t) -> "duration not understood"
            else -> "title not understood"
        }
    }

    fun needsALook(u: UnparsedTemplate): NeedsALookUi =
        NeedsALookUi(u, "“${u.title}”", "${u.calendarName.trim()} · ${Fmt.shortDate(u.date)} · ${reason(u.title)}")

    fun build(tasks: List<Task>, needs: List<UnparsedTemplate>, today: LocalDate): BacklogUi {
        val order = compareBy<Task>({ it.assignedDay ?: LocalDate.MAX }, { -it.priority }, { it.title.lowercase() })
        val toPlan = tasks.filter { it.status == TaskStatus.BACKLOG }.sortedWith(order).map { item(it, today) }
        val planned = tasks.filter { it.status == TaskStatus.SCHEDULED }.sortedWith(order).map { item(it, today) }
        val summary = buildList {
            add("${toPlan.size} to plan")
            if (needs.isNotEmpty()) add("${needs.size} ${if (needs.size == 1) "needs" else "need"} a look")
        }.joinToString(" · ")
        return BacklogUi(toPlan, planned, needs.sortedBy { it.date }.map(::needsALook), summary)
    }

    fun draftOf(t: Task): TaskDraft = TaskDraft(
        id = t.id, title = t.title, category = t.category, durationMin = t.durationMin, day = t.assignedDay,
        earliestStart = t.earliestStart, afterPrayer = t.afterPrayer, deadline = t.deadline, splittable = t.splittable,
        priority = t.priority, kind = t.kind, notes = t.notes.orEmpty(),
    )

    /** A draft from an all-day title that did not parse: "5. ADA revision sometime" -> "ADA revision sometime". */
    fun draftOf(u: UnparsedTemplate): TaskDraft = TaskDraft(
        title = u.title.trim().replace(Regex("""^\d+\.\s*"""), "").replace(Regex("""\s*\([^)]*\)\s*$"""), "").trim(),
        category = CalendarCategory.forCalendarName(u.calendarName) ?: CalendarCategory.PERSONAL,
        day = u.date,
        fromTemplate = u,
    )

    /** Applies the form to [existing] (or makes a new backlog task). Keeps links to calendar events. */
    fun toTask(d: TaskDraft, existing: Task?, newId: () -> String = { UUID.randomUUID().toString() }): Task {
        val base = existing ?: Task(id = d.id ?: newId(), title = d.title.trim(), category = d.category, durationMin = d.durationMin, sourceEvent = d.fromTemplate?.ref)
        return base.copy(
            title = d.title.trim(),
            category = d.category,
            durationMin = d.durationMin,
            assignedDay = d.day,
            earliestStart = d.earliestStart,
            afterPrayer = d.afterPrayer,
            deadline = d.deadline,
            splittable = d.splittable,
            priority = d.priority,
            kind = d.kind,
            notes = d.notes.trim().ifBlank { null },
        )
    }
}
