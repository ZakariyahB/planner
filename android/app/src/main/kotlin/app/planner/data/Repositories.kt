package app.planner.data

import app.planner.calendar.CalendarInfo
import app.planner.model.Task
import app.planner.model.TaskStatus
import app.planner.model.WeeklyTemplate
import app.planner.model.Weeks
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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

    suspend fun snapshot(): List<Task> = dao.all().map { it.toTask() }

    suspend fun save(task: Task) = dao.upsert(task.toEntity())

    suspend fun saveAll(tasks: List<Task>) = dao.upsertAll(tasks.map { it.toEntity() })

    suspend fun setStatus(id: Long, status: TaskStatus) = dao.setStatus(id, status.name)

    /** Creates this week's (or [weeksAhead] weeks ahead) template tasks; safe to call repeatedly. Returns how many were new. */
    suspend fun generateFromTemplate(template: WeeklyTemplate, weeksAhead: Long = 0): Int {
        val monday = Weeks.mondayOf(LocalDate.now(clock)).plusWeeks(weeksAhead)
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

    val plannerCalendarId: Flow<String?> = dao.observe(KEY_PLANNER_CALENDAR)

    /** This week's Fajr, keyed by the Monday of the week (asked once on Sunday). */
    val fajrWeeks: Flow<Map<LocalDate, LocalTime>> = dao.observe(KEY_FAJR).map { text ->
        text?.let { json.decodeFromString<Map<String, String>>(it) }.orEmpty()
            .mapKeys { LocalDate.parse(it.key) }
            .mapValues { LocalTime.parse(it.value) }
    }

    /** The day on which the user approved a readiness load cut, so replans keep applying it. */
    val loadCutApprovedOn: Flow<LocalDate?> = dao.observe(KEY_LOAD_CUT).map { it?.let(LocalDate::parse) }

    /** Plain key-value access for small settings such as toggles and counters. */
    fun observe(key: String): Flow<String?> = dao.observe(key)

    suspend fun get(key: String): String? = dao.get(key)

    suspend fun put(key: String, value: String) = dao.put(SettingEntity(key, value))

    suspend fun saveTemplate(template: WeeklyTemplate) = dao.put(SettingEntity(KEY_TEMPLATE, template.toJson()))

    suspend fun saveCalendars(calendars: List<CalendarInfo>) =
        dao.put(SettingEntity(KEY_CALENDARS, json.encodeToString(calendars)))

    suspend fun saveSelection(ids: Set<String>) =
        dao.put(SettingEntity(KEY_SELECTED, json.encodeToString(ids)))

    suspend fun selection(): Set<String>? =
        dao.get(KEY_SELECTED)?.let { json.decodeFromString<Set<String>>(it) }

    suspend fun saveLastSynced(at: Instant) = dao.put(SettingEntity(KEY_LAST_SYNC, at.toEpochMilli().toString()))

    suspend fun googleAccount(): String? = dao.get(KEY_ACCOUNT)

    suspend fun saveGoogleAccount(email: String) = dao.put(SettingEntity(KEY_ACCOUNT, email))

    suspend fun plannerCalendar(): String? = dao.get(KEY_PLANNER_CALENDAR)

    suspend fun savePlannerCalendar(id: String) = dao.put(SettingEntity(KEY_PLANNER_CALENDAR, id))

    suspend fun saveFajr(weekMonday: LocalDate, time: LocalTime) {
        val current = fajrWeeks.first().mapKeys { it.key.toString() }.mapValues { it.value.toString() }
        dao.put(SettingEntity(KEY_FAJR, json.encodeToString(current + (weekMonday.toString() to time.toString()))))
    }

    /** Newest sleep the last plan took into account, in epoch millis; lets a replan notice new sleep data. */
    suspend fun plannedSleepEnd(): Long? = dao.get(KEY_PLANNED_SLEEP)?.toLongOrNull()

    suspend fun savePlannedSleepEnd(millis: Long?) =
        dao.put(SettingEntity(KEY_PLANNED_SLEEP, millis?.toString().orEmpty()))

    suspend fun approveLoadCut(day: LocalDate) = dao.put(SettingEntity(KEY_LOAD_CUT, day.toString()))

    private companion object {
        const val KEY_TEMPLATE = "weekly_template"
        const val KEY_CALENDARS = "calendars"
        const val KEY_SELECTED = "selected_calendars"
        const val KEY_LAST_SYNC = "last_calendar_sync"
        const val KEY_ACCOUNT = "google_account"
        const val KEY_PLANNER_CALENDAR = "planner_calendar_id"
        const val KEY_FAJR = "fajr_weeks"
        const val KEY_LOAD_CUT = "load_cut_approved_on"
        const val KEY_PLANNED_SLEEP = "planned_sleep_end"
    }
}
