package app.planner.assistant.tools

import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.EventRef
import app.planner.engine.model.Prayer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** The model sent something a tool cannot use. The message goes back to it as an error result so it can retry. */
class ToolInputException(message: String) : Exception(message)

/** Reads tool inputs strictly: a wrong type or format is an error the model sees, never a silent guess. */
internal class Input(private val obj: JsonObject, private val where: String = "") {
    private fun label(key: String) = if (where.isEmpty()) key else "$where.$key"

    private fun raw(key: String): JsonPrimitive? = when (val v = obj[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> v
        else -> throw ToolInputException("${label(key)} must be a single value")
    }

    fun string(key: String): String? = raw(key)?.let { p ->
        if (!p.isString) throw ToolInputException("${label(key)} must be a string")
        p.content.trim().ifEmpty { null }
    }

    fun requireString(key: String): String = string(key) ?: throw ToolInputException("${label(key)} is required")

    fun int(key: String): Int? = raw(key)?.let { p ->
        val d = (if (p.isString) p.content.toDoubleOrNull() else p.doubleOrNull)
            ?: throw ToolInputException("${label(key)} must be a whole number")
        if (d != Math.floor(d)) throw ToolInputException("${label(key)} must be a whole number")
        d.toInt()
    }

    fun boolean(key: String): Boolean? = raw(key)?.let { p ->
        p.booleanOrNull ?: throw ToolInputException("${label(key)} must be true or false")
    }

    fun date(key: String): LocalDate? = string(key)?.let {
        try {
            LocalDate.parse(it)
        } catch (e: DateTimeParseException) {
            throw ToolInputException("${label(key)} must be a date like 2026-11-05")
        }
    }

    fun requireDate(key: String): LocalDate = date(key) ?: throw ToolInputException("${label(key)} is required")

    fun time(key: String): LocalTime? = string(key)?.let {
        try {
            LocalTime.parse(it)
        } catch (e: DateTimeParseException) {
            throw ToolInputException("${label(key)} must be a 24-hour time like 15:00")
        }
    }

    /** "HH:MM" (on [defaultDate]) or "YYYY-MM-DDTHH:MM". */
    fun dateTime(key: String, defaultDate: LocalDate): LocalDateTime? = string(key)?.let {
        try {
            if (it.contains('T')) LocalDateTime.parse(it) else defaultDate.atTime(LocalTime.parse(it))
        } catch (e: DateTimeParseException) {
            throw ToolInputException("${label(key)} must be a time like 15:30 or 2026-11-05T15:30")
        }
    }

    fun dates(key: String): List<LocalDate>? = when (val v = obj[key]) {
        null, JsonNull -> null
        is JsonArray -> v.mapIndexed { i, e ->
            val s = (e as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?: throw ToolInputException("${label(key)}[$i] must be a date string")
            try {
                LocalDate.parse(s.trim())
            } catch (ex: DateTimeParseException) {
                throw ToolInputException("${label(key)}[$i] must be a date like 2026-11-05")
            }
        }
        else -> throw ToolInputException("${label(key)} must be a list of dates")
    }

    fun objects(key: String): List<Input> = when (val v = obj[key]) {
        is JsonArray -> v.mapIndexed { i, e ->
            Input(e as? JsonObject ?: throw ToolInputException("${label(key)}[$i] must be an object"), "${label(key)}[$i]")
        }
        null, JsonNull -> throw ToolInputException("${label(key)} is required")
        else -> throw ToolInputException("${label(key)} must be a list")
    }

    fun category(key: String): CalendarCategory? = string(key)?.let { name ->
        CalendarCategory.forCalendarName(name)
            ?: CalendarCategory.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: throw ToolInputException("${label(key)} must be one of: ${CalendarCategory.entries.joinToString { it.calendarName }}")
    }

    fun afterPrayer(key: String): Prayer? = string(key)?.let { name ->
        val p = Prayer.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (p == null || p == Prayer.FAJR) {
            throw ToolInputException("${label(key)} must be one of: ${ToolNames.afterPrayers.joinToString()}")
        }
        p
    }

    fun scope(key: String): RecurrenceScope = when (string(key)?.lowercase()) {
        null, ToolNames.Scopes.THIS_INSTANCE -> RecurrenceScope.THIS_INSTANCE
        ToolNames.Scopes.THIS_AND_FOLLOWING -> RecurrenceScope.THIS_AND_FOLLOWING
        ToolNames.Scopes.WHOLE_SERIES -> RecurrenceScope.WHOLE_SERIES
        else -> throw ToolInputException("${label(key)} must be one of: ${ToolNames.Scopes.all.joinToString()}")
    }

    /** An exact event, as find_events or get_week returned it. */
    fun eventRef(): EventRef? {
        val cal = string("calendar_id")
        val id = string("event_id")
        if (cal == null && id == null) return null
        if (cal == null || id == null) {
            throw ToolInputException("${label("calendar_id")} and ${label("event_id")} must be given together, exactly as find_events returned them")
        }
        return EventRef(cal, id)
    }

    fun requireEventRef(): EventRef = eventRef()
        ?: throw ToolInputException("${label("calendar_id")} and ${label("event_id")} are required: call find_events first and use a candidate's ids")
}
