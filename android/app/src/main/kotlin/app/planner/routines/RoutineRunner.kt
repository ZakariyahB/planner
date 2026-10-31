package app.planner.routines

import app.planner.data.DayRulesRepository
import android.util.Log
import app.planner.data.SettingsRepository
import app.planner.engine.ports.CheckInRescheduler
import app.planner.engine.ports.PlannerQueries
import app.planner.model.Weeks
import app.planner.readiness.BackToBedAdvice
import app.planner.readiness.ReadinessEngine
import app.planner.readiness.ReadinessInputs
import app.planner.readiness.ReadinessState
import app.planner.sleep.ReadinessRepository
import app.planner.sleep.SleepRepository
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * What each routine does when it is due. The workers and receivers only call in here; the decisions live in
 * app.planner.routines core (RoutineTriggers, NotificationTexts). No step needs the network, the AI, a permission
 * or Health Connect data to be present: a missing one means that step is skipped.
 *
 * Planner v2: the routines that planned or replanned on their own (work stops, tomorrow's plan, the Sunday review,
 * the Monday catch-up, replanning on new sleep, "Lighten today") are retired. Every change to the plan is now a
 * ChangeSet the user approves, and the check-ins and prompts in app.planner.checkin replace those notifications.
 * What stays is sleep coaching: back to bed, the morning brief and the lights-out warning.
 */
