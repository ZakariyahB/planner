package app.planner.social

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageBackfillTest {
    private val today = LocalDate.parse("2026-10-26")

    private fun days(vararg iso: String) = iso.map(LocalDate::parse)

    @Test
    fun `on first use every past day the phone still holds is filled, oldest first`() {
        assertEquals(
            days("2026-10-19", "2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23", "2026-10-24", "2026-10-25"),
            UsageBackfill.daysToFill(today, saved = emptySet()),
        )
    }

    @Test
    fun `days already saved are left alone, because Android may since have dropped their older events`() {
        assertEquals(
            days("2026-10-19", "2026-10-22"),
            UsageBackfill.daysToFill(today, saved = days("2026-10-20", "2026-10-21", "2026-10-23", "2026-10-24", "2026-10-25").toSet()),
        )
    }

    @Test
    fun `today is never part of the backfill, it is tracked on every check anyway`() {
        assertEquals(emptyList(), UsageBackfill.daysToFill(today, saved = days("2026-10-19", "2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23", "2026-10-24", "2026-10-25").toSet()))
    }
}
