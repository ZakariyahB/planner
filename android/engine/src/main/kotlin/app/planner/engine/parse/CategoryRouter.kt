package app.planner.engine.parse

import app.planner.engine.model.CalendarCategory

/**
 * Chooses a calendar for a new item from its title (docs/prompts/calendar-categories.md, "Choosing a calendar").
 *
 * The table's rules are applied in order, with three refinements the table's own examples need:
 *  - a title that says "commute" (or "kahf", the table's commute example) goes to Commute even if it names Quran;
 *  - "Lecture"/"Tutorial" is checked before the module names, so `Lecture - SDE` stays in Lectures, not Academic;
 *  - the fixed personal commitments the table lists under Personal (`Quran class (Sh Anass)`, `Halaqa`) stay there.
 * Generic study words (revision, homework) count as Academic only when the title does not name Quran or Arabic.
 */
object CategoryRouter {
    private val commute = words("commute", "commuting", "on the way", "on the train", "on the bus", "kahf")
    private val lectures = words("lecture", "lectures", "lec", "tutorial", "tutorials")
    private val personalCommitments = words("quran class", "qur'an class", "halaqa", "halaqah", "sh anass")
    private val academicStrong = words(
        "worksheet", "worksheets", "work sheet", "problem sheet", "problem sheets", "problem set", "cw", "coursework",
        "pintos", "sde", "assignment", "lab report", "past paper", "past papers",
    )
    private val quranArabic = words("quran", "qur'an", "quraan", "koran", "arabic", "hifz", "tajweed", "tajwid", "juz", "surah", "madinah book")
    private val academicWeak = words("revision", "revise", "revising", "homework", "hw", "module", "exam", "exams", "study")
    private val society = words("stem muslims", "stemm", "society", "soc", "isoc", "jumu'ah prep", "jumuah prep", "jummah prep", "jumu'ah", "jummah", "committee")
    private val fitness = words("workout", "work out", "gym", "run", "running", "jog", "exercise", "football", "swim", "swimming", "cycling", "climbing", "training", "badminton", "basketball", "boxing")

    fun route(title: String): CalendarCategory {
        val t = normalise(title)
        return when {
            commute.any { it.containsMatchIn(t) } -> CalendarCategory.COMMUTE
            lectures.any { it.containsMatchIn(t) } -> CalendarCategory.LECTURES
            personalCommitments.any { it.containsMatchIn(t) } -> CalendarCategory.PERSONAL
            academicStrong.any { it.containsMatchIn(t) } -> CalendarCategory.ACADEMIC
            quranArabic.any { it.containsMatchIn(t) } -> CalendarCategory.QURAN_ARABIC
            academicWeak.any { it.containsMatchIn(t) } -> CalendarCategory.ACADEMIC
            society.any { it.containsMatchIn(t) } -> CalendarCategory.SOCIETY
            fitness.any { it.containsMatchIn(t) } -> CalendarCategory.FITNESS
            else -> CalendarCategory.PERSONAL
        }
    }

    /** Lower case, curly apostrophes straightened, everything but letters, digits, & and ' turned into single spaces. */
    private fun normalise(s: String): String =
        s.lowercase().replace('’', '\'').replace(Regex("""[^a-z0-9&']+"""), " ").trim()

    private fun words(vararg phrases: String): List<Regex> =
        phrases.map { Regex("""(?<![a-z0-9])${Regex.escape(it)}(?![a-z0-9])""") }
}
