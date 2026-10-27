package app.planner.calendar

import java.time.Duration
import java.time.LocalTime

/** What an all-day calendar title says about itself: "PintOS (3h)" or "Lecture - SDE (1h, from 14:00)". */
data class AllDayHints(val title: String, val estMin: Int?, val startAt: LocalTime?)

/**
 * Reads the length and start time out of the brackets of an all-day title. Brackets that say anything else
 * ("(optional)", "(Room 3)") are left in the title untouched, so nothing he wrote is lost.
 */
object AllDayHintParser {
    private val GROUP = Regex("""\(([^()]*)\)""")
    private val HOURS = Regex("""^(\d+(?:[.,]\d+)?)\s*(?:h|hr|hrs|hour|hours)$""", RegexOption.IGNORE_CASE)
    private val HOURS_MINUTES = Regex("""^(\d+)\s*(?:h|hr|hrs|hour|hours)\s*(\d+)\s*(?:m|min|mins|minute|minutes)?$""", RegexOption.IGNORE_CASE)
    private val MINUTES = Regex("""^(\d+)\s*(?:m|min|mins|minute|minutes)$""", RegexOption.IGNORE_CASE)
    private val TIME = Regex("""^(?:from|at|@|starts?|start at)?\s*(\d{1,2}):(\d{2})$""", RegexOption.IGNORE_CASE)
    private val RANGE = Regex("""^(?:from)?\s*(\d{1,2}):(\d{2})\s*(?:-|–|—|to)\s*(\d{1,2}):(\d{2})$""", RegexOption.IGNORE_CASE)

    private sealed interface Token {
        data class Length(val minutes: Int) : Token
        data class Start(val time: LocalTime) : Token
        data class Span(val start: LocalTime, val end: LocalTime) : Token
    }

    fun parse(raw: String): AllDayHints {
        var length: Int? = null
        var start: LocalTime? = null
        var spanEnd: LocalTime? = null
        val stripped = GROUP.replace(raw) { match ->
            val tokens = match.groupValues[1].split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.map(::token)
            if (tokens.isEmpty() || tokens.any { it == null }) {
                match.value
            } else {
                tokens.filterNotNull().forEach { t ->
                    when (t) {
                        is Token.Length -> length = t.minutes
                        is Token.Start -> start = t.time
                        is Token.Span -> { start = t.start; spanEnd = t.end }
                    }
                }
                ""
            }
        }
        val title = stripped.replace(Regex("""\s+"""), " ").trim().trimEnd('-', '–', ':').trim().ifEmpty { raw.trim() }
        val fromSpan = spanEnd?.let { end -> start?.let { s -> Duration.between(s, end).toMinutes().toInt().takeIf { it > 0 } } }
        return AllDayHints(title, (length ?: fromSpan)?.coerceIn(MIN_MIN, MAX_MIN), start)
    }

    private fun token(text: String): Token? {
        HOURS.matchEntire(text)?.let { return Token.Length((it.groupValues[1].replace(',', '.').toDouble() * 60).toInt()) }
        HOURS_MINUTES.matchEntire(text)?.let { return Token.Length(it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt()) }
        MINUTES.matchEntire(text)?.let { return Token.Length(it.groupValues[1].toInt()) }
        RANGE.matchEntire(text)?.let { m ->
            val s = time(m.groupValues[1], m.groupValues[2])
            val e = time(m.groupValues[3], m.groupValues[4])
            return if (s != null && e != null && e > s) Token.Span(s, e) else null
        }
        TIME.matchEntire(text)?.let { m -> return time(m.groupValues[1], m.groupValues[2])?.let(Token::Start) }
        return null
    }

    private fun time(hour: String, minute: String): LocalTime? {
        val h = hour.toInt()
        val m = minute.toInt()
        return if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
    }

    private const val MIN_MIN = 5
    private const val MAX_MIN = 720
}
