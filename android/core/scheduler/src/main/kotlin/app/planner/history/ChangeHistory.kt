package app.planner.history

import app.planner.scheduler.Block
import app.planner.scheduler.BlockDiff
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A change as the change log stores it. */
data class HistoryEntry(
    val id: Long,
    val tsMillis: Long,
    val actor: String,
    val summary: String,
    val diffs: List<BlockDiff>,
    val undone: Boolean,
)

data class HistoryItem(val id: Long, val title: String, val time: String, val who: String, val detail: String, val undone: Boolean)

data class HistoryDay(val title: String, val items: List<HistoryItem>)

/** Turns the change log into a readable list: days, who did what, and what moved where. */
object ChangeHistory {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
    private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

    fun group(entries: List<HistoryEntry>, now: Instant, zone: ZoneId): List<HistoryDay> {
        val today = now.atZone(zone).toLocalDate()
        return entries.sortedByDescending { it.tsMillis }
            .groupBy { Instant.ofEpochMilli(it.tsMillis).atZone(zone).toLocalDate() }
            .map { (date, list) ->
                HistoryDay(
                    title = dayTitle(date, today),
                    items = list.map {
                        HistoryItem(
                            id = it.id,
                            title = it.summary,
                            time = TIME.format(Instant.ofEpochMilli(it.tsMillis).atZone(zone)),
                            who = who(it.actor),
                            detail = describe(it.diffs),
                            undone = it.undone,
                        )
                    },
                )
            }
    }

    fun who(actor: String): String = when (actor) {
        "USER" -> "You"
        "AI" -> "Assistant"
        else -> "Planner"
    }

    /** What changed, for example "10:30–11:30 to 11:30–12:30". */
    fun describe(diffs: List<BlockDiff>): String {
        if (diffs.size > 1) return "${diffs.size} blocks changed"
        val diff = diffs.singleOrNull() ?: return ""
        val before = diff.before
        val after = diff.after
        return when {
            before != null && after != null ->
                if (before.date == after.date) "${times(before)} to ${times(after)}"
                else "${DAY.format(before.date)} ${times(before)} to ${DAY.format(after.date)} ${times(after)}"
            before != null -> "Was ${DAY.format(before.date)} ${times(before)}"
            after != null -> "${DAY.format(after.date)} ${times(after)}" + if (after.locked) ", locked" else ""
            else -> ""
        }
    }

    private fun times(block: Block) = "${TIME.format(block.start)}–${TIME.format(block.end)}"

    private fun dayTitle(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> DAY.format(date)
    }
}
