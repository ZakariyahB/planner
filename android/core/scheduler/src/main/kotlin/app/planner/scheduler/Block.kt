package app.planner.scheduler

import java.time.LocalDate
import java.time.LocalTime

enum class BlockKind { TASK, FIXED, PRAYER, SLEEP, BUFFER }

enum class BlockOrigin { SCHEDULER, AI, USER }

/**
 * A placed piece of the day. Only TASK blocks are stored; fixed events and prayers are derived
 * from the calendar each time. A block never crosses midnight (work stops by 22:00).
 */
data class Block(
    val id: Long = 0,
    val taskId: Long? = null,
    val date: LocalDate,
    val start: LocalTime,
    val end: LocalTime,
    val kind: BlockKind = BlockKind.TASK,
    val locked: Boolean = false,
    val gcalEventId: String? = null,
    val origin: BlockOrigin = BlockOrigin.SCHEDULER,
) {
    init {
        require(end > start) { "block must end after it starts" }
    }

    val minutes: Int get() = (end.toMinuteOfDay() - start.toMinuteOfDay())
}

internal fun LocalTime.toMinuteOfDay(): Int = hour * 60 + minute

internal fun Int.toLocalTime(): LocalTime {
    require(this in 0 until 24 * 60) { "minute of day out of range: $this" }
    return LocalTime.of(this / 60, this % 60)
}

/** Half-open interval in minutes of the day. */
internal data class Slot(val start: Int, val end: Int) {
    val length: Int get() = end - start

    fun overlaps(o: Slot) = start < o.end && o.start < end
}

internal fun subtract(window: Slot, busy: List<Slot>): List<Slot> {
    val merged = busy.filter { it.end > window.start && it.start < window.end }.sortedBy { it.start }
    val free = mutableListOf<Slot>()
    var cursor = window.start
    for (b in merged) {
        if (b.start > cursor) free += Slot(cursor, minOf(b.start, window.end))
        cursor = maxOf(cursor, b.end)
        if (cursor >= window.end) break
    }
    if (cursor < window.end) free += Slot(cursor, window.end)
    return free.filter { it.length > 0 }
}
