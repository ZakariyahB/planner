package app.planner.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.Serializable

@Serializable
enum class Prayer { FAJR, DHUHR, ASR, MAGHRIB, ISHA }

/** "Asr + 15": the task becomes available [offsetMin] minutes after [prayer]. */
@Serializable
data class Anchor(val prayer: Prayer, val offsetMin: Int = 0)

@Serializable
enum class Priority { LOW, NORMAL, HIGH }

enum class TaskSource { MANUAL, CHAT, TEMPLATE, SUNDAY, CALENDAR }

enum class TaskStatus { OPEN, DONE, PARTIAL, DROPPED }

data class Task(
    val id: Long = 0,
    val title: String,
    val module: String? = null,
    val estMin: Int,
    val availableFrom: Instant? = null,
    val anchor: Anchor? = null,
    val dueAt: Instant? = null,
    /** "Do it at this time" on its due day (or today if undated). Optional; unset lets the planner choose. */
    val startAt: LocalTime? = null,
    val priority: Priority = Priority.NORMAL,
    val source: TaskSource = TaskSource.MANUAL,
    val status: TaskStatus = TaskStatus.OPEN,
    /** "<templateItemId>@<date>" for template-generated tasks; makes generation idempotent. */
    val templateKey: String? = null,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
        require(estMin in 5..MAX_EST_MIN) { "estimate must be 5..$MAX_EST_MIN minutes" }
    }

    companion object {
        const val MAX_EST_MIN = 720
    }
}

/** One copy of this task per date, each due at the end of its day. Repeated dates are collapsed. */
fun Task.onDates(dates: Collection<LocalDate>, zone: ZoneId): List<Task> =
    dates.distinct().sorted().map { date ->
        copy(
            id = 0,
            dueAt = date.atTime(LocalTime.of(23, 59)).atZone(zone).toInstant(),
            status = TaskStatus.OPEN,
            templateKey = null,
        )
    }
