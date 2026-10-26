package app.planner.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.ui.SectionTitle

/** The Social time block of Settings: access, daily budgets, notifications and which apps count. */
@Composable
fun SocialSettingsSection(vm: SocialViewModel = hiltViewModel()) {
    val granted by vm.accessGranted.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    val budget by vm.budget.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val extras by vm.extraApps.collectAsStateWithLifecycle()
    val nudgesOn by vm.nudgesOn.collectAsStateWithLifecycle()
    val summaryOn by vm.summaryOn.collectAsStateWithLifecycle()
    val notificationsAllowed by vm.notificationsAllowed.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadInstalledApps() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SectionTitle("Social time")

        Text(
            if (granted) "Usage access is on" else "Usage access is off, so social time can't be counted.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!granted) OutlinedButton(onClick = vm::openAccessSettings) { Text("Open usage access settings") }
        if (note != null) Text(note.orEmpty(), color = MaterialTheme.colorScheme.error)

        Text("Daily budget", style = MaterialTheme.typography.titleSmall)
        BudgetRow("Weekdays", budget.weekdayMin, onSave = vm::setWeekdayBudget)
        BudgetRow("Weekends", budget.weekendMin, onSave = vm::setWeekendBudget)

        Text("Notifications", style = MaterialTheme.typography.titleSmall)
        SwitchRow("Nudges at 50%, 80% and 100% of the budget", nudgesOn, vm::setNudgesOn)
        SwitchRow("Summary of the day at 21:30", summaryOn, vm::setSummaryOn)
        if (!notificationsAllowed) {
            Text(
                "Notifications are turned off for Planner in system settings, so these won't show.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text("Apps that count", style = MaterialTheme.typography.titleSmall)
        DefaultSocialApps.all.forEach { app ->
            val on = selection.isDefaultEnabled(app.id)
            Row(Modifier.fillMaxWidth().clickable { vm.setDefaultApp(app.id, !on) }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = on, onCheckedChange = { vm.setDefaultApp(app.id, it) })
                Text(app.name)
            }
        }
        extras.forEach { app ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(app.label, Modifier.weight(1f).padding(start = 12.dp))
                TextButton(onClick = { vm.removeApp(app.pkg) }) { Text("Remove") }
            }
        }
        OutlinedButton(onClick = { picking = true }) { Text("Add another app") }
    }

    if (picking) {
        AppPickerDialog(vm, onDismiss = { picking = false })
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Hours and minutes fields; Save is offered once what is typed is a valid budget that differs from the saved one. */
@Composable
private fun BudgetRow(label: String, minutes: Int, onSave: (Int) -> Unit) {
    val (savedHours, savedMinutes) = BudgetInput.split(minutes)
    var hours by remember(minutes) { mutableStateOf(savedHours.toString()) }
    var mins by remember(minutes) { mutableStateOf(savedMinutes.toString()) }
    val typed = BudgetInput.toMinutes(hours, mins)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = hours,
                onValueChange = { hours = it },
                modifier = Modifier.width(88.dp),
                singleLine = true,
                suffix = { Text("h") },
                isError = typed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                value = mins,
                onValueChange = { mins = it },
                modifier = Modifier.width(88.dp),
                singleLine = true,
                suffix = { Text("m") },
                isError = typed == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            TextButton(onClick = { typed?.let(onSave) }, enabled = typed != null && typed != minutes) { Text("Save") }
        }
        if (typed == null) {
            Text("Enter a time between 1 minute and 23h 59m", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun AppPickerDialog(vm: SocialViewModel, onDismiss: () -> Unit) {
    val apps by vm.pickable.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val shown = apps.filter { it.label.contains(query.trim(), ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an app") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Search") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (shown.isEmpty()) Text("No apps to add", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(shown, key = { it.pkg }) { app ->
                        Text(
                            app.label,
                            Modifier.fillMaxWidth().clickable { vm.addApp(app.pkg); onDismiss() }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
