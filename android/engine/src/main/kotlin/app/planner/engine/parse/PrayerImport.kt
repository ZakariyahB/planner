package app.planner.engine.parse

import app.planner.engine.model.PrayerAnchor
import java.time.LocalDate
import java.time.LocalTime

/**
 * newplan 8, prayer import: an event in the read-only Prayer times calendar becomes a [PrayerAnchor].
 * The title says which prayer (`Dhuhr`, `Asr`, ...); the event start is the anchor. The description's `Begins HH:MM`
 * is the fallback when the event has no start time of its own (an all-day entry), then a time in the title.
 * Titles that name no prayer (`Jumu'ah`, `Sunrise`) are skipped.
 */
object PrayerImport {
    private val BEGINS = Regex("""\bbegins?\b\s*(?:at)?\s*[:\-]?\s*(\d{1,2}[:.]\d{2}(?:\s*[ap]m)?)""", RegexOption.IGNORE_CASE)
    private val ANY_TIME = Regex("""\b(\d{1,2}[:.]\d{2}(?:\s*[ap]m)?)""", RegexOption.IGNORE_CASE)

    /** The time after `Begins` in a description (plain text or the simple HTML Google stores). */
    fun begins(description: String?): LocalTime? {
        if (description.isNullOrBlank()) return null
        val text = description.replace(Regex("""<[^>]+>"""), " ").replace("&nbsp;", " ")
        return BEGINS.find(text)?.groupValues?.get(1)?.let(Names::clock)
    }

    /** [start] is the event's local start time, or null for an all-day entry. */
    fun anchor(title: String, description: String?, date: LocalDate, start: LocalTime?): PrayerAnchor? {
        val prayer = Names.prayerIn(title) ?: return null
        val time = start
            ?: begins(description)
            ?: ANY_TIME.find(title)?.groupValues?.get(1)?.let(Names::clock)
            ?: return null
        return PrayerAnchor(date, prayer, time.withSecond(0).withNano(0))
    }
}
