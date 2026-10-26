package app.planner.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.planner.model.Anchor
import app.planner.model.Priority
import app.planner.model.Task
import app.planner.model.Weeks
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Add (task == null) or edit a task. Due dates are end-of-day local time, like template tasks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditor(
    task: Task?,
    onSave: (Task) -> Unit,
    onSaveOnDays: (Task, List<LocalDate>) -> Unit,
    onDrop: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val zone = ZoneId.systemDefault()
    var title by remember { mutableStateOf(task?.title.orEmpty()) }
    var module by remember { mutableStateOf(task?.module.orEmpty()) }
    var minutes by remember { mutableStateOf((task?.estMin ?: 60).toString()) }
    var due by remember { mutableStateOf(task?.dueAt?.atZone(zone)?.toLocalDate()) }
    var priority by remember { mutableStateOf(task?.priority ?: Priority.NORMAL) }
    var anchor by remember { mutableStateOf<Anchor?>(task?.anchor) }
    var startAt by remember { mutableStateOf(task?.startAt) }
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var days by remember { mutableStateOf(emptySet<DayOfWeek>()) }
    var nextWeek by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(task != null) }
    val today = LocalDate.now(zone)
    val weekMonday = Weeks.mondayOf(today).plusWeeks(if (nextWeek) 1 else 0)
    val chosenDates = Weeks.dates(weekMonday, days).filter { it >= today }

    val estMin = minutes.toIntOrNull()
    val valid = title.isNotBlank() && estMin != null && estMin in 5..Task.MAX_EST_MIN

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).imePadding().padding(horizontal = 22.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (task == null) "Make time for something" else "Task details", style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    minutes,
                    { minutes = it.filter(Char::isDigit) },
                    label = { Text("Estimate (minutes, 5–${Task.MAX_EST_MIN})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = estMin == null || estMin !in 5..Task.MAX_EST_MIN,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { showDetails = !showDetails }) { Text(if (showDetails) "Hide extra details" else "Timing, days, module & priority") }
                if (showDetails) {
                OutlinedTextField(module, { module = it }, label = { Text("Module (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (task == null) {
                    DayChooser(days, nextWeek, weekMonday, today, onDays = { days = it }, onNextWeek = { nextWeek = it })
                }
                if (chosenDates.isEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickingDate = true }) { Text(due?.format(dateFormat)?.let { "Due $it" } ?: "No due date") }
                        if (due != null) TextButton(onClick = { due = null }) { Text("Clear") }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickingTime = true }) {
                        Text(startAt?.let { "At ${timeFormat.format(it)}" } ?: "Let the planner choose a time")
                    }
                    if (startAt != null) TextButton(onClick = { startAt = null }) { Text("Clear") }
                }
                PriorityPicker(priority) { priority = it }
                AnchorPicker(anchor) { anchor = it }
                }
            }
            Text("One task, one uninterrupted block. Planner checks the time against your day rules.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = valid,
                onClick = {
                    val base = task ?: Task(title = title.trim(), estMin = estMin!!)
                    val edited = base.copy(
                            title = title.trim(),
                            module = module.trim().ifBlank { null },
                            estMin = estMin!!,
                            dueAt = due?.atTime(LocalTime.of(23, 59))?.atZone(zone)?.toInstant(),
                            priority = priority,
                            anchor = anchor,
                            startAt = startAt,
                        )
                    if (task == null && chosenDates.isNotEmpty()) onSaveOnDays(edited, chosenDates) else onSave(edited)
                },
            ) { Text("Save task") }
                if (onDrop != null) TextButton(onClick = onDrop) { Text("Drop") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }

    if (pickingTime) {
        TimePickerDialog(
            title = "Do it at",
            initial = startAt ?: LocalTime.of(9, 0),
            onConfirm = { startAt = it; pickingTime = false },
            onDismiss = { pickingTime = false },
        )
    }

    if (pickingDate) {
        // The picker works in UTC midnights; convert to a plain LocalDate and never touch the zone.
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (due ?: LocalDate.now(zone)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { due = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/** Pick any days of this week or next; each chosen day becomes its own task. Past days are disabled. */
@Composable
private fun DayChooser(
    days: Set<DayOfWeek>,
    nextWeek: Boolean,
    weekMonday: LocalDate,
    today: LocalDate,
    onDays: (Set<DayOfWeek>) -> Unit,
    onNextWeek: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Days (optional)", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !nextWeek, onClick = { onNextWeek(false) }, label = { Text("This week") })
            FilterChip(selected = nextWeek, onClick = { onNextWeek(true) }, label = { Text("Next week") })
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DayOfWeek.entries.forEach { d ->
                val date = weekMonday.plusDays((d.value - DayOfWeek.MONDAY.value).toLong())
                FilterChip(
                    selected = d in days,
                    enabled = date >= today,
                    onClick = { onDays(if (d in days) days - d else days + d) },
                    label = { Text(d.short()) },
                )
            }
        }
    }
}
