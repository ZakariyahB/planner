package app.planner.ui.model

import app.planner.engine.changeset.AppliedRecord
import app.planner.engine.changeset.Change
import app.planner.engine.changeset.ChangeFlag
import app.planner.engine.changeset.ChangeSet
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.changeset.ChangeStatus
import app.planner.engine.changeset.ChangeType
import app.planner.engine.changeset.EventTime
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.Settings
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

// Approval cards: a ChangeSet shown as a diff (title, calendar, date, time before and after), with the exact
// target, flags and recurring scope. Building these writes nothing; only ApprovalGate + CalendarWriter do.

/** A time span drawn as a bar across the day window, 0..1. */
data class SpanUi(val label: String, val startFraction: Float, val endFraction: Float, val allDay: Boolean)

data class FlagUi(val label: String, val warning: Boolean)

data class ChangeCardUi(
    val id: String,
    val type: ChangeType,
    /** "EDIT · ACADEMIC". */
    val kindLabel: String,
    val title: String,
    /** Set when the title changes: the old one. */
    val previousTitle: String?,
    /** "Wed 4 Nov", or "Wed 4 Nov → Thu 5 Nov" when the day changes. */
    val dateLabel: String,
    val before: SpanUi?,
    val after: SpanUi?,
    /** "Personal → Academic" when the calendar changes. */
    val calendarMove: String?,
    val flags: List<FlagUi>,
    val recurring: Boolean,
    val scope: RecurrenceScope,
    /** "Includes 15 min for Isha". */
    val note: String?,
    /** For MOVE / EDIT / REMOVE: the exact event this touches. */
    val targetLine: String?,
    val status: ChangeStatus,
    val problem: String?,
    /** Spoken summary for screen readers. */
    val description: String,
)

data class ChangeSetUi(
    val id: String,
    /** "From your check-in · 12:45". */
    val sourceLine: String,
    val title: String,
    val notes: List<String>,
    val cards: List<ChangeCardUi>,
)

data class ReportLine(val changeId: String, val title: String, val status: ChangeStatus, val text: String)

data class ApplyReport(
    val changeSetId: String,
    /** "All 2 changes are in Google Calendar" / "Applied 2 of 3". */
    val headline: String,
    val explanation: String?,
    val lines: List<ReportLine>,
    val appliedCount: Int,
    /** Snackbar text, e.g. "2 changes written to Google". */
    val snackbar: String,
    val canUndo: Boolean,
)

object ApprovalCards {
    fun scopeLabel(scope: RecurrenceScope): String = when (scope) {
        RecurrenceScope.THIS_INSTANCE -> "this event only"
        RecurrenceScope.THIS_AND_FOLLOWING -> "this and following"
        RecurrenceScope.WHOLE_SERIES -> "whole series"
    }

    fun scopeOption(scope: RecurrenceScope): String = when (scope) {
        RecurrenceScope.THIS_INSTANCE -> "This one only"
        RecurrenceScope.THIS_AND_FOLLOWING -> "This and following"
        RecurrenceScope.WHOLE_SERIES -> "Whole series"
    }

    fun flag(f: ChangeFlag): FlagUi = when (f) {
        ChangeFlag.TOUCHES_FIXED_EVENT -> FlagUi("Fixed event", warning = false)
        ChangeFlag.EXTERNAL_ORGANISER -> FlagUi("Organised by someone else", warning = true)
        ChangeFlag.USES_REST_ZONE -> FlagUi("Uses rest zone", warning = false)
        ChangeFlag.EXTENDED_DAY -> FlagUi("Extended day", warning = true)
        ChangeFlag.OVER_SOFT_LIMIT -> FlagUi("Over the soft limit", warning = true)
        ChangeFlag.INTERPOLATED_PRAYER -> FlagUi("Weekend prayer time estimated", warning = false)
        ChangeFlag.RECURRING -> FlagUi("Repeating event", warning = false)
        ChangeFlag.MISSES_DEADLINE -> FlagUi("Misses its deadline", warning = true)
        ChangeFlag.CONVERTS_ALL_DAY -> FlagUi("Turns the all-day item into a timed block", warning = false)
    }

