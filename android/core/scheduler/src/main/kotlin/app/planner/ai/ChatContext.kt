package app.planner.ai

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One saved turn of the conversation: just the words, not the tool traffic. */
data class ChatEntry(val role: Role, val text: String)

object ChatHistory {
    /**
     * The recent conversation as API messages. Keeps at most [maxEntries] entries and [maxChars] characters
     * (oldest dropped first), starts on a user message, and merges neighbours from the same side.
     */
    fun toMessages(entries: List<ChatEntry>, maxEntries: Int = 20, maxChars: Int = 12_000): List<Message> {
        val recent = entries.filter { it.text.isNotBlank() }.takeLast(maxEntries)

        var budget = maxChars
        val withinBudget = recent.asReversed().takeWhile { entry ->
            budget -= entry.text.length
            budget >= 0
        }.asReversed()

        val merged = mutableListOf<ChatEntry>()
        for (entry in withinBudget.dropWhile { it.role != Role.USER }) {
            val last = merged.lastOrNull()
            if (last != null && last.role == entry.role) {
                merged[merged.lastIndex] = last.copy(text = last.text + "\n\n" + entry.text)
            } else {
                merged += entry
            }
        }
        return merged.map { Message(it.role, listOf(ContentBlock.Text(it.text))) }
    }
}

/**
 * The date and time change on every call, so they go in front of the user's words rather than in the
 * system prompt, which has to stay identical to be cached.
 */
object Preamble {
    private val FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", Locale.UK)

    fun forNow(now: ZonedDateTime): String = "[Now: ${FORMAT.format(now)} (${now.zone.id})]"

    fun withUserText(now: ZonedDateTime, text: String): String = "${forNow(now)}\n\n$text"
}
