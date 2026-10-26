package app.planner.model

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
enum class Prayer { FAJR, DHUHR, ASR, MAGHRIB, ISHA }

/** "Asr + 15": the task becomes available [offsetMin] minutes after [prayer]. */
@Serializable
data class Anchor(val prayer: Prayer, val offsetMin: Int = 0)

@Serializable
enum class Priority { LOW, NORMAL, HIGH }

enum class TaskSource { MANUAL, CHAT, TEMPLATE, SUNDAY }

enum class TaskStatus { OPEN, DONE, PARTIAL, DROPPED }

data class Task(
    val id: Long = 0,
    val title: String,
    val module: String? = null,
    val estMin: Int,
    val availableFrom: Instant? = null,
    val anchor: Anchor? = null,
    val dueAt: Instant? = null,
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
