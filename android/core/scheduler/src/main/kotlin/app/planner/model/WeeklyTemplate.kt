package app.planner.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object LocalTimeSerializer : KSerializer<LocalTime> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LocalTime", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalTime) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): LocalTime = LocalTime.parse(decoder.decodeString())
}

@Serializable
data class TemplateItem(
    val id: String,
    /** Every weekday this item repeats on, e.g. Monday, Wednesday and Friday. */
    val days: List<DayOfWeek> = emptyList(),
    val title: String,
    val module: String? = null,
    val estMin: Int,
    val anchor: Anchor? = null,
    val priority: Priority = Priority.NORMAL,
    /** Optional chosen time of day; unset lets the planner choose. */
    @Serializable(with = LocalTimeSerializer::class) val startAt: LocalTime? = null,
    /** Only present in templates saved before items could have several days. */
    val day: DayOfWeek? = null,
) {
    val allDays: List<DayOfWeek> get() = (days + listOfNotNull(day)).distinct().sortedBy { it.value }
}

/** Recurring weekly tasks, e.g. a lecture catch-up every Tuesday. */
@Serializable
data class WeeklyTemplate(val items: List<TemplateItem> = emptyList()) {
    fun toJson(): String = json.encodeToString(this)

    /**
     * Tasks for the week starting [weekStart], each due at the end of its day.
     * Items whose key is in [existingKeys] are skipped, so calling this twice never duplicates tasks.
     */
    fun expand(weekStart: LocalDate, zone: ZoneId, existingKeys: Set<String> = emptySet()): List<Task> {
        require(weekStart.dayOfWeek == DayOfWeek.MONDAY) { "weekStart must be a Monday" }
        return items.flatMap { item ->
            Weeks.dates(weekStart, item.allDays.toSet()).mapNotNull { date ->
                val key = "${item.id}@$date"
                if (key in existingKeys) return@mapNotNull null
                Task(
                    title = item.title,
                    module = item.module,
                    estMin = item.estMin,
                    anchor = item.anchor,
                    startAt = item.startAt,
                    dueAt = date.atTime(LocalTime.of(23, 59)).atZone(zone).toInstant(),
                    priority = item.priority,
                    source = TaskSource.TEMPLATE,
                    templateKey = key,
                )
            }
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(text: String): WeeklyTemplate = json.decodeFromString(text)
    }
}
