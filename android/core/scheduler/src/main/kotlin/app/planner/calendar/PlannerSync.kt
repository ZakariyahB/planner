package app.planner.calendar

import java.time.Instant

/** A planned block as the sync sees it. [synced*] is what Google had when we last pushed or pulled. */
data class SyncBlock(
    val id: Long,
    val gcalEventId: String?,
    val start: Instant,
    val end: Instant,
    val syncedStart: Instant?,
    val syncedEnd: Instant?,
)

/** An event on the Planner calendar; [blockId] comes from the private extended property we write. */
data class RemoteEvent(val id: String, val blockId: Long?, val start: Instant, val end: Instant)

sealed interface SyncAction {
    /** Block has no event yet. */
    data class CreateRemote(val blockId: Long) : SyncAction

    /** Block moved in the app; push the new time. */
    data class UpdateRemote(val blockId: Long, val eventId: String) : SyncAction

    /** Event was dragged in Google Calendar; the block follows (and becomes user-owned and locked). */
    data class PullTime(val blockId: Long, val start: Instant, val end: Instant) : SyncAction

    /** Both sides already agree but the stored snapshot is stale; just refresh it. */
    data class MarkSynced(val blockId: Long, val start: Instant, val end: Instant) : SyncAction

    /** Block no longer exists; remove its event. */
    data class DeleteRemote(val eventId: String) : SyncAction

    /** Event was deleted in Google Calendar; unschedule the block so the planner can place the task again. */
    data class ForgetBlock(val blockId: Long) : SyncAction
}

/**
 * Two-way sync of task blocks with the Planner calendar. If both sides changed since the last
 * sync, the app wins, because the app validates every change.
 */
object PlannerSync {
    fun diff(blocks: List<SyncBlock>, remote: List<RemoteEvent>): List<SyncAction> {
        val remoteById = remote.associateBy { it.id }
        val blockIds = blocks.map { it.id }.toSet()
        val actions = mutableListOf<SyncAction>()

        for (b in blocks) {
            val eventId = b.gcalEventId
            if (eventId == null) {
                actions += SyncAction.CreateRemote(b.id)
                continue
            }
            val r = remoteById[eventId]
            if (r == null) {
                actions += SyncAction.ForgetBlock(b.id)
                continue
            }
            val localChanged = b.start != b.syncedStart || b.end != b.syncedEnd
            val remoteChanged = r.start != b.syncedStart || r.end != b.syncedEnd
            when {
                r.start == b.start && r.end == b.end && (localChanged || remoteChanged) ->
                    actions += SyncAction.MarkSynced(b.id, b.start, b.end)
                localChanged && (r.start != b.start || r.end != b.end) -> actions += SyncAction.UpdateRemote(b.id, eventId)
                !localChanged && remoteChanged -> actions += SyncAction.PullTime(b.id, r.start, r.end)
            }
        }

        // Events we created for blocks that are gone. Events without our tag are the user's own: left alone.
        remote.filter { it.blockId != null && it.blockId !in blockIds }
            .forEach { actions += SyncAction.DeleteRemote(it.id) }
        return actions
    }
}
