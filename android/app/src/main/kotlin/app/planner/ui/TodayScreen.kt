package app.planner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.model.Task
import app.planner.model.TaskStatus
import app.planner.scheduler.Block
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun TodayScreen(
    tasksVm: TasksViewModel,
    calVm: CalendarViewModel,
    planVm: PlanViewModel,
    readinessVm: ReadinessViewModel,
    onConnect: () -> Unit,
    onAllowHealth: () -> Unit,
    onEdit: (Task) -> Unit,
    onAdd: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val tasks by tasksVm.today.collectAsStateWithLifecycle()
    val today by calVm.today.collectAsStateWithLifecycle()
    val sync by calVm.sync.collectAsStateWithLifecycle()
    val lastSynced by calVm.lastSyncedAt.collectAsStateWithLifecycle()
    val blocks by planVm.todayBlocks.collectAsStateWithLifecycle()
    val summary by planVm.summary.collectAsStateWithLifecycle()
    val allTasks by planVm.tasksById.collectAsStateWithLifecycle()
    val readiness by readinessVm.view.collectAsStateWithLifecycle()
    val health by readinessVm.health.collectAsStateWithLifecycle()
    val note by readinessVm.syncNote.collectAsStateWithLifecycle()
    val fajr by readinessVm.fajrThisWeek.collectAsStateWithLifecycle()
    var manualSleep by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(LocalDate.now(zone).format(dayHeaderFormat), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        }

        item {
            ReadinessCard(
                view = readiness,
                health = health,
                note = note,
                onAllowHealth = onAllowHealth,
                onRefresh = readinessVm::syncSleep,
                onManualSleep = { manualSleep = true },
            )
        }
        if (fajr == null) item { FajrCard(onSet = readinessVm::saveFajr) }

        if (lastSynced == null || sync is SyncUi.NeedsConsent || sync is SyncUi.Failed) {
            item { ConnectCard(sync, connected = lastSynced != null, onConnect = onConnect) }
        }

        if (today.allDay.isNotEmpty()) {
            item { Text(today.allDay.joinToString(" · ") { it.title }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        if (today.fixed.isNotEmpty()) {
            item { SectionTitle("Fixed today") }
            items(today.fixed, key = { it.calendarId + it.id }) { e ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${timeFormat.format(e.start.atZone(zone))}–${timeFormat.format(e.end.atZone(zone))}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(e.title)
                }
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
        }

        item { RoutineCards() }

        item { PlanNotes(summary, allTasks, onApproveCut = planVm::approveLoadCut) }

        item { SectionTitle("Tasks") }
        if (tasks.isEmpty()) {
            item { Text("Nothing due. Add a task, or generate this week from the weekly template in Settings.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(tasks, key = { it.id }) { task ->
            TaskRow(task, zone, blocks[task.id], onToggle = { tasksVm.toggleDone(task) }, onClick = { onEdit(task) })
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                Button(onClick = onAdd) { Text("Add task") }
                OutlinedButton(onClick = planVm::replan) { Text("Replan") }
            }
        }
    }

    if (manualSleep) {
        ManualSleepDialog(
            onSave = { bed, wake -> readinessVm.addManualSleep(bed, wake); manualSleep = false },
            onDismiss = { manualSleep = false },
        )
    }
}

@Composable
fun TaskRow(task: Task, zone: ZoneId, block: Block?, onToggle: () -> Unit, onClick: () -> Unit) {
    val done = task.status == TaskStatus.DONE
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = done, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(taskMeta(task, zone), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (block != null) {
            Text(
                "${timeFormat.format(block.start)}–${timeFormat.format(block.end)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun ConnectCard(sync: SyncUi, connected: Boolean, onConnect: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Text(if (connected) "Google Calendar needs reconnecting" else "Connect Google Calendar", style = MaterialTheme.typography.titleMedium)
        Text(
            when (sync) {
                is SyncUi.Failed -> sync.message
                else -> "Read-only access to your calendars gives the planner your classes and commitments to work around."
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onConnect, enabled = sync !is SyncUi.Syncing) { Text(if (sync is SyncUi.Syncing) "Connecting…" else if (connected) "Reconnect" else "Connect") }
    }
}
