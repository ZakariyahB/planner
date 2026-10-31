package app.planner.engine.checkin

import app.planner.engine.model.Prayer
import app.planner.engine.ports.ProposalOutcome
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Where tapping a notification takes the user. :app passes [extra] as the `open` extra on the MainActivity intent
 * (see docs/redesign/notes/E.md); the UI decides what each one opens.
 */
enum class OpenTarget(val extra: String) {
    APPROVALS("approvals"),
    TODAY("today"),
    WEEK("week"),
    CHAT("chat"),
    SETTINGS("settings"),
    ;

    companion object {
        const val EXTRA = "open"

        fun parse(value: String?): OpenTarget? = entries.firstOrNull { it.extra == value }
    }
}

data class NoticeText(val title: String, val body: String, val open: OpenTarget = OpenTarget.TODAY)

object CheckInTexts {
    private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun hm(t: LocalTime): String = t.format(HM)

    fun prayerName(p: Prayer): String = p.name.lowercase().replaceFirstChar { it.uppercase() }

    fun forCheckIn(c: CheckIn, slotMin: Int = 15): NoticeText = when (c.kind) {
        CheckInKind.START -> NoticeText(
            title = "${c.block.title}, ${hm(c.block.plannedStart.toLocalTime())}",
            body = buildString {
                append("Until ${hm(c.block.plannedEnd.toLocalTime())}.")
                bufferNote(c.block)?.let { append(" ").append(it) }
            },
        )
        CheckInKind.END -> NoticeText(
            title = "${c.block.title} done?",
            body = "Planned ${hm(c.block.plannedStart.toLocalTime())} to ${hm(c.block.plannedEnd.toLocalTime())}.",
        )
        CheckInKind.NEXT_UP -> {
            val next = requireNotNull(c.next)
            NoticeText(
                title = "Next up: ${next.title}, ${hm(next.plannedStart.toLocalTime())}",
                body = buildString {
                    append("${c.block.title} done? ${next.title} runs until ${hm(next.plannedEnd.toLocalTime())}.")
                    bufferNote(next)?.let { append(" ").append(it) }
                },
            )
        }
        CheckInKind.PRAYER_BREAK -> NoticeText(
            title = "${prayerName(requireNotNull(c.prayer))} break, $slotMin min",
            body = "${c.block.title} carries on until ${hm(c.block.plannedEnd.toLocalTime())}.",
        )
    }

    /** "Includes 15 min for Dhuhr", as in the event description. */
    fun bufferNote(b: CheckInBlock): String? {
        if (b.prayerBufferMin <= 0) return null
        val names = b.coveredPrayers.map(::prayerName)
        return if (names.isEmpty()) "Includes ${b.prayerBufferMin} min for prayer."
        else "Includes ${b.prayerBufferMin} min for ${names.joinToString(" and ")}."
    }

    fun actionLabel(a: CheckInAction, prefs: app.planner.engine.model.NotificationPrefs): String = when (a) {
        CheckInAction.START -> "Start"
        CheckInAction.SNOOZE -> "Snooze ${prefs.snoozeMin}"
        CheckInAction.SKIP -> "Skip"
        CheckInAction.DONE -> "Done"
        CheckInAction.EXTEND -> "Need ${prefs.extendMin} more"
        CheckInAction.STOP -> "Stop here"
        CheckInAction.DONE_START_NEXT -> "Done, start next"
    }

    fun focusQuestion(block: CheckInBlock): NoticeText =
        NoticeText(title = "How was your focus?", body = "${block.title}: tap 1 (scattered) to 5 (deep focus).")

    /**
     * After "Need 30 more" or "Stop here": the replan is a proposal waiting for approval, never a change.
     * Only a real proposal opens the approval screen.
     */
    fun replanNotice(outcome: ProposalOutcome): NoticeText = when (outcome) {
        is ProposalOutcome.Proposed -> {
            val n = outcome.changeSet.changes.size
            NoticeText(
                title = "Review change",
                body = "${outcome.changeSet.summary} (${n} change${if (n == 1) "" else "s"}). Nothing moves until you approve.",
                open = OpenTarget.APPROVALS,
            )
        }
        is ProposalOutcome.NeedsChoice -> NoticeText("Replan needs a choice", outcome.question)
        is ProposalOutcome.Rejected -> NoticeText("Could not replan", outcome.violations.joinToString(" ").ifBlank { "The plan breaks a rule." })
        is ProposalOutcome.NothingToDo -> NoticeText("Nothing to move", outcome.reason)
    }
}
