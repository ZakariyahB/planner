package app.planner.ai

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatContextTest {
    private fun entry(role: Role, text: String) = ChatEntry(role, text)

    @Test
    fun `entries become messages in order`() {
        val messages = ChatHistory.toMessages(listOf(entry(Role.USER, "hi"), entry(Role.ASSISTANT, "hello"), entry(Role.USER, "plan")))
        assertEquals(listOf("hi", "hello", "plan"), messages.map { it.text })
        assertEquals(listOf(Role.USER, Role.ASSISTANT, Role.USER), messages.map { it.role })
    }

    @Test
    fun `only the most recent entries are kept`() {
        val entries = (1..30).map { entry(if (it % 2 == 1) Role.USER else Role.ASSISTANT, "m$it") }
        val messages = ChatHistory.toMessages(entries, maxEntries = 6)
        assertEquals(listOf("m25", "m26", "m27", "m28", "m29", "m30"), messages.map { it.text })
    }

    @Test
    fun `the conversation always starts with the user, as the API requires`() {
        val entries = listOf(entry(Role.ASSISTANT, "old reply"), entry(Role.USER, "q"), entry(Role.ASSISTANT, "a"))
        val messages = ChatHistory.toMessages(entries)
        assertEquals(Role.USER, messages.first().role)
        assertEquals(listOf("q", "a"), messages.map { it.text })
    }

    @Test
    fun `trimming that leaves an assistant message first drops it`() {
        val entries = listOf(entry(Role.USER, "u1"), entry(Role.ASSISTANT, "a1"), entry(Role.USER, "u2"), entry(Role.ASSISTANT, "a2"))
        assertEquals(listOf("u2", "a2"), ChatHistory.toMessages(entries, maxEntries = 3).map { it.text })
    }

    @Test
    fun `consecutive messages from one side are merged`() {
        val messages = ChatHistory.toMessages(listOf(entry(Role.USER, "one"), entry(Role.USER, "two"), entry(Role.ASSISTANT, "ok")))
        assertEquals(listOf("one\n\ntwo", "ok"), messages.map { it.text })
    }

    @Test
    fun `blank entries are skipped`() {
        val messages = ChatHistory.toMessages(listOf(entry(Role.USER, "  "), entry(Role.USER, "real")))
        assertEquals(listOf("real"), messages.map { it.text })
    }

    @Test
    fun `a character budget drops the oldest entries first`() {
        val entries = listOf(entry(Role.USER, "a".repeat(100)), entry(Role.ASSISTANT, "b".repeat(100)), entry(Role.USER, "c".repeat(100)), entry(Role.ASSISTANT, "d".repeat(100)))
        val messages = ChatHistory.toMessages(entries, maxChars = 250)
        assertEquals(listOf("c".repeat(100), "d".repeat(100)), messages.map { it.text })
    }

    @Test
    fun `the preamble gives the model the date, time and zone`() {
        val now = ZonedDateTime.of(2026, 10, 26, 14, 7, 0, 0, ZoneId.of("Europe/London"))
        val text = Preamble.forNow(now)
        assertTrue("Monday 26 October 2026" in text)
        assertTrue("14:07" in text)
        assertTrue("Europe/London" in text)
    }

    @Test
    fun `the preamble goes in front of the user's words, not in the system prompt`() {
        val now = ZonedDateTime.of(2026, 10, 26, 14, 7, 0, 0, ZoneId.of("Europe/London"))
        val text = Preamble.withUserText(now, "Halaqa ran over by an hour")
        assertTrue(text.startsWith("["))
        assertTrue(text.endsWith("Halaqa ran over by an hour"))
    }
}
