package app.planner.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class TemplateItem(
    val id: String,
    val day: DayOfWeek,
    val title: String,
    val module: String? = null,
    val estMin: Int,
    val anchor: Anchor? = null,
    val priority: Priority = Priority.NORMAL,
)

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
        return items.mapNotNull { item ->
            val date = weekStart.plusDays((item.day.value - DayOfWeek.MONDAY.value).toLong())
            val key = "${item.id}@$date"
            if (key in existingKeys) return@mapNotNull null
            Task(
                title = item.title,
                module = item.module,
                estMin = item.estMin,
                anchor = item.anchor,
                dueAt = date.atTime(LocalTime.of(23, 59)).atZone(zone).toInstant(),
                priority = item.priority,
                source = TaskSource.TEMPLATE,
                templateKey = key,
            )
        }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun fromJson(text: String): WeeklyTemplate = json.decodeFromString(text)
    }
}
