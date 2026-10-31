package app.planner.routines

import app.planner.data.DayRulesRepository
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts the next trigger of every enabled timed routine on AlarmManager, and cancels the switched-off and retired
 * ones (those replaced by the v2 check-ins and prompts in app.planner.checkin).
 */
@Singleton
class RoutineScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val routines: RoutineSettings,
    private val dayRules: DayRulesRepository,
    private val clock: Clock,
) {
    /**
     * Safe to run as often as you like: an alarm that is set again just replaces itself. Called after every
     * routine fires, at boot, on a time zone change and by the 15-minute job. Returns false if any alarm had to be
     * inexact.
     */
    suspend fun rescheduleAll(): Boolean {
        val now = clock.instant()
        val zone = clock.zone
        val rules = dayRules.current()
        var allExact = true
        for (routine in Routine.TIMED) {
            if (routine.retired || !routines.enabled(routine)) {
                AlarmClock.cancel(context, routine)
                continue
            }
            // Only the retired work-stops routine depended on the day's fixed events; the rest need no calendar.
            val at = RoutineSchedule.next(routine, now, zone, rules)
            if (!AlarmClock.set(context, routine, at)) allExact = false
        }
        return allExact
    }
}
