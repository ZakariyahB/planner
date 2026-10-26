package app.planner.calendar

import app.planner.data.SettingsRepository
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Which all-day calendar tasks are ticked off. Kept on the phone only; the calendar event itself is never changed. */
@Singleton
class AllDayTicks @Inject constructor(
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    val done: Flow<Set<String>> = settings.observe(KEY).map(::decode)

    suspend fun toggle(task: AllDayTask) {
        val now = decode(settings.get(KEY))
        val next = if (task.key in now) now - task.key else now + task.key
        settings.put(KEY, Json.encodeToString(AllDayTasks.prune(next, LocalDate.now(clock)).sorted()))
    }

    private fun decode(text: String?): Set<String> = try {
        text?.let { Json.decodeFromString<List<String>>(it).toSet() }.orEmpty()
    } catch (e: SerializationException) {
        emptySet()
    }

    private companion object {
        const val KEY = "all_day_done"
    }
}
