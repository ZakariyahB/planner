package app.planner.calendar

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlannerSyncTest {
    private val t0 = Instant.parse("2026-10-28T09:00:00Z")
    private val t1 = Instant.parse("2026-10-28T10:00:00Z")
    private val t2 = Instant.parse("2026-10-28T11:00:00Z")

    private fun block(id: Long, event: String?, start: Instant, end: Instant, synced: Pair<Instant, Instant>? = start to end) =
        SyncBlock(id, event, start, end, synced?.first, synced?.second)

    @Test
    fun `new blocks are created remotely`() {
        assertEquals(listOf<SyncAction>(SyncAction.CreateRemote(1)), PlannerSync.diff(listOf(block(1, null, t0, t1, null)), emptyList()))
    }

    @Test
    fun `unchanged blocks produce no actions`() {
        assertTrue(PlannerSync.diff(listOf(block(1, "e1", t0, t1)), listOf(RemoteEvent("e1", 1, t0, t1))).isEmpty())
    }

    @Test
    fun `a move in the app is pushed`() {
        val actions = PlannerSync.diff(listOf(block(1, "e1", t1, t2, synced = t0 to t1)), listOf(RemoteEvent("e1", 1, t0, t1)))
        assertEquals(listOf<SyncAction>(SyncAction.UpdateRemote(1, "e1")), actions)
    }

    @Test
    fun `an edit made in Google Calendar is pulled`() {
        val actions = PlannerSync.diff(listOf(block(1, "e1", t0, t1)), listOf(RemoteEvent("e1", 1, t1, t2)))
        assertEquals(listOf<SyncAction>(SyncAction.PullTime(1, t1, t2)), actions)
    }

    @Test
    fun `when both sides changed the app wins`() {
        val actions = PlannerSync.diff(listOf(block(1, "e1", t1, t2, synced = t0 to t1)), listOf(RemoteEvent("e1", 1, t2, t2.plusSeconds(3600))))
        assertEquals(listOf<SyncAction>(SyncAction.UpdateRemote(1, "e1")), actions)
    }

    @Test
    fun `both sides changed to the same time only refreshes the snapshot`() {
        val actions = PlannerSync.diff(listOf(block(1, "e1", t1, t2, synced = t0 to t1)), listOf(RemoteEvent("e1", 1, t1, t2)))
        assertEquals(listOf<SyncAction>(SyncAction.MarkSynced(1, t1, t2)), actions)
    }

    @Test
    fun `an event deleted in Google Calendar unschedules the block`() {
        assertEquals(listOf<SyncAction>(SyncAction.ForgetBlock(1)), PlannerSync.diff(listOf(block(1, "e1", t0, t1)), emptyList()))
    }

    @Test
    fun `events of removed blocks are deleted but untagged events are left alone`() {
        val remote = listOf(RemoteEvent("gone", 7, t0, t1), RemoteEvent("mine", null, t0, t1))
        assertEquals(listOf<SyncAction>(SyncAction.DeleteRemote("gone")), PlannerSync.diff(emptyList(), remote))
    }
}
