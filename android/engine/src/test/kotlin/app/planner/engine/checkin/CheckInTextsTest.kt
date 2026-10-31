package app.planner.engine.checkin

import app.planner.engine.changeset.ChangeSet
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.model.EventRef
import app.planner.engine.model.NotificationPrefs
import app.planner.engine.model.Prayer
import app.planner.engine.ports.ProposalOutcome
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheckInTextsTest {
    private val t = LocalDateTime.of(2026, 11, 3, 18, 0)
    private val os = CheckInBlock(EventRef("c", "os"), "lec-os-tue", "Lecture - OS", t, t.plusMinutes(75), 15, listOf(Prayer.ISHA))
    private val pintos = CheckInBlock(EventRef("c", "p"), "pintos", "PintOS", t.minusHours(3), t)

    @Test
    fun `start ping reads like the plan`() {
        val text = CheckInTexts.forCheckIn(CheckIn("s", CheckInKind.START, t, os))
        assertEquals("Lecture - OS, 18:00", text.title)
        assertEquals("Until 19:15. Includes 15 min for Isha.", text.body)
        assertEquals(OpenTarget.TODAY, text.open)
    }

    @Test
    fun `end ping asks whether it is done`() {
        val text = CheckInTexts.forCheckIn(CheckIn("e", CheckInKind.END, t, pintos))
        assertEquals("PintOS done?", text.title)
        assertEquals("Planned 15:00 to 18:00.", text.body)
    }

    @Test
    fun `next up names the next block and asks about the last`() {
        val text = CheckInTexts.forCheckIn(CheckIn("n", CheckInKind.NEXT_UP, t, pintos, next = os))
        assertEquals("Next up: Lecture - OS, 18:00", text.title)
        assertEquals("PintOS done? Lecture - OS runs until 19:15. Includes 15 min for Isha.", text.body)
    }

    @Test
    fun `prayer break says how long`() {
        val text = CheckInTexts.forCheckIn(CheckIn("p", CheckInKind.PRAYER_BREAK, t.plusMinutes(3), os, prayer = Prayer.ISHA))
        assertEquals("Isha break, 15 min", text.title)
        assertEquals("Lecture - OS carries on until 19:15.", text.body)
    }

    @Test
    fun `no buffer, no buffer note`() {
        assertNull(CheckInTexts.bufferNote(pintos))
    }

    @Test
    fun `two prayers in the buffer are both named`() {
        assertEquals("Includes 30 min for Dhuhr and Asr.", CheckInTexts.bufferNote(os.copy(prayerBufferMin = 30, coveredPrayers = listOf(Prayer.DHUHR, Prayer.ASR))))
    }

    @Test
    fun `action labels follow the settings`() {
        val prefs = NotificationPrefs(snoozeMin = 5, extendMin = 20)
        assertEquals(listOf("Start", "Snooze 5", "Skip"), CheckInKind.START.actions().map { CheckInTexts.actionLabel(it, prefs) })
        assertEquals(listOf("Done", "Need 20 more", "Stop here"), CheckInKind.END.actions().map { CheckInTexts.actionLabel(it, prefs) })
        assertEquals(listOf("Done, start next", "Need 20 more", "Stop here"), CheckInKind.NEXT_UP.actions().map { CheckInTexts.actionLabel(it, prefs) })
        assertEquals(emptyList(), CheckInKind.PRAYER_BREAK.actions())
    }

    private fun CheckInKind.actions() = CheckIn("x", this, t, os, next = os, prayer = Prayer.ISHA).actions

    @Test
    fun `replan notices only open approvals for a real proposal`() {
        val cs = ChangeSet("cs1", ChangeSource.NOTIFICATION, "PintOS ran over: move 2 blocks", emptyList(), Instant.EPOCH)
        val proposed = CheckInTexts.replanNotice(ProposalOutcome.Proposed(cs, emptyList()))
        assertEquals("Review change", proposed.title)
        assertEquals("PintOS ran over: move 2 blocks (0 changes). Nothing moves until you approve.", proposed.body)
        assertEquals(OpenTarget.APPROVALS, proposed.open)

        assertEquals(OpenTarget.TODAY, CheckInTexts.replanNotice(ProposalOutcome.NothingToDo("Nothing left today")).open)
        assertEquals("Nothing left today", CheckInTexts.replanNotice(ProposalOutcome.NothingToDo("Nothing left today")).body)
        assertEquals("Could not replan", CheckInTexts.replanNotice(ProposalOutcome.Rejected(listOf("Past the hard cap."))).title)
        assertEquals("Which one?", CheckInTexts.replanNotice(ProposalOutcome.NeedsChoice("Which one?", emptyList())).body)
    }

    @Test
    fun `open extras are stable strings`() {
        assertEquals("approvals", OpenTarget.APPROVALS.extra)
        assertEquals("open", OpenTarget.EXTRA)
        assertEquals(OpenTarget.APPROVALS, OpenTarget.parse("approvals"))
        assertNull(OpenTarget.parse("nope"))
    }
}