    fun sourceLabel(source: ChangeSource): String = when (source) {
        ChangeSource.USER -> "From you"
        ChangeSource.ASSISTANT -> "From you, via Ask"
        ChangeSource.REPLAN -> "Replan"
        ChangeSource.ROLLOVER -> "Next week from your template"
        ChangeSource.NOTIFICATION -> "From your check-in"
        ChangeSource.IMPORT -> "From your template"
    }

    private fun EventTime.date(): LocalDate = when (this) {
        is EventTime.Timed -> start.toLocalDate()
        is EventTime.AllDay -> start
    }

    private fun EventTime.label(): String = when (this) {
        is EventTime.Timed -> Fmt.range(start, end)
        is EventTime.AllDay -> "All day"
    }

    /** Bars share one window: the day's wake time to the hard cap, stretched to fit both spans. */
    fun spans(before: EventTime?, after: EventTime?, settings: Settings): Pair<SpanUi?, SpanUi?> {
        val day = (before ?: after)?.date() ?: return null to null
        // Spans on different days share a time-of-day window.
        val timed = listOfNotNull(before as? EventTime.Timed, after as? EventTime.Timed)
        val starts = timed.map { it.start.toLocalTime() } + settings.wakeTime
        val ends = timed.map { it.end.toLocalTime() } + settings.hardCap
        val from: LocalDateTime = day.atTime(starts.min())
        val to: LocalDateTime = day.atTime(ends.max())
        val total = Fmt.minutesBetween(from, to).coerceAtLeast(1).toFloat()
        fun span(t: EventTime?): SpanUi? = when (t) {
            null -> null
            is EventTime.AllDay -> SpanUi("All day", 0f, 1f, allDay = true)
            is EventTime.Timed -> {
                val s = Fmt.minutesBetween(from, day.atTime(t.start.toLocalTime())) / total
                val e = Fmt.minutesBetween(from, day.atTime(t.end.toLocalTime())) / total
                SpanUi(t.label(), s.coerceIn(0f, 1f), e.coerceIn(0f, 1f).coerceAtLeast(s), allDay = false)
            }
        }
        return span(before) to span(after)
    }

    /**
     * Calendar names: from the target when there is one, else from [calendarNames] (id to name, from synced
     * events), else the category whose name the id contains, else "Calendar".
     */
    fun calendarName(calendarId: String, calendarNames: Map<String, String>): String =
        calendarNames[calendarId]?.trim()
            ?: CalendarCategory.entries.firstOrNull { calendarId.contains(it.name.lowercase()) || calendarId.contains(it.calendarName, ignoreCase = true) }?.calendarName
            ?: "Calendar"

    fun card(change: Change, settings: Settings, calendarNames: Map<String, String> = emptyMap()): ChangeCardUi {
        val target = change.target
        val after = change.after
        val beforeCal = target?.calendarName?.trim()
        val afterCal = after?.let { a ->
            if (target != null && a.calendarId == target.ref.calendarId) beforeCal else calendarName(a.calendarId, calendarNames)
        }
        val cal = (afterCal ?: beforeCal ?: "Calendar")
        val title = after?.title ?: target?.title ?: "Event"
        val previous = target?.title?.takeIf { after != null && it != after.title }
        val beforeDate = target?.time?.date()
        val afterDate = after?.time?.date()
        val dateLabel = when {
            beforeDate != null && afterDate != null && beforeDate != afterDate -> "${Fmt.shortDate(beforeDate)} → ${Fmt.shortDate(afterDate)}"
            else -> (afterDate ?: beforeDate)?.let(Fmt::shortDate) ?: ""
        }
        val (b, a) = spans(target?.time, after?.time, settings)
        val recurring = target?.recurringEventId != null || ChangeFlag.RECURRING in change.flags
        val flags = change.flags.filter { it != ChangeFlag.RECURRING }.sortedBy { it.ordinal }.map(::flag)
        val calendarMove = if (beforeCal != null && afterCal != null && !beforeCal.equals(afterCal, ignoreCase = true)) "$beforeCal → $afterCal" else null
        val targetLine = target?.let { "${it.title} · ${it.calendarName.trim()} · ${Fmt.shortDate(it.time.date())}, ${it.time.label()}" }
        val verb = when (change.type) {
            ChangeType.ADD -> "Add"
            ChangeType.MOVE -> "Move"
            ChangeType.EDIT -> "Edit"
            ChangeType.REMOVE -> "Remove"
        }
        val spoken = buildList {
            add("$verb $title in $cal")
            add(dateLabel)
            b?.let { add("before ${it.label}") }
            a?.let { add("after ${it.label}") }
            addAll(flags.map { it.label })
            if (recurring) add("repeating, ${scopeLabel(change.scope)}")
        }.filter { it.isNotBlank() }.joinToString(", ")
        return ChangeCardUi(
            id = change.id,
            type = change.type,
            kindLabel = "${change.type.name} · ${cal.uppercase()}",
            title = title,
            previousTitle = previous,
            dateLabel = dateLabel,
            before = b,
            after = a,
            calendarMove = calendarMove,
            flags = flags,
            recurring = recurring,
            scope = change.scope,
            note = change.note,
            targetLine = targetLine,
            status = change.status,
            problem = change.problem,
            description = spoken,
        )
    }

