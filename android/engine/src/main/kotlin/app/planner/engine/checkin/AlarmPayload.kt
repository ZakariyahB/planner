package app.planner.engine.checkin

import app.planner.engine.model.EventRef
import app.planner.engine.model.ExecutionLog
import app.planner.engine.model.ExecutionOutcome
import app.planner.engine.model.Prayer
import app.planner.engine.nudge.Nudge
import app.planner.engine.nudge.NudgeKind
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What an alarm (and the action buttons of the notification it posts) carries: the check-in or prompt itself,
 * as one string extra. Alarms are set from synced events so they work offline; the payload is everything the
 * receiver needs without reading the stores first. Decoding is tolerant: an unreadable payload is null, never a crash.
 */
sealed interface AlarmPayload {
    val id: String

    data class CheckInAlarm(val checkIn: CheckIn) : AlarmPayload {
        override val id: String get() = checkIn.id
    }

    data class NudgeAlarm(val nudge: Nudge) : AlarmPayload {
        override val id: String get() = nudge.id
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun encode(p: AlarmPayload): String = json.encodeToString(Dto.serializer(), Dto.from(p))

        fun decode(text: String?): AlarmPayload? = runCatching { json.decodeFromString(Dto.serializer(), text!!).toPayload() }.getOrNull()

        fun encodeBlock(b: CheckInBlock): String = json.encodeToString(BlockDto.serializer(), BlockDto.from(b))

        fun decodeBlock(text: String?): CheckInBlock? = runCatching { json.decodeFromString(BlockDto.serializer(), text!!).toBlock() }.getOrNull()
    }
}

@Serializable
private data class BlockDto(
    @SerialName("c") val calendarId: String,
    @SerialName("e") val eventId: String,
    @SerialName("t") val taskId: String? = null,
    @SerialName("n") val title: String,
    @SerialName("s") val start: String,
    @SerialName("f") val end: String,
    @SerialName("b") val buffer: Int = 0,
    @SerialName("p") val prayers: List<String> = emptyList(),
) {
    fun toBlock() = CheckInBlock(
        EventRef(calendarId, eventId), taskId, title, LocalDateTime.parse(start), LocalDateTime.parse(end), buffer,
        prayers.mapNotNull { p -> Prayer.entries.firstOrNull { it.name == p } },
    )

    companion object {
        fun from(b: CheckInBlock) = BlockDto(
            b.ref.calendarId, b.ref.eventId, b.taskId, b.title, b.plannedStart.toString(), b.plannedEnd.toString(),
            b.prayerBufferMin, b.coveredPrayers.map { it.name },
        )
    }
}

@Serializable
private data class Dto(
    val type: String,
    val id: String,
    val kind: String,
    val at: String,
    val block: BlockDto? = null,
    val next: BlockDto? = null,
    val prayer: String? = null,
    val shiftedFrom: String? = null,
    val forDate: String? = null,
) {
    fun toPayload(): AlarmPayload = when (type) {
        CHECK_IN -> AlarmPayload.CheckInAlarm(
            CheckIn(
                id = id,
                kind = CheckInKind.valueOf(kind),
                at = LocalDateTime.parse(at),
                block = requireNotNull(block).toBlock(),
                next = next?.toBlock(),
                prayer = prayer?.let(Prayer::valueOf),
                shiftedFrom = shiftedFrom?.let(LocalDateTime::parse),
            ),
        )
        NUDGE -> AlarmPayload.NudgeAlarm(Nudge(id, NudgeKind.valueOf(kind), LocalDateTime.parse(at), LocalDate.parse(requireNotNull(forDate))))
        else -> error("unknown payload $type")
    }

    companion object {
        const val CHECK_IN = "checkin"
        const val NUDGE = "nudge"

        fun from(p: AlarmPayload): Dto = when (p) {
            is AlarmPayload.CheckInAlarm -> with(p.checkIn) {
                Dto(CHECK_IN, id, kind.name, at.toString(), BlockDto.from(block), next?.let(BlockDto::from), prayer?.name, shiftedFrom?.toString())
            }
            is AlarmPayload.NudgeAlarm -> with(p.nudge) { Dto(NUDGE, id, kind.name, at.toString(), forDate = forDate.toString()) }
        }
    }
}

/**
 * Checked when an alarm fires: the user may have tapped Start or Done in the app since the alarm was set, and
 * logging does not reschedule. [logOf] looks up a block's log.
 */
fun CheckIn.stillDue(logOf: (CheckInBlock) -> ExecutionLog?): Boolean {
    val finished = setOf(ExecutionOutcome.DONE, ExecutionOutcome.SKIPPED, ExecutionOutcome.STOPPED)
    val own = logOf(block)?.outcome
    if (own in finished) return false
    return when (kind) {
        CheckInKind.START -> own != ExecutionOutcome.STARTED && own != ExecutionOutcome.EXTENDED
        CheckInKind.END, CheckInKind.PRAYER_BREAK -> true
        CheckInKind.NEXT_UP -> true // the previous block is still open; the question stands
    }
}
