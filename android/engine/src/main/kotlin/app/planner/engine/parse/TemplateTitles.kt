package app.planner.engine.parse

import app.planner.engine.model.Prayer
import java.time.DayOfWeek
import java.time.LocalTime

/** What a numbered all-day template title says about its task. */
data class TemplateSpec(
    /** The number the title starts with; it keeps the template sorted in Google Calendar. */
    val order: Int,
    val title: String,
    val durationMin: Int,
    /** `from 15:00`: a lecture recording is available from then. */
    val earliestStart: LocalTime? = null,
    /** `after Asr`: may not start before that prayer's slot ends. */
    val afterPrayer: Prayer? = null,
    /** `Fri's`: which session this is (Friday's lecture), kept so two copies of the same lecture can be told apart. */
    val sessionOf: DayOfWeek? = null,
    /** `no split`: one continuous block. */
    val splittable: Boolean = true,
    /** `due Fri` / `by Fri`: the next such day on or after the template day. */
    val dueBy: DayOfWeek? = null,
)

sealed interface TitleParse {
    data class Template(val spec: TemplateSpec) : TitleParse

    /** Numbered like a template, but the brackets could not be read. Goes to "Needs a look". */
    data class NeedsALook(val reason: String) : TitleParse

    /** Not a template title at all (no leading number): an ordinary all-day event such as a holiday. */
    data object NotATemplate : TitleParse
}

/**
 * newplan 6: `^\d+\.\s*(title)\s*\((duration)(,\s*(from HH:MM|after <Prayer>|<weekday>'s|...))?\)$`.
 *
 * Examples: `1. PintOS (3h)`, `2. Lecture - OS (2h, from 15:00)`, `3. Arabic hw (1h, after Asr)`, `4. Lecture - SDE (1h, Fri's)`.
 * The title may contain brackets of its own (`5. Quran class (Sh Anass) (30m)`); only the last group is read.
 * Notes inside the brackets are separated by commas or semicolons and may come in any order after the duration.
 */
object TemplateTitles {
    private val NUMBERED = Regex("""^\s*(\d+)\s*[.)]\s*(.*)$""")
    private val SHAPE = Regex("""^(.+?)\s*\(([^()]*)\)\s*$""")
    private val FROM = Regex("""^(?:from|at|after|not before|starts?|start at|@)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val AFTER = Regex("""^after\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val SESSION = Regex("""^([a-z]+)\s*(?:'s|’s|s)$""", RegexOption.IGNORE_CASE)
    private val DUE = Regex("""^(?:due|by|deadline)\s+([a-z]+)$""", RegexOption.IGNORE_CASE)
    private val NO_SPLIT = setOf("no split", "nosplit", "don't split", "dont split", "do not split", "unsplit", "one block", "in one go", "one go")

    private val DECIMAL_START = Regex("""^\s*\d+\.\d""")

    fun parse(raw: String): TitleParse {
        if (DECIMAL_START.containsMatchIn(raw)) return TitleParse.NotATemplate // "1.5h reading" is not item 1
        val numbered = NUMBERED.matchEntire(raw) ?: return TitleParse.NotATemplate
        val order = numbered.groupValues[1].toInt()
        val rest = numbered.groupValues[2].trim()
        val shape = SHAPE.matchEntire(rest)
            ?: return TitleParse.NeedsALook("No duration in brackets, e.g. \"$rest (2h)\"")
        val title = shape.groupValues[1].replace(Regex("""\s+"""), " ").trim().trimEnd('-', '–', ':').trim()
        if (title.isEmpty()) return TitleParse.NeedsALook("No title before the brackets")

        val parts = shape.groupValues[2].split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return TitleParse.NeedsALook("Empty brackets: add a duration such as (2h)")
        val duration = Durations.parse(parts.first())
            ?: return TitleParse.NeedsALook("\"${parts.first()}\" is not a duration (try 3h, 1h30, 45m or 1.5h)")

        var spec = TemplateSpec(order = order, title = title, durationMin = duration)
        for (note in parts.drop(1)) {
            spec = applyNote(spec, note) ?: return TitleParse.NeedsALook("Did not understand \"$note\"")
        }
        return TitleParse.Template(spec)
    }

    private fun applyNote(spec: TemplateSpec, note: String): TemplateSpec? {
        val lower = note.lowercase().replace(Regex("""\s+"""), " ")
        if (lower in NO_SPLIT) return spec.copy(splittable = false)
        AFTER.matchEntire(note)?.let { m -> Names.prayer(m.groupValues[1])?.let { return spec.copy(afterPrayer = it) } }
        FROM.matchEntire(note)?.let { m -> Names.clock(m.groupValues[1])?.let { return spec.copy(earliestStart = it) } }
        DUE.matchEntire(note)?.let { m -> Names.weekday(m.groupValues[1])?.let { return spec.copy(dueBy = it) } }
        SESSION.matchEntire(note)?.let { m -> Names.weekday(m.groupValues[1])?.let { return spec.copy(sessionOf = it) } }
        // A bare time or prayer with no keyword ("15:00", "Asr") is too ambiguous to guess.
        return null
    }
}
