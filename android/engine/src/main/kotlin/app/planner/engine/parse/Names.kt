package app.planner.engine.parse

import app.planner.engine.model.Prayer
import java.time.DayOfWeek
import java.time.LocalTime

/** Prayer names, weekday names and clock times as they appear in titles, in their common spellings. */
object Names {
    private val prayerSpellings: List<Pair<Prayer, List<String>>> = listOf(
        Prayer.FAJR to listOf("fajr", "fajar", "fajer", "subh", "fadjr"),
        Prayer.DHUHR to listOf("dhuhr", "zuhr", "duhr", "dhuhur", "zuhur", "dhur", "thuhr", "dhohr", "zohr"),
        Prayer.ASR to listOf("asr", "asar"),
        Prayer.MAGHRIB to listOf("maghrib", "magrib", "maghreb"),
        Prayer.ISHA to listOf("isha", "esha", "ishaa", "isha'a"),
    )

    /** The prayer a word names exactly (`Asr`, `zuhr`), or null. */
    fun prayer(word: String): Prayer? {
        val w = word.trim().lowercase()
        return prayerSpellings.firstOrNull { (_, names) -> w in names }?.first
    }

    /** The prayer a longer title mentions as a whole word (`Dhuhr`, `Asr begins`, `Maghrib 16:35`), or null. */
    fun prayerIn(title: String): Prayer? {
        val words = title.lowercase().split(Regex("""[^a-z']+""")).filter { it.isNotEmpty() }
        return words.firstNotNullOfOrNull { prayer(it) }
    }

    private val weekdaySpellings: Map<String, DayOfWeek> = buildMap {
        fun add(day: DayOfWeek, vararg names: String) = names.forEach { put(it, day) }
        add(DayOfWeek.MONDAY, "mon", "monday")
        add(DayOfWeek.TUESDAY, "tue", "tues", "tuesday")
        add(DayOfWeek.WEDNESDAY, "wed", "weds", "wednesday")
        add(DayOfWeek.THURSDAY, "thu", "thur", "thurs", "thursday")
        add(DayOfWeek.FRIDAY, "fri", "friday")
        add(DayOfWeek.SATURDAY, "sat", "saturday")
        add(DayOfWeek.SUNDAY, "sun", "sunday")
    }

    fun weekday(word: String): DayOfWeek? = weekdaySpellings[word.trim().lowercase()]

    /** Three-letter English name, as used in descriptions ("Sat prayer time interpolated"). */
    fun shortName(day: DayOfWeek): String = day.name.take(3).lowercase().replaceFirstChar { it.uppercase() }

    private val CLOCK = Regex("""^(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)?$""", RegexOption.IGNORE_CASE)

    /**
     * `15:00`, `9:30`, `14.30`, `3pm`, `3:30pm`. A bare hour without am/pm (`15`) is accepted only as 24-hour,
     * so `from 3` is not guessed and the title goes to Needs a look.
     */
    fun clock(text: String): LocalTime? {
        val m = CLOCK.matchEntire(text.trim()) ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val half = m.groupValues[3].lowercase()
        if (m.groupValues[2].isEmpty() && half.isEmpty() && hour < 13) return null
        if (half.isNotEmpty()) {
            if (hour !in 1..12) return null
            hour = when {
                half == "am" && hour == 12 -> 0
                half == "pm" && hour != 12 -> hour + 12
                else -> hour
            }
        }
        return if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
    }
}
