package app.planner.scheduler

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** Saves the day rules as small JSON text, and reads them back without ever failing: bad input means defaults. */
object DayRulesCodec {
    val BUFFER_RANGE_MIN = 0..60
    val PACKED_RANGE_MIN = 60..600
    val DAILY_CAP_RANGE_MIN = 60..720
    val CATCH_UP_RANGE_MIN = 0..480

    private const val MIN_WORK_DAY_MIN = 4 * 60

    fun encode(rules: DayRules): String = buildJsonObject {
        put("wake", rules.wake.toString())
        put("workStop", rules.workStop.toString())
        put("workStopPacked", rules.workStopPacked.toString())
        put("windDown", rules.windDown.toString())
        put("lightsOut", rules.lightsOut.toString())
        put("blockBufferMin", rules.blockBufferMin)
        put("prayerBufferMin", rules.prayerBufferMin)
        put("packedFixedMinutes", rules.packedFixedMinutes)
        put("fridayClearFrom", rules.fridayClearFrom.toString())
        put("sundayCatchUpMin", rules.sundayCatchUpMin)
        put("maxTaskMinutesPerDay", rules.maxTaskMinutesPerDay)
    }.toString()

    fun decode(text: String?): DayRules {
        val defaults = DayRules()
        val json = try {
            text?.takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) as? JsonObject }
        } catch (e: SerializationException) {
            null
        } ?: return defaults

        fun time(key: String, default: LocalTime): LocalTime = try {
            (json[key] as? JsonPrimitive)?.contentOrNull?.let(LocalTime::parse) ?: default
        } catch (e: DateTimeParseException) {
            default
        }

        fun number(key: String, default: Int, range: IntRange): Int =
            (json[key] as? JsonPrimitive)?.intOrNull?.takeIf { it in range } ?: default

        val rules = try {
            DayRules(
                wake = time("wake", defaults.wake),
                workStop = time("workStop", defaults.workStop),
                workStopPacked = time("workStopPacked", defaults.workStopPacked),
                windDown = time("windDown", defaults.windDown),
                lightsOut = time("lightsOut", defaults.lightsOut),
                blockBufferMin = number("blockBufferMin", defaults.blockBufferMin, BUFFER_RANGE_MIN),
                prayerBufferMin = number("prayerBufferMin", defaults.prayerBufferMin, BUFFER_RANGE_MIN),
                packedFixedMinutes = number("packedFixedMinutes", defaults.packedFixedMinutes, PACKED_RANGE_MIN),
                fridayClearFrom = time("fridayClearFrom", defaults.fridayClearFrom),
                sundayCatchUpMin = number("sundayCatchUpMin", defaults.sundayCatchUpMin, CATCH_UP_RANGE_MIN),
                maxTaskMinutesPerDay = number("maxTaskMinutesPerDay", defaults.maxTaskMinutesPerDay, DAILY_CAP_RANGE_MIN),
            )
        } catch (e: IllegalArgumentException) {
            return defaults
        }
        return if (problems(rules).isEmpty()) rules else defaults
    }

    /** Why these rules can't be used, in plain words; empty when they are fine. */
    fun problems(rules: DayRules): List<String> = buildList {
        val wake = rules.wake.hour * 60 + rules.wake.minute
        val stop = rules.workStop.hour * 60 + rules.workStop.minute
        if (stop - wake < MIN_WORK_DAY_MIN) add("Work must stop at least 4 hours after you wake.")
        if (rules.windDown < rules.workStopPacked) add("The wind-down can't be before the latest work stop.")
        if (rules.blockBufferMin !in BUFFER_RANGE_MIN || rules.prayerBufferMin !in BUFFER_RANGE_MIN) add("Buffers can be 0 to 60 minutes.")
        if (rules.packedFixedMinutes !in PACKED_RANGE_MIN) add("A packed day starts at 1 to 10 hours of fixed events.")
        if (rules.maxTaskMinutesPerDay !in DAILY_CAP_RANGE_MIN) add("Planned work per day can be 1 to 12 hours.")
        if (rules.sundayCatchUpMin !in CATCH_UP_RANGE_MIN) add("The Sunday catch-up can be 0 to 8 hours.")
    }
}
