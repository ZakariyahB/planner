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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.sleep.HealthStatus
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun SettingsScreen(
    calVm: CalendarViewModel,
    readinessVm: ReadinessViewModel,
    planVm: PlanViewModel,
    onConnect: () -> Unit,
    onAllowHealth: () -> Unit,
    onOpenTemplate: () -> Unit,
) {
    val calendars by calVm.calendars.collectAsStateWithLifecycle()
    val selected by calVm.selected.collectAsStateWithLifecycle()
    val sync by calVm.sync.collectAsStateWithLifecycle()
    val lastSynced by calVm.lastSyncedAt.collectAsStateWithLifecycle()
    val health by readinessVm.health.collectAsStateWithLifecycle()
    val fajr by readinessVm.fajrThisWeek.collectAsStateWithLifecycle()
    val problem by planVm.syncProblem.collectAsStateWithLifecycle()
    var pickingFajr by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp)) }

        item { SectionTitle("Google Calendar") }
        item {
            val state = sync
            val synced = lastSynced
            val status = when {
                state is SyncUi.Syncing -> "Syncing…"
                state is SyncUi.Failed -> state.message
                state is SyncUi.NeedsConsent -> "Access needs approving again"
                synced != null -> "Connected · last synced ${synced.atZone(ZoneId.systemDefault()).format(syncFormat)}"
                else -> "Not connected"
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (lastSynced == null || sync is SyncUi.NeedsConsent) {
                        Button(onClick = onConnect) { Text("Connect") }
                    } else {
                        OutlinedButton(onClick = calVm::refresh, enabled = sync !is SyncUi.Syncing) { Text("Sync now") }
                    }
                }
            }
        }

        if (calendars.isNotEmpty()) {
            item {
                Text(
                    "Ticked calendars supply fixed commitments (timed events only). Untick any you don't attend live.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(calendars, key = { it.id }) { cal ->
                val on = cal.id in selected
                Row(
                    Modifier.fillMaxWidth().clickable { calVm.setCalendarSelected(cal.id, !on) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = { calVm.setCalendarSelected(cal.id, it) })
                    Text(cal.name)
                }
            }
        }

        item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
        item { SectionTitle("Planner calendar") }
        item {
            Text(
                problem ?: "Planned blocks are mirrored to a calendar called Planner. Times you change there are read back into the plan.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
        item { SectionTitle("Sleep") }
        item {
            val status = when (val h = health) {
                HealthStatus.Ready -> "Health Connect connected"
                HealthStatus.NeedsPermission -> "Health Connect needs your permission to read sleep"
                is HealthStatus.Unavailable -> h.reason
                null -> "Checking Health Connect…"
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (health == HealthStatus.NeedsPermission) Button(onClick = onAllowHealth) { Text("Allow") }
                    OutlinedButton(onClick = readinessVm::syncSleep) { Text("Sync sleep now") }
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Fajr this week: ${fajr?.let { timeFormat.format(it) } ?: "not set"}")
                OutlinedButton(onClick = { pickingFajr = true }) { Text(if (fajr == null) "Set" else "Change") }
            }
        }

        item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
        item { SectionTitle("Tasks") }
        item { OutlinedButton(onClick = onOpenTemplate) { Text("Weekly template") } }
    }

    if (pickingFajr) {
        TimePickerDialog(
            title = "Fajr this week",
            initial = fajr ?: LocalTime.of(5, 30),
            onConfirm = { readinessVm.saveFajr(it); pickingFajr = false },
            onDismiss = { pickingFajr = false },
        )
    }
}
