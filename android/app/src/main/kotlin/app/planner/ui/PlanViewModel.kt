package app.planner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.calendar.AllDayTask
import app.planner.calendar.AllDayTicks
import app.planner.data.TaskRepository
import app.planner.model.Task
import app.planner.model.DayTaskList
import app.planner.model.TaskStatus
import app.planner.model.Weeks
import app.planner.plan.DayView
import app.planner.plan.MoveResult
import app.planner.plan.PlanMessage
import app.planner.plan.PlanRepository
import app.planner.plan.PlanSummary
import app.planner.scheduler.Block
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CalendarMode(val label: String, val days: Int) {
    DAY("Day", 1), THREE_DAY("3 days", 3), WEEK("Week", 7)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlanViewModel @Inject constructor(
    private val plan: PlanRepository,
    private val tasks: TaskRepository,
    private val allDayTicks: AllDayTicks,
    private val clock: Clock,
) : ViewModel() {
    val mode = MutableStateFlow(CalendarMode.DAY)
    private val anchor = MutableStateFlow(LocalDate.now(clock))

    val visibleStart: StateFlow<LocalDate> = combine(mode, anchor) { m, a -> if (m == CalendarMode.WEEK) Weeks.mondayOf(a) else a }
        .stateIn(viewModelScope, SharingStarted.Eagerly, LocalDate.now(clock))

    val days: StateFlow<List<DayView>> = combine(mode, visibleStart) { m, start -> start to m.days }
        .flatMapLatest { (start, count) -> plan.dayViews(start, start.plusDays(count - 1L)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tasksById: StateFlow<Map<Long, Task>> = tasks.tasks.map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val scheduledBlocks: StateFlow<List<Block>> = plan.dayViews(LocalDate.now(clock), LocalDate.now(clock).plusDays(PlanRepository.HORIZON_DAYS - 1L))
        .map { views -> views.flatMap { it.blocks } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val todayTasks: StateFlow<List<Task>> = combine(tasks.tasks, scheduledBlocks, plan.summary) { list, blocks, summary ->
        val today = LocalDate.now(clock)
        DayTaskList.on(today, today, clock.zone, list, blocks, summary.overflow.map { it.id }.toSet()).all
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Today's all-day calendar items, as tasks to tick off. */
    val todayAllDay: StateFlow<List<AllDayTask>> = plan.dayViews(LocalDate.now(clock), LocalDate.now(clock))
        .map { views -> views.flatMap { it.allDayTasks } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Today's block per task, for the suggested times on the Today list. */
    val todayBlocks: StateFlow<Map<Long, Block>> = plan.dayViews(LocalDate.now(clock), LocalDate.now(clock))
        .map { views -> views.flatMap { it.blocks }.mapNotNull { b -> b.taskId?.let { it to b } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val summary: StateFlow<PlanSummary> = plan.summary
    val syncProblem: StateFlow<String?> = plan.syncProblem

    private val _messages = MutableSharedFlow<PlanMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<PlanMessage> = _messages.asSharedFlow()

    init {
        // A fresh install or a new week has no plan yet; build one so the calendar isn't empty.
        viewModelScope.launch {
            val today = LocalDate.now(clock)
            val hasPlan = plan.dayViews(today, today.plusDays(PlanRepository.HORIZON_DAYS - 1L)).first().any { it.blocks.isNotEmpty() }
            if (!hasPlan) plan.replan()
        }
    }

    fun setMode(m: CalendarMode) {
        mode.value = m
    }

    fun shift(direction: Int) {
        anchor.value = visibleStart.value.plusDays((direction * mode.value.days).toLong())
    }

    fun goToToday() {
        anchor.value = LocalDate.now(clock)
    }

    fun goToDate(date: LocalDate) { anchor.value = date }

    fun replan() = viewModelScope.launch {
        val summary = plan.replan()
        _messages.emit(PlanMessage(if (summary.unscheduled.isEmpty()) "Plan updated" else "Plan updated. ${summary.unscheduled.size} task(s) don't fit this week"))
    }

    fun approveLoadCut() = viewModelScope.launch { plan.approveLoadCut() }

    fun move(blockId: Long, date: LocalDate, startMin: Int, endMin: Int) = viewModelScope.launch {
        when (val r = plan.move(blockId, date, startMin, endMin)) {
            is MoveResult.Applied -> _messages.emit(PlanMessage(listOf("Moved").plus(r.warnings).joinToString(". "), r.changeId))
            is MoveResult.Rejected -> _messages.emit(PlanMessage(r.reasons.firstOrNull() ?: "That can't go there"))
        }
    }

    fun toggleLock(block: Block) = viewModelScope.launch {
        plan.setLocked(block.id, !block.locked)
        _messages.emit(PlanMessage(if (block.locked) "Unlocked" else "Locked: the planner will keep it here"))
    }

    fun removeFromPlan(block: Block) = viewModelScope.launch {
        plan.unschedule(block.id)?.let { _messages.emit(PlanMessage("Removed from the plan", it)) }
    }

    fun toggleDone(task: Task) = viewModelScope.launch {
        tasks.setStatus(task.id, if (task.status == TaskStatus.DONE) TaskStatus.OPEN else TaskStatus.DONE)
    }

    fun toggleAllDay(task: AllDayTask) = viewModelScope.launch { allDayTicks.toggle(task) }

    fun undo(changeId: Long) = viewModelScope.launch {
        if (plan.undo(changeId)) _messages.emit(PlanMessage("Undone"))
    }
}
