package app.planner.routines

import app.planner.data.DayRulesRepository
import android.content.Context
import android.util.Log
import app.planner.data.BlockDao
import app.planner.data.SettingsRepository
import app.planner.data.TaskRepository
import app.planner.data.toBlock
import app.planner.model.Task
import app.planner.model.TaskStatus
import app.planner.model.Weeks
import app.planner.plan.PlanRepository
import app.planner.plan.PlanSummary
import app.planner.readiness.BackToBedAdvice
import app.planner.readiness.ReadinessBand
import app.planner.readiness.ReadinessEngine
import app.planner.readiness.ReadinessInputs
import app.planner.readiness.ReadinessState
import app.planner.scheduler.Block
import app.planner.scheduler.BlockKind
import app.planner.scheduler.DayRules
import app.planner.sleep.ReadinessRepository
import app.planner.sleep.SleepRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * What each routine does when it is due. The workers and receivers only call in here; the decisions live in
 * app.planner.routines core (RoutineTriggers, NotificationTexts, SundayReview). No step needs the network, the
 * AI, a permission or Health Connect data to be present: a missing one means that step is skipped.
 */
@Singleton
class RoutineRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val plan: PlanRepository,
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
    private val readiness: ReadinessRepository,
    private val sleep: SleepRepository,
    private val routines: RoutineSettings,
    private val notifier: Notifier,
    private val scheduler: RoutineScheduler,
    private val blocks: BlockDao,
    private val planRows: TomorrowPlanRepository,
    private val planNotes: PlanNoteWriter,
    private val reviews: SundayReviewRepository,
    private val dayRules: DayRulesRepository,
    private val clock: Clock,
) {
    suspend fun runJob(job: String?) {
        when (job) {
            RoutineWork.JOB_RESCHEDULE -> {
                routines.ensureStarted()
                scheduler.rescheduleAll()
            }
            RoutineWork.JOB_MORNING_UNLOCK -> guard("unlock") { morning(awakeConfirmed = true) }
            RoutineWork.JOB_LIGHTEN -> lighten()
            else -> Routine.parse(job)?.takeIf { it.timed }?.let { fire(it) }
        }
    }

    /** A timed routine is due. Whatever happens, the next occurrence gets its alarm. */
    suspend fun fire(routine: Routine) {
        try {
            if (routines.enabled(routine)) {
                when (routine) {
                    Routine.WORK_STOPS -> workStops()
                    Routine.PLAN_READY -> planReady()
                    Routine.LIGHTS_OUT -> lightsOut()
                    Routine.SUNDAY_REVIEW -> sundayReview()
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
     */
    suspend fun sendNow(routine: Routine) {
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
                NotificationTexts.morningBrief(readiness, DayOutline(5, LocalTime.of(9, 30), "PintOS"), loadCutCount = 2)
            }
        }
        notifier.post(routine, text.copy(title = "Test: ${text.title}"))
    }

    /** The 15-minute job. Each step is independent: a failure in one never stops the others. */
    suspend fun periodic() {
        routines.ensureStarted()
        guard("sleep sync") { sleep.syncFromHealthConnect() }
        guard("replan on new sleep") { plan.replanIfSleepChanged() }
        guard("provisional readiness") { provisional() }
        guard("morning brief fallback") { morning(awakeConfirmed = false) }
        guard("monday catch-up") { mondayCatchUp() }
        guard("alarms") { scheduler.rescheduleAll() }
    }

    // Timed routines

    private suspend fun workStops() {
        val today = LocalDate.now(clock)
        val planned = tasksPlannedOn(today)
        val unfinished = planned.filter { it.status == TaskStatus.OPEN || it.status == TaskStatus.PARTIAL }
        // Work has stopped: what was not done is planned again, and the notice says where it went.
        if (unfinished.isNotEmpty()) guard("replan at work stop") { plan.replan() }
        val after = blocks.fromDate(today.toString()).map { it.toBlock() }.filter { it.kind == BlockKind.TASK }
        val moved = unfinished.mapNotNull { task ->
            val next = after.filter { it.taskId == task.id }.minByOrNull { it.date }
            when {
                next == null -> MovedTask(task.title, null)
                next.date == today -> null // in progress or locked: it did not move
                else -> MovedTask(task.title, next.date)
            }
        }
        val summary = WorkStopsSummary(done = planned.count { it.status == TaskStatus.DONE }, planned = planned.size, moved = moved, today = today)
        notifier.post(Routine.WORK_STOPS, NotificationTexts.workStops(summary))
    }

    private suspend fun planReady() {
        val today = LocalDate.now(clock)
        val date = today.plusDays(1)
        val rows = planRows.rowsOn(date)
        val outline = TomorrowPlan.outline(rows, emptySet())
        val forecast = forecastNote()
        val note = planNotes.write(date, rows, forecast) // null if the AI is unavailable; the text stands on its own
        notifier.post(Routine.PLAN_READY, NotificationTexts.planReady(outline, forecast), LaunchTarget.TOMORROW_PLAN, extraLine = note)
        routines.markSent(Routine.PLAN_READY, today)
    }

    private suspend fun lightsOut() {
        val rules = dayRules.current()
        notifier.post(Routine.LIGHTS_OUT, NotificationTexts.lightsOutSoon(rules.lightsOut, rules.wake))
    }

    private suspend fun sundayReview() {
        val monday = Weeks.mondayOf(LocalDate.now(clock))
        val report = reviews.report(monday)
        notifier.post(
            Routine.SUNDAY_REVIEW,
            NotificationTexts.sundayReview(report.plannedMin, report.doneMin),
            LaunchTarget.SUNDAY_REVIEW,
        )
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
     */
    suspend fun morning(awakeConfirmed: Boolean) {
        val today = LocalDate.now(clock)
        if (!RoutineTriggers.morningBriefDue(clock.instant(), clock.zone, routines.sentOn(Routine.MORNING_BRIEF), awakeConfirmed)) return

        if (awakeConfirmed) guard("sleep sync") { sleep.syncFromHealthConnect() }
        val view = readiness.evaluate(awakeConfirmed)
        val final = view.state as? ReadinessState.Final ?: return

        // The day's load is now fixed: plan again. A lighter band also needs the proposed cut for the notice.
        var summary: PlanSummary? = null
        if (view.newlyFinal || final.readiness.band != ReadinessBand.GREEN) summary = plan.replan()

        if (routines.enabled(Routine.MORNING_BRIEF)) {
            val outline = TomorrowPlan.outline(planRows.rowsOn(today), emptySet())
            val cut = summary?.loadCut?.size ?: 0
            notifier.post(
                Routine.MORNING_BRIEF,
                NotificationTexts.morningBrief(final.readiness, outline, cut),
                LaunchTarget.TODAY,
                actions = if (cut > 0) listOf(RoutineActionReceiver.lightenAction(context)) else emptyList(),
            )
        }
        routines.markSent(Routine.MORNING_BRIEF, today)
    }

    /** The "Lighten today" button: approve the proposed cut. */
    private suspend fun lighten() {
        plan.approveLoadCut()
        notifier.cancel(Routine.MORNING_BRIEF)
    }

    /** A skipped Sunday check-in still gets the week generated from the template. Safe to repeat. */
    private suspend fun mondayCatchUp() {
        val week = SundayReview.catchUpWeek(LocalDate.now(clock), routines.generatedWeeks()) ?: return
        val created = tasks.generateFromTemplate(settings.template.first(), weeksAhead = 0)
        routines.markWeekGenerated(week)
        if (created > 0) plan.replan()
    }

    // Helpers

    private suspend fun forecastNote(): ForecastNote? {
        val f = readiness.evaluate().forecast ?: return null
        return ForecastNote(dayRules.current().lightsOut, f.onTime.score, f.usualLightsOut, f.usual?.score)
    }

    /** Tasks that had a block on [date], once each, with their current status. */
    private suspend fun tasksPlannedOn(date: LocalDate): List<Task> {
        val byId = tasks.snapshot().associateBy { it.id }
        return blocks.between(date.toString(), date.toString()).map { it.toBlock() }
            .filter { it.kind == BlockKind.TASK }
            .mapNotNull { b: Block -> b.taskId?.let(byId::get) }
            .distinctBy { it.id }
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
