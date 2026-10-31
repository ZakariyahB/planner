package app.planner.gcal.plan

import app.planner.engine.changeset.EventTime
import app.planner.engine.model.Task
import app.planner.engine.model.TaskKind
import app.planner.engine.model.UnparsedTemplate
import app.planner.engine.parse.Names
import app.planner.engine.parse.TemplateTitles
import app.planner.engine.parse.TitleParse
import app.planner.engine.ports.ImportReport
import app.planner.engine.ports.TaskStore
import app.planner.engine.model.CalendarCategory
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlinx.coroutines.flow.first

/**
 * newplan 6: the weekly template's numbered all-day events become tasks (assigned day = the event's date, category =
 * its calendar). Re-importing is idempotent: a task's id is derived from its source event, and its status and linked
 * blocks are kept. Numbered titles that do not parse go to Needs a look; unnumbered all-day events are ignored.
 */
class TemplateImporter(private val tasks: TaskStore) {
    suspend fun import(ctx: PlanningContext, from: LocalDate, toExclusive: LocalDate): ImportReport {
        val existing = tasks.all()
        val bySource = existing.filter { it.sourceEvent != null }.associateBy { it.sourceEvent!! }
        val upserts = mutableListOf<Task>()
        val unparsed = mutableListOf<UnparsedTemplate>()
        var imported = 0
        var updated = 0
        for (e in ctx.templateEvents(from, toExclusive).sortedWith(compareBy({ (it.time as EventTime.AllDay).start }, { it.title }))) {
            val date = (e.time as EventTime.AllDay).start
            val category = ctx.directory.get(e.ref.calendarId)?.category ?: continue
            when (val parsed = TemplateTitles.parse(e.title)) {
                TitleParse.NotATemplate -> Unit
                is TitleParse.NeedsALook -> unparsed += UnparsedTemplate(e.ref, e.title, e.calendarName.trim(), date)
                is TitleParse.Template -> {
                    val spec = parsed.spec
                    val fresh = Task(
                        id = idFor(e.ref.calendarId, e.ref.eventId),
                        title = spec.title,
                        category = category,
                        durationMin = spec.durationMin,
                        assignedDay = date,
                        earliestStart = spec.earliestStart,
                        afterPrayer = spec.afterPrayer,
                        deadline = spec.dueBy?.let { date.with(TemporalAdjusters.nextOrSame(it)) },
                        splittable = spec.splittable,
                        minChunkMin = ctx.settings.minChunkMin,
                        priority = 0,
                        kind = if (category == CalendarCategory.LECTURES) TaskKind.PASSIVE else TaskKind.DEEP,
                        sourceEvent = e.ref,
                        notes = spec.sessionOf?.let { "${Names.shortName(it)}'s session" },
                    )
                    val old = bySource[e.ref]
                    if (old == null) {
                        upserts += fresh
                        imported++
                    } else {
                        val merged = fresh.copy(id = old.id, status = old.status, calendarEventIds = old.calendarEventIds, priority = old.priority)
                        if (merged != old) {
                            upserts += merged
                            updated++
                        }
                    }
                }
            }
        }
        if (upserts.isNotEmpty()) tasks.upsert(upserts)
        val keepOthers = tasks.observeNeedsALook().first().filter { it.date.isBefore(from) || !it.date.isBefore(toExclusive) }
        tasks.setNeedsALook((keepOthers + unparsed).sortedBy { it.date })
        return ImportReport(imported = imported, updated = updated, needsALook = unparsed.size)
    }

    companion object {
        fun idFor(calendarId: String, eventId: String): String =
            UUID.nameUUIDFromBytes("template:$calendarId/$eventId".toByteArray()).toString()
    }
}
