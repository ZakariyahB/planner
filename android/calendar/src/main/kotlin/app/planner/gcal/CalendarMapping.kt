package app.planner.gcal

import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.PRAYER_CALENDAR_NAME
import app.planner.engine.ports.CalendarInfo

/**
 * newplan 3: calendar ids are resolved at runtime from calendarList by trimmed, case-insensitive name. Never hardcoded.
 * A calendar is writable when it is a category calendar the user allows in Settings and Google lets us write to it.
 * Prayer times is never writable.
 */
object CalendarMapping {
    /** Google calendarList accessRole values that allow writing events. */
    private val WRITE_ROLES = setOf("owner", "writer")

    fun isPrayerTimes(name: String): Boolean = name.trim().equals(PRAYER_CALENDAR_NAME, ignoreCase = true)

    fun info(id: String, name: String, accessRole: String?, writableCategories: Set<CalendarCategory>): CalendarInfo {
        val prayer = isPrayerTimes(name)
        val category = if (prayer) null else CalendarCategory.forCalendarName(name)
        val roleAllows = accessRole == null || accessRole in WRITE_ROLES
        return CalendarInfo(
            id = id,
            name = name.trim(),
            category = category,
            isPrayerTimes = prayer,
            writable = category != null && category in writableCategories && roleAllows,
        )
    }
}

/** Lookups over one calendar list. If two calendars share a category name, the first in the list wins. */
class CalendarDirectory(val calendars: List<CalendarInfo>) {
    private val byId = calendars.associateBy { it.id }

    fun get(id: String): CalendarInfo? = byId[id]

    fun idFor(category: CalendarCategory): String? = calendars.firstOrNull { it.category == category }?.id

    fun writableIdFor(category: CalendarCategory): String? = calendars.firstOrNull { it.category == category && it.writable }?.id

    fun isWritable(id: String): Boolean = byId[id]?.writable == true

    fun isPrayer(id: String): Boolean = byId[id]?.isPrayerTimes == true

    val prayerIds: Set<String> get() = calendars.filter { it.isPrayerTimes }.map { it.id }.toSet()

    fun nameOf(id: String): String = byId[id]?.name ?: id
}
