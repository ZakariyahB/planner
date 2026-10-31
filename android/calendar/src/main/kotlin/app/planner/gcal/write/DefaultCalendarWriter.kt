package app.planner.gcal.write

import app.planner.engine.changeset.AppliedRecord
import app.planner.engine.changeset.Change
import app.planner.engine.changeset.ChangeResult
import app.planner.engine.changeset.ChangeSet
import app.planner.engine.changeset.ChangeStatus
import app.planner.engine.changeset.ChangeType
import app.planner.engine.changeset.EventDraft
import app.planner.engine.changeset.EventSnapshot
import app.planner.engine.changeset.EventTime
import app.planner.engine.changeset.InverseOp
import app.planner.engine.changeset.PlannerTags
import app.planner.engine.changeset.RecurrenceScope
import app.planner.engine.model.EventRef
import app.planner.engine.model.TaskStatus
import app.planner.engine.ports.ChangeSetStore
import app.planner.engine.ports.CheckInRescheduler
import app.planner.engine.ports.SnapshotStore
import app.planner.engine.ports.TaskStore
import app.planner.gcal.ApprovedChangeSet
import app.planner.gcal.AuthTracker
import app.planner.gcal.CalendarDirectory
import app.planner.gcal.CalendarException
import app.planner.gcal.CalendarGateway
import app.planner.gcal.CalendarWriter
import app.planner.gcal.EventIds
import app.planner.gcal.IdSource
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The only code that writes to Google Calendar (newplan 7.6 to 7.9).
 *
 * For each approved change, in order:
 *  1. Refuse anything outside the writable calendars (Prayer times never), whatever the ChangeSet says.
 *  2. Pre-flight: re-fetch the target. A different etag or updated time means STALE: nothing is written, and the
 *     change is re-proposed against the fresh event. If the event already looks exactly as approved (a retry after
 *     a partial failure), nothing is written either and the change goes straight to verification.
 *  3. Apply with client-generated ids for ADDs (a retried insert is a no-op) and the planner tags.
 *  4. Verify by re-reading: calendar, title, start and end must match what was approved; removed events must be gone.
 *     A mismatch is FAILED and reported.
 *  5. Store the inverse op for undo, the fresh snapshot (so sync does not mistake this write for the user's edit),
 *     the task links and the applied record; then reschedule check-ins.
 * Google sign-in expiry stops the run: the rest is marked FAILED with "sign in again" and nothing is retried later.
 */
class DefaultCalendarWriter(
    private val gateway: CalendarGateway,
    private val changeSets: ChangeSetStore,
    private val snapshots: SnapshotStore,
    private val tasks: TaskStore,
    private val rescheduler: CheckInRescheduler,
    private val auth: AuthTracker,
    private val clock: () -> Instant,
    private val ids: IdSource,
    /** Runs after anything was written, e.g. a calendar refresh. Failures are ignored. */
    private val afterWrite: suspend () -> Unit = {},
) : CalendarWriter {
    private val mutex = Mutex()
    private val hm = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

    private class Outcome(
        val status: ChangeStatus,
        val ref: EventRef?,
        val problem: String? = null,
        val inverse: InverseOp? = null,
        /** What the event looks like now (null when it is gone), for the snapshot cache and task links. */
        val now: EventSnapshot? = null,
        val gone: EventRef? = null,
        val wrote: Boolean = false,
        val fresh: EventSnapshot? = null,
    )

    private class Expired : Exception()

    override suspend fun apply(approved: ApprovedChangeSet): AppliedRecord = mutex.withLock {
        val cs = approved.changeSet
        if (approved.approved.isEmpty()) {
            changeSets.save(cs.copy(status = ChangeStatus.REJECTED, changes = cs.changes.map { it.copy(status = ChangeStatus.REJECTED) }))
            return@withLock AppliedRecord(cs.id, clock(), emptyList(), emptyList())
        }
        val outcomes = linkedMapOf<String, Outcome>()
        val directory = try {
            CalendarDirectory(gateway.calendars())
        } catch (e: CancellationException) {
            throw e
        } catch (e: CalendarException.AuthExpired) {
            auth.expired(); null
        } catch (e: Exception) {
            null
        }
        var expired = directory == null && auth.state.value == app.planner.engine.ports.AuthState.EXPIRED
        for (c in approved.approved) {
            if (directory == null || expired) {
                outcomes[c.id] = Outcome(ChangeStatus.FAILED, c.target?.ref, if (expired) SIGN_IN else OFFLINE)
                continue
            }
            outcomes[c.id] = try {
                applyOne(cs, c, directory)
            } catch (e: Expired) {
                expired = true
                auth.expired()
                Outcome(ChangeStatus.FAILED, c.target?.ref, SIGN_IN)
            }
        }

        val all = outcomes.values
        all.mapNotNull { it.now }.takeIf { it.isNotEmpty() }?.let { snapshots.upsert(it) }
        all.mapNotNull { it.gone }.takeIf { it.isNotEmpty() }?.let { snapshots.remove(it) }
        relinkTasks(all.mapNotNull { o -> o.now?.let { it.ref to it } ?: o.gone?.let { it to null } }.toMap())

        val record = AppliedRecord(
            changeSetId = cs.id,
            appliedAt = clock(),
            results = approved.approved.map { c -> outcomes.getValue(c.id).let { ChangeResult(c.id, it.status, it.ref, it.problem) } },
            inverse = all.mapNotNull { it.inverse }.reversed(),
        )
        changeSets.saveApplied(record)
        changeSets.save(settled(cs, outcomes))
        reproposeStale(cs, approved.approved.filter { outcomes.getValue(it.id).status == ChangeStatus.STALE }, outcomes)

        if (all.any { it.wrote }) afterWrites()
        record
    }

    private suspend fun applyOne(cs: ChangeSet, c: Change, directory: CalendarDirectory): Outcome {
        val target = c.target
        val after = c.after
        // 1. Only writable calendars, whatever the ChangeSet says.
        listOfNotNull(target?.ref?.calendarId, after?.calendarId).firstOrNull { !directory.isWritable(it) }?.let { id ->
            return Outcome(ChangeStatus.FAILED, target?.ref, "${directory.nameOf(id).trim()} is read-only for the app; nothing was written")
        }
        return try {
            when (c.type) {
                ChangeType.ADD -> add(cs, c, after!!)
                ChangeType.MOVE, ChangeType.EDIT -> update(c, target!!, after!!)
                ChangeType.REMOVE -> remove(c, target!!)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CalendarException.AuthExpired) {
            throw Expired()
        } catch (e: CalendarException.NotFound) {
            Outcome(ChangeStatus.STALE, target?.ref, "The event is no longer on the calendar; nothing was written")
        } catch (e: CalendarException.ReadOnlyCalendar) {
            Outcome(ChangeStatus.FAILED, target?.ref, "Google refused: the calendar is read-only")
        } catch (e: CalendarException.Network) {
            Outcome(ChangeStatus.FAILED, target?.ref, OFFLINE)
        } catch (e: Exception) {
            Outcome(ChangeStatus.FAILED, target?.ref, e.message ?: "Google Calendar refused the change")
        }
    }

    private suspend fun add(cs: ChangeSet, c: Change, after: EventDraft): Outcome {
        val eventId = c.newEventId ?: EventIds.from("${cs.id}/${c.id}")
        if (!EventIds.isValid(eventId)) return Outcome(ChangeStatus.FAILED, null, "Invalid event id \"$eventId\"; nothing was written")
        val ref = EventRef(after.calendarId, eventId)
        val draft = tagged(c, after)
        val existing = gateway.event(ref)
        val wrote = if (existing != null && matches(existing, draft)) false else { gateway.insert(after.calendarId, eventId, draft); true }
        return verify(ref, draft, InverseOp.Delete(ref), wrote)
    }

    private suspend fun update(c: Change, target: EventSnapshot, after: EventDraft): Outcome {
        val draft = tagged(c, after)
        val newRef = EventRef(after.calendarId, target.ref.eventId)
        val inverse = if (c.scope == RecurrenceScope.THIS_INSTANCE) InverseOp.Revert(newRef, draftOf(target)) else null
        val fresh = gateway.event(target.ref) ?: gateway.event(newRef)
            ?: return Outcome(ChangeStatus.STALE, target.ref, "The event is no longer on the calendar; nothing was written")
        if (matches(fresh, draft)) return verify(newRef, draft, inverse, wrote = false) // already applied: a retry
        if (fresh.etag != target.etag || fresh.updated != target.updated) {
            return Outcome(ChangeStatus.STALE, target.ref, "Changed in Google Calendar since it was proposed; nothing was written", fresh = fresh)
        }
        gateway.patch(target.ref, draft, c.scope)
        return verify(newRef, draft, inverse, wrote = true).withSeriesNote(c.scope)
    }

    private suspend fun remove(c: Change, target: EventSnapshot): Outcome {
        val inverse = if (c.scope == RecurrenceScope.THIS_INSTANCE) InverseOp.Restore(target) else null
        val fresh = gateway.event(target.ref)
            ?: return Outcome(ChangeStatus.VERIFIED, target.ref, "Already removed", inverse = inverse, gone = target.ref)
        if (fresh.etag != target.etag || fresh.updated != target.updated) {
            return Outcome(ChangeStatus.STALE, target.ref, "Changed in Google Calendar since it was proposed; nothing was written", fresh = fresh)
        }
        try {
            gateway.delete(target.ref, c.scope)
        } catch (e: CalendarException.NotFound) {
            // gone already: fine, verification below confirms it
        }
        val still = gateway.event(target.ref)
        return if (still == null) {
            Outcome(ChangeStatus.VERIFIED, target.ref, inverse = inverse, gone = target.ref, wrote = true).withSeriesNote(c.scope)
        } else {
            Outcome(ChangeStatus.FAILED, target.ref, "Google Calendar still shows ${target.title} after removing it", inverse = inverse, now = still, wrote = true)
        }
    }

    private suspend fun verify(ref: EventRef, draft: EventDraft, inverse: InverseOp?, wrote: Boolean): Outcome {
        val read = gateway.event(ref)
            ?: return Outcome(ChangeStatus.FAILED, ref, "Not found in Google Calendar after writing", inverse = inverse, wrote = wrote)
        return if (matches(read, draft)) {
            Outcome(ChangeStatus.VERIFIED, ref, inverse = inverse, now = read, wrote = wrote)
        } else {
            Outcome(ChangeStatus.FAILED, ref, "Google Calendar shows ${describe(read)}; approved ${describe(draft)}", inverse = inverse, now = read, wrote = wrote)
        }
    }

    private fun Outcome.withSeriesNote(scope: RecurrenceScope): Outcome =
        if (scope == RecurrenceScope.THIS_INSTANCE || problem != null) this
        else Outcome(status, ref, "Undo is not available for changes to a recurring series", inverse, now, gone, wrote, fresh)

    /** Planner tags on blocks that belong to a task. They link events to tasks; they do not limit what may be edited. */
    private fun tagged(c: Change, draft: EventDraft): EventDraft {
        val taskId = c.taskId ?: return draft
        val props = draft.privateProps.toMutableMap()
        props[PlannerTags.PLANNER] = "1"
        props[PlannerTags.TASK_ID] = taskId
        if (PlannerTags.CHUNK !in props) props[PlannerTags.CHUNK] = "${c.chunkIndex ?: 1}/1"
        if (PlannerTags.REV !in props) props[PlannerTags.REV] = "1"
        return draft.copy(privateProps = props)
    }

    private fun settled(cs: ChangeSet, outcomes: Map<String, Outcome>): ChangeSet {
        val changes = cs.changes.map { c ->
            val o = outcomes[c.id]
            if (o == null) c.copy(status = ChangeStatus.REJECTED) else c.copy(status = o.status, problem = o.problem)
        }
        val statuses = outcomes.values.map { it.status }
        val status = when {
            statuses.all { it == ChangeStatus.VERIFIED } -> ChangeStatus.VERIFIED
            statuses.any { it == ChangeStatus.FAILED } -> ChangeStatus.FAILED
            statuses.any { it == ChangeStatus.STALE } && statuses.none { it == ChangeStatus.VERIFIED } -> ChangeStatus.STALE
            else -> ChangeStatus.APPLIED
        }
        return cs.copy(changes = changes, status = status)
    }

    /** newplan 7.6: a stale change is proposed again against the event as it is now. */
    private suspend fun reproposeStale(cs: ChangeSet, stale: List<Change>, outcomes: Map<String, Outcome>) {
        val again = stale.mapNotNull { c ->
            val fresh = outcomes.getValue(c.id).fresh ?: return@mapNotNull null
            c.copy(id = ids.next(), target = fresh, status = ChangeStatus.PROPOSED, problem = "Changed in Google Calendar after it was proposed; check it again")
        }
        if (again.isEmpty()) return
        changeSets.save(
            ChangeSet(
                id = ids.next(), source = cs.source, summary = "Check again: ${cs.summary}", changes = again, createdAt = clock(),
                notes = listOf("${again.size} event${if (again.size == 1) " was" else "s were"} changed in Google Calendar before this could be applied."),
            ),
        )
    }

    override suspend fun undo(record: AppliedRecord): AppliedRecord = mutex.withLock {
        if (record.undone || record.inverse.isEmpty()) return@withLock record
        val cached = cachedSnapshots()
        val problems = mutableMapOf<EventRef, String>()
        val finalState = mutableMapOf<EventRef, EventSnapshot?>()
        var wrote = false
        var expired = false
        for (op in record.inverse) {
            val ref = when (op) {
                is InverseOp.Delete -> op.ref
                is InverseOp.Restore -> op.snapshot.ref
                is InverseOp.Revert -> op.ref
            }
            if (expired) { problems[ref] = SIGN_IN; continue }
            try {
                when (op) {
                    is InverseOp.Delete -> {
                        val fresh = gateway.event(ref)
                        if (fresh == null) { finalState[ref] = null; continue }
                        if (cached[ref]?.let { it.etag != fresh.etag } == true) { problems[ref] = CHANGED_SINCE; continue }
                        try { gateway.delete(ref, RecurrenceScope.THIS_INSTANCE) } catch (e: CalendarException.NotFound) { }
                        wrote = true
                        if (gateway.event(ref) == null) finalState[ref] = null else problems[ref] = "Still on the calendar after undo"
                    }
                    is InverseOp.Restore -> {
                        val before = draftOf(op.snapshot)
                        val fresh = gateway.event(ref)
                        if (fresh == null) { gateway.insert(ref.calendarId, ref.eventId, before); wrote = true }
                        val read = gateway.event(ref)
                        if (read != null && matches(read, before)) finalState[ref] = read else problems[ref] = "Could not put ${op.snapshot.title} back"
                    }
                    is InverseOp.Revert -> {
                        val fresh = gateway.event(ref)
                        if (fresh == null) { problems[ref] = "No longer on the calendar"; continue }
                        if (cached[ref]?.let { it.etag != fresh.etag } == true) { problems[ref] = CHANGED_SINCE; continue }
                        if (!matches(fresh, op.before)) { gateway.patch(ref, op.before, RecurrenceScope.THIS_INSTANCE); wrote = true }
                        val read = gateway.event(EventRef(op.before.calendarId, ref.eventId))
                        if (read != null && matches(read, op.before)) {
                            if (read.ref != ref) finalState[ref] = null
                            finalState[read.ref] = read
                        } else problems[ref] = "Could not put it back as it was"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CalendarException.AuthExpired) {
                expired = true; auth.expired(); problems[ref] = SIGN_IN
            } catch (e: CalendarException.Network) {
                problems[ref] = OFFLINE
            } catch (e: Exception) {
                problems[ref] = e.message ?: "Undo failed"
            }
        }
        finalState.filterValues { it != null }.values.filterNotNull().takeIf { it.isNotEmpty() }?.let { snapshots.upsert(it) }
        finalState.filterValues { it == null }.keys.toList().takeIf { it.isNotEmpty() }?.let { snapshots.remove(it) }
        relinkTasks(finalState)
        val undone = record.copy(
            undone = problems.isEmpty(),
            results = record.results.map { r -> r.ref?.let(problems::get)?.let { r.copy(problem = "Undo: $it") } ?: r },
        )
        changeSets.saveApplied(undone)
        if (wrote) afterWrites()
        undone
    }

    /** Snapshots the app holds, to tell whether the user changed an event after it was applied. */
    private suspend fun cachedSnapshots(): Map<EventRef, EventSnapshot> {
        val today = LocalDate.ofInstant(clock(), java.time.ZoneOffset.UTC)
        return snapshots.events(today.minusDays(400), today.plusDays(400)).associateBy { it.ref }
    }

    /** Keep Task.calendarEventIds and status in step with what is on the calendar now for the [state] refs. */
    private suspend fun relinkTasks(state: Map<EventRef, EventSnapshot?>) {
        if (state.isEmpty()) return
        val all = tasks.all()
        val changed = all.mapNotNull { task ->
            var refs = task.calendarEventIds.filter { ref -> ref !in state || state[ref]?.let { it.taskId == task.id && it.time is EventTime.Timed } == true }
            state.values.filterNotNull().filter { it.taskId == task.id && it.time is EventTime.Timed && it.ref !in refs }
                .sortedBy { (it.time as EventTime.Timed).start }.forEach { refs = refs + it.ref }
            val status = when {
                refs.isNotEmpty() && task.status == TaskStatus.BACKLOG -> TaskStatus.SCHEDULED
                refs.isEmpty() && task.status == TaskStatus.SCHEDULED -> TaskStatus.BACKLOG
                else -> task.status
            }
            val updated = task.copy(calendarEventIds = refs, status = status)
            updated.takeIf { it != task }
        }
        if (changed.isNotEmpty()) tasks.upsert(changed)
    }

    private suspend fun afterWrites() {
        runCatching { rescheduler.reschedule() }
        runCatching { afterWrite() }
    }

    private fun describe(e: EventSnapshot) = "\"${e.title}\" ${describe(e.time)} in ${e.calendarName.trim()}"

    private fun describe(d: EventDraft) = "\"${d.title}\" ${describe(d.time)}"

    private fun describe(t: EventTime) = when (t) {
        is EventTime.Timed -> "${t.start.format(hm)}-${t.end.toLocalTime()}"
        is EventTime.AllDay -> "all day ${t.start}"
    }

    companion object {
        const val SIGN_IN = "Google sign-in expired: sign in again, then apply this again. Nothing else was written."
        const val OFFLINE = "Could not reach Google Calendar; nothing was written for this change"
        const val CHANGED_SINCE = "Changed in Google Calendar after it was applied; left as it is"

        fun draftOf(e: EventSnapshot) = EventDraft(e.ref.calendarId, e.title, e.time, e.description, e.privateProps)

        /** newplan 7.8: calendar, title, start and end must be what was approved. */
        fun matches(e: EventSnapshot, d: EventDraft): Boolean =
            e.ref.calendarId == d.calendarId && e.title == d.title && e.time == d.time
    }
}
