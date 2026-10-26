package app.planner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.planner.data.SettingsRepository
import app.planner.data.TaskRepository
import app.planner.model.Task
import app.planner.model.TaskStatus
import app.planner.model.TemplateItem
import app.planner.model.WeeklyTemplate
import app.planner.model.tasksForToday
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {
    val today: StateFlow<List<Task>> = tasks.tasks
        .map { tasksForToday(it, LocalDate.now(clock), clock.zone) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Everything not dropped: open tasks by due date first, then done. */
    val all: StateFlow<List<Task>> = tasks.tasks
        .map { list ->
            list.filter { it.status != TaskStatus.DROPPED }
                .sortedWith(compareBy<Task> { it.status == TaskStatus.DONE }.thenBy { it.dueAt }.thenBy { it.title.lowercase() })
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val template: StateFlow<WeeklyTemplate> = settings.template
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeeklyTemplate())

    /** Message from the last "generate" action, shown once. */
    private val _generated = MutableStateFlow<String?>(null)
    val generated: StateFlow<String?> = _generated

    fun save(task: Task) = viewModelScope.launch { tasks.save(task) }

    fun toggleDone(task: Task) = viewModelScope.launch {
        tasks.setStatus(task.id, if (task.status == TaskStatus.DONE) TaskStatus.OPEN else TaskStatus.DONE)
    }

    fun drop(task: Task) = viewModelScope.launch { tasks.setStatus(task.id, TaskStatus.DROPPED) }

    fun saveTemplateItem(item: TemplateItem) = viewModelScope.launch {
        val current = template.value.items
        val next = if (current.any { it.id == item.id }) current.map { if (it.id == item.id) item else it } else current + item
        settings.saveTemplate(WeeklyTemplate(next))
    }

    fun deleteTemplateItem(id: String) = viewModelScope.launch {
        settings.saveTemplate(WeeklyTemplate(template.value.items.filterNot { it.id == id }))
    }

    fun generateWeek(weeksAhead: Long) = viewModelScope.launch {
        val created = tasks.generateFromTemplate(template.value, weeksAhead)
        _generated.value = if (created == 0) "Nothing new: those tasks already exist" else "Added $created task${if (created == 1) "" else "s"}"
    }

    fun clearGenerated() {
        _generated.value = null
    }
}
