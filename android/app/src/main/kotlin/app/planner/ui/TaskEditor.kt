package app.planner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Add (task == null) or edit a task. Due dates are end-of-day local time, like template tasks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditor(task: Task?, onSave: (Task) -> Unit, onDrop: (() -> Unit)?, onDismiss: () -> Unit) {
    val zone = ZoneId.systemDefault()
    var title by remember { mutableStateOf(task?.title.orEmpty()) }
    var module by remember { mutableStateOf(task?.module.orEmpty()) }
    var minutes by remember { mutableStateOf((task?.estMin ?: 60).toString()) }
    var due by remember { mutableStateOf(task?.dueAt?.atZone(zone)?.toLocalDate()) }
    var priority by remember { mutableStateOf(task?.priority ?: Priority.NORMAL) }
    var anchor by remember { mutableStateOf<Anchor?>(task?.anchor) }
    var pickingDate by remember { mutableStateOf(false) }

    val estMin = minutes.toIntOrNull()
    val valid = title.isNotBlank() && estMin != null && estMin in 5..Task.MAX_EST_MIN

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) "Add task" else "Edit task") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(module, { module = it }, label = { Text("Module (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    minutes,
                    { minutes = it.filter(Char::isDigit) },
                    label = { Text("Estimate (minutes, 5–${Task.MAX_EST_MIN})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = estMin == null || estMin !in 5..Task.MAX_EST_MIN,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { pickingDate = true }) { Text(due?.format(dateFormat)?.let { "Due $it" } ?: "No due date") }
                    if (due != null) TextButton(onClick = { due = null }) { Text("Clear") }
                }
                PriorityPicker(priority) { priority = it }
                AnchorPicker(anchor) { anchor = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val base = task ?: Task(title = title.trim(), estMin = estMin!!)
                    onSave(
                        base.copy(
                            title = title.trim(),
                            module = module.trim().ifBlank { null },
                            estMin = estMin!!,
                            dueAt = due?.atTime(LocalTime.of(23, 59))?.atZone(zone)?.toInstant(),
                            priority = priority,
                            anchor = anchor,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDrop != null) TextButton(onClick = onDrop) { Text("Drop") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )

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
