package app.planner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.model.Task
import java.time.ZoneId

@Composable
fun TasksScreen(tasksVm: TasksViewModel, planVm: PlanViewModel, onEdit: (Task) -> Unit, onAdd: () -> Unit) {
    val zone = ZoneId.systemDefault()
    val tasks by tasksVm.all.collectAsStateWithLifecycle()
    val blocks by planVm.todayBlocks.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("All tasks", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp)) }
        if (tasks.isEmpty()) item { Text("No tasks yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(tasks, key = { it.id }) { task ->
            TaskRow(task, zone, blocks[task.id], onToggle = { tasksVm.toggleDone(task) }, onClick = { onEdit(task) })
        }
        item { Button(onClick = onAdd, modifier = Modifier.padding(vertical = 8.dp)) { Text("Add task") } }
    }
}
