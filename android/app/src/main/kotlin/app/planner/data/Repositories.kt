package app.planner.data

import app.planner.calendar.CalendarInfo
import app.planner.model.Task
import app.planner.model.TaskStatus
import app.planner.model.WeeklyTemplate
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Singleton
class TaskRepository @Inject constructor(
    private val dao: TaskDao,
    private val clock: Clock,
) {
    val tasks: Flow<List<Task>> = dao.observeAll().map { rows -> rows.map { it.toTask() } }

    suspend fun save(task: Task) = dao.upsert(task.toEntity())

    suspend fun setStatus(id: Long, status: TaskStatus) = dao.setStatus(id, status.name)

    /** Creates this week's (or [weeksAhead] weeks ahead) template tasks; safe to call repeatedly. Returns how many were new. */
    suspend fun generateFromTemplate(template: WeeklyTemplate, weeksAhead: Long = 0): Int {
        val monday = LocalDate.now(clock).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weeksAhead)
        val existing = dao.templateKeys().toSet()
        val fresh = template.expand(monday, clock.zone, existing)
        dao.insertIgnoring(fresh.map { it.toEntity() })
        return fresh.size
    }
}

@Singleton
class SettingsRepository @Inject constructor(private val dao: SettingsDao) {
    private val json = Json { ignoreUnknownKeys = true }

    val template: Flow<WeeklyTemplate> =
        dao.observe(KEY_TEMPLATE).map { it?.let(WeeklyTemplate::fromJson) ?: WeeklyTemplate() }

    val calendars: Flow<List<CalendarInfo>> =
        dao.observe(KEY_CALENDARS).map { it?.let { s -> json.decodeFromString<List<CalendarInfo>>(s) }.orEmpty() }

    /** Null until the user (or first sync) has chosen; null means "use the default selection". */
    val selectedCalendarIds: Flow<Set<String>?> =
        dao.observe(KEY_SELECTED).map { it?.let { s -> json.decodeFromString<Set<String>>(s) } }

    val lastSyncedAt: Flow<Instant?> =
        dao.observe(KEY_LAST_SYNC).map { it?.toLongOrNull()?.let(Instant::ofEpochMilli) }

    suspend fun saveTemplate(template: WeeklyTemplate) = dao.put(SettingEntity(KEY_TEMPLATE, template.toJson()))

    suspend fun saveCalendars(calendars: List<CalendarInfo>) =
        dao.put(SettingEntity(KEY_CALENDARS, json.encodeToString(calendars)))

    suspend fun saveSelection(ids: Set<String>) =
        dao.put(SettingEntity(KEY_SELECTED, json.encodeToString(ids)))

    suspend fun selection(): Set<String>? =
        dao.get(KEY_SELECTED)?.let { json.decodeFromString<Set<String>>(it) }

    suspend fun saveLastSynced(at: Instant) = dao.put(SettingEntity(KEY_LAST_SYNC, at.toEpochMilli().toString()))

    private companion object {
        const val KEY_TEMPLATE = "weekly_template"
        const val KEY_CALENDARS = "calendars"
        const val KEY_SELECTED = "selected_calendars"
        const val KEY_LAST_SYNC = "last_calendar_sync"
    }
}
