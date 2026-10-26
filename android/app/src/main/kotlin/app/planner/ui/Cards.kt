package app.planner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.planner.model.Task
import app.planner.plan.PlanSummary
import app.planner.readiness.Readiness
import app.planner.readiness.ReadinessBand
import app.planner.readiness.ReadinessState
import app.planner.sleep.HealthStatus
import app.planner.sleep.ReadinessView
import app.planner.ui.theme.LocalStatusColors
import java.time.LocalTime

private fun formatDuration(minutes: Int): String = "${minutes / 60}h ${"%02d".format(minutes % 60)}m"

@Composable
private fun bandColour(band: ReadinessBand): Color = when (band) {
    ReadinessBand.GREEN -> LocalStatusColors.current.good
    ReadinessBand.AMBER, ReadinessBand.RED -> LocalStatusColors.current.warning
}

private fun bandLoadNote(band: ReadinessBand) = when (band) {
    ReadinessBand.GREEN -> "Full plan today."
    ReadinessBand.AMBER -> "Lighter day suggested: about 20% less planned."
    ReadinessBand.RED -> "Low-energy day: only what is due within 48 hours."
}

@Composable
fun ReadinessCard(
    view: ReadinessView?,
    health: HealthStatus?,
    note: String?,
    onAllowHealth: () -> Unit,
    onRefresh: () -> Unit,
    onManualSleep: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (val state = view?.state) {
            is ReadinessState.Final -> ScoreBlock("Readiness", state.readiness, provisional = false)
            is ReadinessState.Provisional -> {
                ScoreBlock("Readiness so far", state.readiness, provisional = true)
                Text("${formatDuration(state.asleepSoFarMin)} asleep so far", color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.advice?.let {
                    Text(
                        if (it.sleepMin > 0) "Back to bed: aim for ${formatDuration(it.sleepMin)} more, alarm ${timeFormat.format(it.alarm)}."
                        else "You've had enough sleep; keep the alarm at ${timeFormat.format(it.alarm)}.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            else -> {
                Text("Readiness", style = MaterialTheme.typography.titleMedium)
                Text("Waiting for sleep data. Fitbit can take a while to reach Health Connect.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                when (health) {
                    HealthStatus.NeedsPermission -> Button(onClick = onAllowHealth) { Text("Allow Health Connect") }
                    is HealthStatus.Unavailable -> Text(health.reason, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Unit
                }
            }
        }
        note?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        view?.forecast?.let { f ->
            val usual = if (f.usual != null && f.usualLightsOut != null) {
                "; usual lights-out ${timeFormat.format(f.usualLightsOut)} gives ${f.usual.score}"
            } else {
                ""
            }
            Text("Tomorrow: lights-out at 00:15 gives ${f.onTime.score}$usual", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRefresh) { Text("Refresh") }
            TextButton(onClick = onManualSleep) { Text("Enter sleep manually") }
        }
    }
}

@Composable
private fun ScoreBlock(label: String, readiness: Readiness, provisional: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${readiness.score}", style = MaterialTheme.typography.headlineMedium, color = bandColour(readiness.band))
        Column {
            Text(label, style = MaterialTheme.typography.titleMedium)
            val i = readiness.inputs
            Text(
                "Slept ${formatDuration(i.asleepMin)} · 7-night debt ${formatDuration(i.debtMin)} · lights-out ${i.lightsOutDelayMin} min late",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (!provisional) Text(bandLoadNote(readiness.band), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun FajrCard(onSet: (LocalTime) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Fajr this week", style = MaterialTheme.typography.titleMedium)
        Text("A rough time is enough. It anchors prayer-based tasks.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { picking = true }) { Text("Set Fajr time") }
    }
    if (picking) {
        TimePickerDialog("Fajr this week", LocalTime.of(5, 30), onConfirm = { onSet(it); picking = false }, onDismiss = { picking = false })
    }
}

/** Shows what the plan couldn't do on its own: a proposed cut needing approval, overflow and tasks that don't fit. */
@Composable
fun PlanNotes(summary: PlanSummary, tasks: Map<Long, Task>, onApproveCut: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (summary.loadCut.isNotEmpty()) {
            Text("Suggested: move ${summary.loadCut.size} task(s) off today", style = MaterialTheme.typography.titleMedium)
            Text(summary.loadCut.joinToString(", ") { it.title }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onApproveCut) { Text("Lighten today") }
        }
        if (summary.overflow.isNotEmpty()) {
            Text(
                "Moved to Sunday catch-up: " + summary.overflow.joinToString(", ") { it.title } +
                    if (summary.catchUpExceeded) ". That's more than the 2h you set aside." else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        summary.pinConflicts.forEach { c ->
            Text(
                "Couldn't put ${c.task.title} at ${c.task.startAt?.let { timeFormat.format(it) }}: ${c.reason}. It was planned at the next free time.",
                color = LocalStatusColors.current.warning,
            )
        }
        val missing = summary.unscheduled.filter { it.id in tasks }
        if (missing.isNotEmpty()) {
            Text("Doesn't fit this week: " + missing.joinToString(", ") { it.title }, color = LocalStatusColors.current.warning)
        }
    }
}

@Composable
fun ManualSleepDialog(onSave: (bed: LocalTime, wake: LocalTime) -> Unit, onDismiss: () -> Unit) {
    var bed by remember { mutableStateOf(LocalTime.of(0, 30)) }
    var wake by remember { mutableStateOf(LocalTime.of(8, 0)) }
    var picking by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Last night's sleep") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Counted as asleep the whole time, so it's approximate.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { picking = "bed" }) { Text("Lights out ${timeFormat.format(bed)}") }
                OutlinedButton(onClick = { picking = "wake" }) { Text("Woke ${timeFormat.format(wake)}") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(bed, wake) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    when (picking) {
        "bed" -> TimePickerDialog("Lights out", bed, onConfirm = { bed = it; picking = null }, onDismiss = { picking = null })
        "wake" -> TimePickerDialog("Woke up", wake, onConfirm = { wake = it; picking = null }, onDismiss = { picking = null })
    }
}
