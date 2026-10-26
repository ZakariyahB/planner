package app.planner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.BuildConfig
import app.planner.ai.AiSpendRepository
import app.planner.sleep.HealthStatus
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

@Composable
private fun Page(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun CalendarsPage(calVm: CalendarViewModel, planVm: PlanViewModel, onConnect: () -> Unit) {
    val calendars by calVm.calendars.collectAsStateWithLifecycle()
    val selected by calVm.selected.collectAsStateWithLifecycle()
    val sync by calVm.sync.collectAsStateWithLifecycle()
    val lastSynced by calVm.lastSyncedAt.collectAsStateWithLifecycle()
    val problem by planVm.syncProblem.collectAsStateWithLifecycle()

    Page {
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
                if (lastSynced == null || sync is SyncUi.NeedsConsent) {
                    Button(onClick = onConnect) { Text("Connect") }
                } else {
                    OutlinedButton(onClick = calVm::refresh, enabled = sync !is SyncUi.Syncing) { Text("Sync now") }
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
                Row(Modifier.fillMaxWidth().clickable { calVm.setCalendarSelected(cal.id, !on) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = on, onCheckedChange = { calVm.setCalendarSelected(cal.id, it) })
                    Text(cal.name)
                }
            }
        }
        item { SectionTitle("Planner calendar") }
        item {
            Text(
                problem ?: "Planned blocks are mirrored to a calendar called Planner. Times you change there are read back into the plan.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun SleepPage(readinessVm: ReadinessViewModel, onAllowHealth: () -> Unit) {
    val health by readinessVm.health.collectAsStateWithLifecycle()
    val fajr by readinessVm.fajrThisWeek.collectAsStateWithLifecycle()
    var pickingFajr by remember { mutableStateOf(false) }

    Page {
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
        item { SectionTitle("Fajr") }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This week: ${fajr?.let { timeFormat.format(it) } ?: "not set"}")
                OutlinedButton(onClick = { pickingFajr = true }) { Text(if (fajr == null) "Set" else "Change") }
            }
        }
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

@Composable
fun AssistantPage(chatVm: ChatViewModel) {
    val spend by chatVm.spendGbp.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Page {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (BuildConfig.WORKER_URL.isBlank() || BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
                    Text(
                        "Not set up yet: add WORKER_URL and GOOGLE_WEB_CLIENT_ID to android/local.properties and rebuild.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "AI spend this month: " + String.format(Locale.UK, "£%.2f of £%.0f", spend, AiSpendRepository.CAP_GBP),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = { chatVm.signIn(context) }) { Text("Sign in for the assistant") }
                }
                Text(
                    "The assistant sees summaries of your plan, tasks and sleep, never raw sleep data. Planning, ticking and the calendar work without it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
