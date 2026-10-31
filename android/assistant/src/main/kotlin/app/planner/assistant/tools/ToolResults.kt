package app.planner.assistant.tools

import app.planner.engine.changeset.Change
import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.EventTime
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.model.EventRef
import app.planner.engine.model.Prayer
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.model.Task
import app.planner.engine.ports.ProposalOutcome
import app.planner.engine.ports.TodayBlock
import app.planner.engine.ports.WeekDay
import app.planner.engine.schedule.PlanWarning
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the tools tell the model: what the engine and the calendar said, in compact JSON. Times are never invented here. */
internal object ToolResults {
    fun hhmm(t: LocalDateTime): String = "%02d:%02d".format(t.hour, t.minute)

    fun weekday(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.UK)

    private fun JsonObjectBuilder.ref(ref: EventRef) {
        put("calendar_id", ref.calendarId)
        put("event_id", ref.eventId)
    }

    fun day(d: WeekDay): JsonObject = buildJsonObject {
        put("date", d.date.toString())
        put("weekday", weekday(d.date))
        d.endTime?.let { put("ends", "%02d:%02d".format(it.hour, it.minute)) }
        put("max_tier", d.maxTier.level)
        if (d.flags.isNotEmpty()) put("flags", buildJsonArray { d.flags.sortedBy { it.name }.forEach { add(JsonPrimitive(it.name.lowercase())) } })
        if (d.overflowCount > 0) put("overflow", d.overflowCount)
        put("blocks", buildJsonArray { d.blocks.forEach { add(block(it)) } })
    }

    fun block(b: TodayBlock): JsonObject = buildJsonObject {
        b.ref?.let { ref(it) }
        put("title", listOfNotNull(b.title, b.chunkLabel).joinToString(" "))
        put("calendar", b.calendarName)
        put("start", hhmm(b.start))
        put("end", hhmm(b.end))
        if (b.isFixed) put("fixed", true)
        b.taskId?.let { put("task_id", it) }
        if (b.tier.level > 0) put("tier", b.tier.level)
        if (b.prayerBufferMin > 0) {
            put("prayer_buffer_min", b.prayerBufferMin)
            put("covers", b.coveredPrayers.joinToString { it.pretty() })
        }
    }

    fun snapshot(s: EventSnapshot): JsonObject = buildJsonObject {
        ref(s.ref)
        put("title", s.title)
        put("calendar", s.calendarName)
        time(s.time)
        if (s.recurringEventId != null) put("recurring", true)
        if (s.organiserIsExternal) put("organised_by_someone_else", true)
        if (s.isPlannerBlock) put("planner_block", true)
        s.taskId?.let { put("task_id", it) }
    }

    private fun JsonObjectBuilder.time(t: EventTime) {
        when (t) {
            is EventTime.Timed -> {
                put("date", t.start.toLocalDate().toString())
                put("weekday", weekday(t.start.toLocalDate()))
                put("start", hhmm(t.start))
                put("end", if (t.end.toLocalDate() == t.start.toLocalDate()) hhmm(t.end) else t.end.toString())
            }
            is EventTime.AllDay -> {
                put("date", t.start.toString())
                put("weekday", weekday(t.start))
                put("all_day", true)
                if (t.endExclusive > t.start.plusDays(1)) put("until", t.endExclusive.minusDays(1).toString())
            }
        }
    }

    fun task(t: Task): JsonObject = buildJsonObject {
        put("task_id", t.id)
        put("title", t.title)
        put("calendar", t.category.calendarName)
        put("duration_min", t.durationMin)
        t.assignedDay?.let { put("day", it.toString()) }
        t.earliestStart?.let { put("earliest_start", it.toString()) }
        t.afterPrayer?.let { put("after_prayer", it.pretty()) }
        t.deadline?.let { put("deadline", it.toString()) }
        if (!t.splittable) put("splittable", false)
        if (t.priority != 0) put("priority", t.priority)
        put("kind", t.kind.name.lowercase())
        put("status", t.status.name.lowercase())
        if (t.calendarEventIds.isNotEmpty()) put("blocks", t.calendarEventIds.size)
    }

    fun anchor(a: PrayerAnchor): JsonObject = buildJsonObject {
        put("prayer", a.prayer.pretty())
        put("begins", a.begin.toString())
        if (a.interpolated) put("interpolated", true)
    }

    fun outcome(o: ProposalOutcome): JsonObject = when (o) {
        is ProposalOutcome.Proposed -> buildJsonObject {
            put("status", "proposed")
            put("proposal_id", o.changeSet.id)
            put("summary", o.changeSet.summary)
            put("changes", buildJsonArray { o.changeSet.changes.forEach { add(change(it)) } })
            val notes = o.changeSet.notes + o.warnings.map(::warning)
            if (notes.isNotEmpty()) put("notes", buildJsonArray { notes.forEach { add(JsonPrimitive(it)) } })
            put("next", "Nothing has been written. The proposal is waiting on the approval card; tell the user what it does in a line or two.")
        }
        is ProposalOutcome.NeedsChoice -> buildJsonObject {
            put("status", "needs_choice")
            put("question", o.question)
            put("candidates", buildJsonArray { o.candidates.forEach { add(snapshot(it)) } })
            put("next", "Nothing was proposed. Ask the user which one they mean.")
        }
        is ProposalOutcome.Rejected -> buildJsonObject {
            put("status", "rejected")
            put("violations", buildJsonArray { o.violations.forEach { add(JsonPrimitive(it)) } })
            put("next", "Nothing was proposed. Explain why in plain words; do not try to work around the rule.")
        }
        is ProposalOutcome.NothingToDo -> buildJsonObject {
            put("status", "nothing_to_do")
            put("reason", o.reason)
        }
    }

    private fun change(c: Change): JsonObject = buildJsonObject {
        put("type", c.type.name.lowercase())
        c.target?.let { t -> put("before", buildJsonObject { ref(t.ref); put("title", t.title); put("calendar", t.calendarName); time(t.time) }) }
        c.after?.let { a ->
            put(
                "after",
                buildJsonObject {
                    put("title", a.title)
                    put("calendar", c.target?.takeIf { it.ref.calendarId == a.calendarId }?.calendarName ?: a.calendarId)
                    time(a.time)
                },
            )
        }
        if (c.flags.isNotEmpty()) put("flags", buildJsonArray { c.flags.sortedBy { it.name }.forEach { add(JsonPrimitive(it.name.lowercase())) } })
        if (c.target?.recurringEventId != null || c.scope != RecurrenceScope.THIS_INSTANCE) put("scope", c.scope.name.lowercase())
        c.note?.let { put("note", it) }
    }

    private fun warning(w: PlanWarning): String = when (w) {
        is PlanWarning.DeadlineMissed -> "Misses the deadline ${w.deadline} (task ${w.taskId})"
        is PlanWarning.AssumedGapKept -> "${w.title} is not on the calendar on ${w.date}; its gap was kept open"
        is PlanWarning.Note -> w.text
    }

    fun error(message: String): String = buildJsonObject { put("error", message) }.toString()

    private fun Prayer.pretty(): String = name.lowercase().replaceFirstChar { it.uppercase() }
}