@Singleton
class RoutineRunner @Inject constructor(
    private val settings: SettingsRepository,
    private val readiness: ReadinessRepository,
    private val sleep: SleepRepository,
    private val routines: RoutineSettings,
    private val notifier: Notifier,
    private val scheduler: RoutineScheduler,
    private val dayRules: DayRulesRepository,
    private val planner: Provider<PlannerQueries>,
    private val checkIns: Provider<CheckInRescheduler>,
    private val clock: Clock,
) {
    suspend fun runJob(job: String?) {
        when (job) {
            RoutineWork.JOB_RESCHEDULE -> {
                routines.ensureStarted()
                scheduler.rescheduleAll()
            }
            RoutineWork.JOB_MORNING_UNLOCK -> guard("unlock") { morning(awakeConfirmed = true) }
            RoutineWork.JOB_LIGHTEN -> notifier.cancel(Routine.MORNING_BRIEF) // a button left on an old notification
            else -> Routine.parse(job)?.takeIf { it.timed }?.let { fire(it) }
        }
    }

    /** A timed routine is due. Whatever happens, the next occurrence gets its alarm (retired ones are cancelled). */
    suspend fun fire(routine: Routine) {
        try {
            if (!routine.retired && routines.enabled(routine)) {
                when (routine) {
                    Routine.LIGHTS_OUT -> lightsOut()
                    else -> Unit
                }
            }
        } finally {
            withContext(NonCancellable) { guard("reschedule") { scheduler.rescheduleAll() } }
        }
    }

    /**
     * Settings' "Send now": a timed routine runs for real. The two driven by last night's sleep need a night of
     * data to fire on their own, so they post a sample built from made-up numbers, marked as a test.
     * Retired routines send nothing.
     */
    suspend fun sendNow(routine: Routine) {
        if (routine.retired) return
        if (routine.timed) {
            guard("send now") { fire(routine) }
            return
        }
        val text = when (routine) {
            Routine.PROVISIONAL_READINESS -> {
                val readiness = ReadinessEngine.score(ReadinessInputs(asleepMin = 210, debtMin = 300, lightsOutDelayMin = 72, doneRatio = 0.8))
                NotificationTexts.provisionalReadiness(210, readiness, BackToBedAdvice(210, dayRules.current().wake))
            }
            else -> {
                val readiness = ReadinessEngine.score(ReadinessInputs(asleepMin = 300, debtMin = 360, lightsOutDelayMin = 72, doneRatio = 0.7))
                NotificationTexts.morningBrief(readiness, DayOutline(5, LocalTime.of(9, 30), "PintOS"), loadCutCount = 0)
            }
        }
        notifier.post(routine, text.copy(title = "Test: ${text.title}"))
    }

    /** The 15-minute job. Each step is independent: a failure in one never stops the others. */
    suspend fun periodic() {
        routines.ensureStarted()
        guard("sleep sync") { sleep.syncFromHealthConnect() }
        guard("provisional readiness") { provisional() }
        guard("morning brief fallback") { morning(awakeConfirmed = false) }
        guard("alarms") { scheduler.rescheduleAll() }
        // A safety net: syncs reschedule check-ins themselves, but a missed one is caught within 15 minutes.
        guard("check-ins") { checkIns.get().reschedule() }
    }

    // Timed routines

    private suspend fun lightsOut() {
        val rules = dayRules.current()
        notifier.post(Routine.LIGHTS_OUT, NotificationTexts.lightsOutSoon(rules.lightsOut, rules.wake))
    }

    // Event-driven routines

    /** First sleep block ended near Fajr and he may still go back to bed. */
    private suspend fun provisional() {
        if (!routines.enabled(Routine.PROVISIONAL_READINESS)) return
        val today = LocalDate.now(clock)
        val sentOn = routines.sentOn(Routine.PROVISIONAL_READINESS)
        if (sentOn == today) return
        val view = readiness.evaluate()
        val state = view.state as? ReadinessState.Provisional ?: return
        val fajr = settings.fajrWeeks.first()[Weeks.mondayOf(today)]
        if (!RoutineTriggers.provisionalDue(view.night, clock.instant(), clock.zone, fajr, dayRules.current().wake, sentOn)) return
        notifier.post(
            Routine.PROVISIONAL_READINESS,
            NotificationTexts.provisionalReadiness(state.asleepSoFarMin, state.readiness, state.advice),
        )
        routines.markSent(Routine.PROVISIONAL_READINESS, today)
    }

    /**
     * Final readiness and the morning brief, once a day: after he unlocks the phone past 07:00, or from 10:00.
     * No final score (no sleep data) means no brief; it is tried again at the next 15-minute run until 14:00.
     * The brief describes today's plan as it is on the calendar; it no longer replans or proposes a lighter day.
     */
    suspend fun morning(awakeConfirmed: Boolean) {
        val today = LocalDate.now(clock)
        if (!RoutineTriggers.morningBriefDue(clock.instant(), clock.zone, routines.sentOn(Routine.MORNING_BRIEF), awakeConfirmed)) return

        if (awakeConfirmed) guard("sleep sync") { sleep.syncFromHealthConnect() }
        val view = readiness.evaluate(awakeConfirmed)
        val final = view.state as? ReadinessState.Final ?: return

        if (routines.enabled(Routine.MORNING_BRIEF)) {
            notifier.post(Routine.MORNING_BRIEF, NotificationTexts.morningBrief(final.readiness, todayOutline(), loadCutCount = 0))
        }
        routines.markSent(Routine.MORNING_BRIEF, today)
    }

    /** Today from the v2 planner: task blocks, the first one, fixed events and unscheduled template tasks. */
    private suspend fun todayOutline(): DayOutline = try {
        val state = planner.get().observeToday().first()
        val tasks = state.blocks.filter { !it.isFixed }.sortedBy { it.start }
        val first = tasks.firstOrNull()
        DayOutline(
            taskCount = tasks.size,
            firstStart = first?.start?.toLocalTime(),
            firstTitle = first?.let { listOfNotNull(it.title, it.chunkLabel).joinToString(" ") },
            fixedCount = state.blocks.count { it.isFixed },
            unplacedCount = state.unscheduled.size,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "No plan for the morning brief", e)
        DayOutline(0, null, null)
    }

    private suspend fun guard(label: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "$label failed", e)
        }
    }

    private companion object {
        const val TAG = "RoutineRunner"
    }
}
