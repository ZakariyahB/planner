package app.planner.checkin

import android.util.Log
import app.planner.engine.checkin.AlarmDiff
import app.planner.engine.checkin.AlarmPayload
import app.planner.engine.checkin.CheckIn
import app.planner.engine.checkin.CheckInPlanner
import app.planner.engine.checkin.PlannedAlarm
import app.planner.engine.checkin.toAlarm
import app.planner.engine.model.PrayerAnchor
import app.planner.engine.nudge.Nudge
import app.planner.engine.nudge.NudgePlanner
import app.planner.engine.ports.CheckInRescheduler
import app.planner.engine.ports.ExecutionLogStore
import app.planner.engine.ports.PlannerQueries
import app.planner.engine.ports.SettingsStore
import app.planner.engine.ports.TodayBlock
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sets the exact alarms for today's and tomorrow's check-ins and the next prompts, from what the last sync left in
 * [PlannerQueries]. Called after every sync or apply that changes events (Stream B), on boot, time and zone
 * changes, at app start and by the 15-minute routine job. Safe to call as often as you like: alarms are keyed by
 * stable ids, so a reschedule replaces rather than duplicates, and the ones no longer planned are cancelled.
 *
 * [PlannerQueries] is taken through a Provider so the planner service can depend on this rescheduler without a cycle.
 */
@Singleton
class CheckInScheduler @Inject constructor(
    private val queries: Provider<PlannerQueries>,
    private val settings: SettingsStore,
    private val logs: ExecutionLogStore,
    private val alarms: CheckInAlarms,
    private val prefs: CheckInPrefs,
    private val clock: Clock,
) : CheckInRescheduler {
    private val mutex = Mutex()

    override suspend fun reschedule() {
        reschedule(force = false)
    }

    /**
     * [force] sets every upcoming alarm again: after a reboot the system has none, and after a clock or zone change
     * the same wall-clock time is a different instant. Returns false if any alarm had to be inexact.
     */
    suspend fun reschedule(force: Boolean): Boolean = mutex.withLock {
        val s = settings.current()
        val zone = s.zone
        val now = LocalDateTime.now(clock.withZone(zone))
        val today = now.toLocalDate()

        val planned: List<Pair<PlannedAlarm, String>> = try {
            val anchors = (0L..NUDGE_LOOKAHEAD_DAYS).associate { d -> today.plusDays(d).let { it to anchorsOn(it) } }
            val checkIns = (0L..1L).flatMap { d ->
                val date = today.plusDays(d)
                CheckInPlanner.plan(date, withLogs(blocksOn(date)), anchors[date].orEmpty(), s)
            }
            val nudges = NudgePlanner.next(now, s, { anchors[it].orEmpty() })
            checkIns.map { it.toAlarm() to AlarmPayload.encode(AlarmPayload.CheckInAlarm(it)) } +
                nudges.map { it.toAlarm() to AlarmPayload.encode(AlarmPayload.NudgeAlarm(it)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No plan to read (not signed in, store error): keep the alarms already set rather than cancel them.
            Log.w(TAG, "Could not read the plan; check-in alarms left as they were", e)
            return@withLock prefs.lastExact
        }

        val payloads = planned.associate { (alarm, payload) -> alarm.id to payload }
        val diff = AlarmDiff.between(prefs.alarms(), planned.map { it.first }, now, force)
        diff.cancel.forEach(alarms::cancel)
        var exact = true
        for (alarm in diff.set) {
            val millis = alarm.at.atZone(zone).toInstant().toEpochMilli()
            if (!alarms.set(alarm.id, millis, payloads.getValue(alarm.id))) exact = false
        }
        prefs.saveAlarms(diff.remembered)
        if (diff.set.isNotEmpty()) prefs.lastExact = exact
        prefs.lastExact
    }

    /** A snoozed start ping: its own id, so a reschedule (which drops past pings) leaves it alone. */
    fun snooze(checkIn: CheckIn, until: LocalDateTime, zone: java.time.ZoneId) {
        val snoozed = checkIn.copy(id = CheckIn.snoozeId(checkIn.block), at = until, shiftedFrom = null)
        alarms.set(snoozed.id, until.atZone(zone).toInstant().toEpochMilli(), AlarmPayload.encode(AlarmPayload.CheckInAlarm(snoozed)))
    }

    private suspend fun blocksOn(date: LocalDate): List<TodayBlock> {
        val q = queries.get()
        q.week(date).days.firstOrNull { it.date == date }?.let { return it.blocks }
        // An implementation may only accept week starts on Monday.
        return q.week(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))).days.firstOrNull { it.date == date }?.blocks.orEmpty()
    }

    private suspend fun anchorsOn(date: LocalDate): List<PrayerAnchor> = queries.get().anchors(date)

    /** The week view may not carry execution logs; fill them in so started and finished blocks are respected. */
    private suspend fun withLogs(blocks: List<TodayBlock>): List<TodayBlock> = blocks.map { b ->
        if (b.execution != null || b.ref == null || b.isFixed) b else b.copy(execution = logs.forEvent(b.ref!!, b.start))
    }

    private fun Nudge.toAlarm() = PlannedAlarm(id, at, "$kind|$at|$forDate")

    private companion object {
        const val TAG = "CheckInScheduler"
        const val NUDGE_LOOKAHEAD_DAYS = 8L
    }
}
