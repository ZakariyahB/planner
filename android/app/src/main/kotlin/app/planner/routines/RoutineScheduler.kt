package app.planner.routines

import app.planner.data.DayRulesRepository
import android.content.Context
import app.planner.plan.PlanRepository
import app.planner.scheduler.DayRules
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** Puts the next trigger of every enabled timed routine on AlarmManager, and cancels the switched-off ones. */
@Singleton
class RoutineScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val plan: PlanRepository,
    private val routines: RoutineSettings,
    private val dayRules: DayRulesRepository,
    private val clock: Clock,
) {
    /**
     * Safe to run as often as you like: an alarm that is set again just replaces itself. Called after every
     * routine fires, at boot, on a time zone change and by the 15-minute job, so a packed day or a changed
     * switch is picked up within minutes. Returns false if any alarm had to be inexact.
     */
    suspend fun rescheduleAll(): Boolean {
        val now = clock.instant()
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val fixedByDay = try {
            plan.dayViews(today, today.plusDays(LOOKAHEAD_DAYS)).first()
                .associate { it.date to RoutineSchedule.fixedMinutesOn(it.date, zone, it.fixed) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyMap() // no calendar data: treat every day as ordinary rather than not scheduling at all
        }
        val rules = dayRules.current()
        var allExact = true
        for (routine in Routine.TIMED) {
            if (!routines.enabled(routine)) {
                AlarmClock.cancel(context, routine)
                continue
            }
            val at = RoutineSchedule.next(routine, now, zone, rules) { fixedByDay[it] ?: 0 }
            if (!AlarmClock.set(context, routine, at)) allExact = false
        }
        return allExact
    }

    private companion object {
        const val LOOKAHEAD_DAYS = 8L
    }
}