    fun changeSet(cs: ChangeSet, settings: Settings, calendarNames: Map<String, String> = emptyMap()): ChangeSetUi {
        val at = cs.createdAt.atZone(settings.zone).toLocalDateTime()
        return ChangeSetUi(
            id = cs.id,
            sourceLine = "${sourceLabel(cs.source)} · ${Fmt.time(at)}",
            title = cs.summary,
            notes = cs.notes,
            cards = cs.changes.map { card(it, settings, calendarNames) },
        )
    }

    /** Button text for the main action. */
    fun approveLabel(selected: Int, total: Int): String = when {
        selected == 0 -> "Nothing selected"
        total == 1 -> "Approve and update Google"
        selected == total -> "Approve all $total"
        else -> "Approve $selected of $total"
    }

    /** The changes to send through ApprovalGate, with the scope the user picked for repeating events. */
    fun withScopes(cs: ChangeSet, scopes: Map<String, RecurrenceScope>): ChangeSet =
        if (scopes.isEmpty()) cs else cs.copy(changes = cs.changes.map { c -> scopes[c.id]?.let { c.copy(scope = it) } ?: c })

    fun report(cs: ChangeSet, record: AppliedRecord): ApplyReport {
        val titles = cs.changes.associate { it.id to (it.after?.title ?: it.target?.title ?: "Event") }
        val lines = record.results.map { r ->
            val text = when (r.status) {
                ChangeStatus.VERIFIED -> "Verified in Google Calendar"
                ChangeStatus.APPLIED -> "Written; not checked yet"
                ChangeStatus.STALE -> r.problem ?: "Changed in Google Calendar after this was proposed, so it was left alone. Your edit stays."
                ChangeStatus.FAILED -> r.problem ?: "Google did not show the change after writing it"
                ChangeStatus.REJECTED -> "Not approved"
                else -> r.problem ?: r.status.name.lowercase()
            }
            ReportLine(r.changeId, titles[r.changeId] ?: r.changeId, r.status, text)
        }
        val written = record.results.count { it.status == ChangeStatus.VERIFIED || it.status == ChangeStatus.APPLIED }
        val total = record.results.size
        val stale = record.results.count { it.status == ChangeStatus.STALE }
        val failed = record.results.count { it.status == ChangeStatus.FAILED }
        val headline = when {
            total == 0 -> "Nothing was written"
            written == total -> if (total == 1) "Done. It is in Google Calendar" else "All $total changes are in Google Calendar"
            else -> "Applied $written of $total"
        }
        val explanation = buildList {
            if (stale > 0) add(if (stale == 1) "One event was edited in Google Calendar after this was proposed, so it was not touched." else "$stale events were edited in Google Calendar after this was proposed, so they were not touched.")
            if (failed > 0) add(if (failed == 1) "One change did not check out after writing; see below." else "$failed changes did not check out after writing; see below.")
        }.joinToString(" ").ifBlank { null }
        val snackbar = when (written) {
            0 -> "Nothing written to Google"
            1 -> "1 change written to Google"
            else -> "$written changes written to Google"
        }
        return ApplyReport(cs.id, headline, explanation, lines, written, snackbar, canUndo = written > 0 && !record.undone)
    }

    /** "When it was proposed" in the device zone, for tests and headers. */
    fun proposedAt(cs: ChangeSet, zone: ZoneId): LocalDateTime = cs.createdAt.atZone(zone).toLocalDateTime()
}
