package app.planner.ui.model

import app.planner.engine.changeset.AppliedRecord
import app.planner.engine.changeset.Change
import app.planner.engine.changeset.ChangeResult
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.changeset.ChangeStatus
import app.planner.engine.changeset.ChangeType
import app.planner.engine.changeset.EventDraft
import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.EventTime
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.fakes.FakeProposalPort
import app.planner.engine.fakes.InMemoryChangeSetStore
import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.EventRef
import app.planner.engine.model.Settings
import app.planner.gcal.ApprovalGate
import java.time.Instant
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalCardsTest {
    private val settings = Settings()
    private val sample = FakeProposalPort(InMemoryChangeSetStore(), clock = { Instant.parse("2026-11-04T12:45:00Z") })
        .sample(ChangeSource.NOTIFICATION, "Halaqa ran over: move 2 blocks")

    @Test
    fun moveCardShowsBeforeAfterCalendarAndFlags() {
        val ui = ApprovalCards.changeSet(sample, settings)
        assertEquals("From your check-in · 12:45", ui.sourceLine)
        assertEquals("Halaqa ran over: move 2 blocks", ui.title)
        val c = ui.cards.first()
        assertEquals("MOVE · ACADEMIC", c.kindLabel)
        assertEquals("P&S CW (2/2)", c.title)
        assertEquals("Wed 4 Nov", c.dateLabel)
        assertEquals("16:46 – 17:31", c.before!!.label)
        assertEquals("17:46 – 18:46", c.after!!.label)
        assertTrue(c.after!!.startFraction > c.before!!.startFraction)
        assertEquals(listOf(FlagUi("Extended day", warning = true)), c.flags)
        assertEquals("Includes 15 min for Isha", c.note)
        assertEquals("P&S CW (2/2) · Academic · Wed 4 Nov, 16:46 – 17:31", c.targetLine)
        assertNull(c.previousTitle)
        assertNull(c.calendarMove)
        assertFalse(c.recurring)
    }

    @Test
    fun barsShareTheDayWindowFromWakeToHardCap() {
        val (b, a) = ApprovalCards.spans(
            EventTime.Timed(ReferenceWeek.days[1].atTime(9, 30), ReferenceWeek.days[1].atTime(15, 45)),
            EventTime.Timed(ReferenceWeek.days[1].atTime(9, 30), ReferenceWeek.days[1].atTime(22, 0)),
            settings,
        )
        assertEquals(0f, b!!.startFraction, 0.001f)
        assertEquals(0.5f, b.endFraction, 0.001f)
        assertEquals(1f, a!!.endFraction, 0.001f)
    }

    @Test
    fun recurringRemoveDefaultsToThisInstanceAndScopeCanBeChosen() {
        val day = ReferenceWeek.days[3]
        val target = EventSnapshot(
            ref = EventRef("cal-personal", "quran-1"), calendarName = "Personal", title = "Quran class (Sh Anass)",
            time = EventTime.Timed(day.atTime(18, 0), day.atTime(18, 30)), etag = "\"1\"", updated = Instant.EPOCH,
            recurringEventId = "quran-series",
        )
        val cs = sample.copy(changes = listOf(Change("r1", ChangeType.REMOVE, target = target)))
        val card = ApprovalCards.changeSet(cs, settings).cards.single()
        assertTrue(card.recurring)
        assertEquals(RecurrenceScope.THIS_INSTANCE, card.scope)
        assertEquals("REMOVE · PERSONAL", card.kindLabel)
        assertNull(card.after)

        val chosen = ApprovalCards.withScopes(cs, mapOf("r1" to RecurrenceScope.WHOLE_SERIES))
        val approved = ApprovalGate.approve(chosen, mapOf("r1" to true))
        assertEquals(RecurrenceScope.WHOLE_SERIES, approved.approved.single().scope)
    }

    @Test
    fun addCardFindsTheCalendarNameAndRenameShowsTheOldTitle() {
        val day = ReferenceWeek.days[6]
        val add = Change(
            "a1", ChangeType.ADD,
            after = EventDraft("cal-personal", "Internship apps", EventTime.Timed(day.atTime(14, 30), day.atTime(16, 30))),
        )
        val card = ApprovalCards.card(add, settings)
        assertEquals("ADD · PERSONAL", card.kindLabel)
        assertEquals("Sun 8 Nov", card.dateLabel)
        assertNull(card.before)

        val named = ApprovalCards.card(add, settings, mapOf("cal-personal" to "Personal "))
        assertEquals("ADD · PERSONAL", named.kindLabel)

        val target = sample.changes.first().target!!
        val rename = Change("e1", ChangeType.EDIT, target = target, after = sample.changes.first().after!!.copy(title = "P&S coursework (2/2)"))
        assertEquals("P&S CW (2/2)", ApprovalCards.card(rename, settings).previousTitle)
    }

    @Test
    fun movingToAnotherDayShowsBothDates() {
        val c = sample.changes.first()
        val after = c.after!!.copy(time = EventTime.Timed(ReferenceWeek.days[3].atTime(LocalTime.of(10, 0)), ReferenceWeek.days[3].atTime(LocalTime.of(10, 45))))
        assertEquals("Wed 4 Nov → Thu 5 Nov", ApprovalCards.card(c.copy(after = after), settings).dateLabel)
    }

    @Test
    fun approveLabels() {
        assertEquals("Approve and update Google", ApprovalCards.approveLabel(1, 1))
        assertEquals("Approve all 3", ApprovalCards.approveLabel(3, 3))
        assertEquals("Approve 2 of 3", ApprovalCards.approveLabel(2, 3))
        assertEquals("Nothing selected", ApprovalCards.approveLabel(0, 3))
    }

    @Test
    fun reportSaysWhatWasWrittenAndWhatWentStale() {
        val record = AppliedRecord(
            sample.id, Instant.EPOCH,
            listOf(ChangeResult("c1", ChangeStatus.VERIFIED, null), ChangeResult("c2", ChangeStatus.STALE, null)),
            inverse = emptyList(),
        )
        val r = ApprovalCards.report(sample, record)
        assertEquals("Applied 1 of 2", r.headline)
        assertEquals("1 change written to Google", r.snackbar)
        assertTrue(r.canUndo)
        assertTrue(r.explanation!!.startsWith("One event was edited in Google Calendar"))
        assertEquals(ChangeStatus.STALE, r.lines[1].status)
        assertEquals("OS worksheet (2/2)", r.lines[1].title)

        val all = ApprovalCards.report(sample, record.copy(results = record.results.map { it.copy(status = ChangeStatus.VERIFIED) }))
        assertEquals("All 2 changes are in Google Calendar", all.headline)
        assertNull(all.explanation)
        assertFalse(ApprovalCards.report(sample, record.copy(undone = true)).canUndo)
    }

    @Test
    fun onlyApprovedChangesReachTheGate() {
        val approved = ApprovalGate.approve(sample, mapOf("c1" to true, "c2" to false))
        assertEquals(listOf("c1"), approved.approved.map { it.id })
    }
}
