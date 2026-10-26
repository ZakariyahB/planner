package app.planner.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.planner.model.ProgressOutcome
import app.planner.model.ProgressPreview
import app.planner.model.Task
import app.planner.ui.theme.LocalStatusColors

private fun ProgressOutcome.label(): String = when (this) {
    ProgressOutcome.DONE -> "Done"
    ProgressOutcome.PARTIAL -> "Partly"
    ProgressOutcome.DROPPED -> "Drop"
}

/**
 * Opened by long-pressing a task: what came of it (done, partly, dropped), how many minutes went into it, and a
 * line saying what saving will do. The wording comes from [ProgressPreview] so it matches the result exactly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressSheet(task: Task, onSave: (minutes: Int, outcome: ProgressOutcome) -> Unit, onDismiss: () -> Unit) {
    var outcome by rememberSaveable { mutableStateOf(ProgressOutcome.PARTIAL) }
    var chosen by rememberSaveable { mutableStateOf(DEFAULT_MINUTES) } // 0 means none picked
    var other by rememberSaveable { mutableStateOf(false) }
    var typed by rememberSaveable { mutableStateOf("") }

    val minutes = if (other) ProgressPreview.parseMinutes(typed) else chosen
    val problem = when {
        minutes == null -> "Type the minutes as a whole number, like 25."
        else -> ProgressPreview.problem(outcome, minutes)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("A little progress counts.", style = MaterialTheme.typography.headlineSmall)
                Text(task.title, style = MaterialTheme.typography.titleMedium)
                Text("${ProgressPreview.duration(task.estMin)} planned", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text("How did it go?", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressOutcome.entries.forEach { o ->
                    FilterChip(selected = outcome == o, onClick = { outcome = o }, label = { Text(o.label()) })
                }
            }

            Text("Minutes worked", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProgressPreview.QUICK_MINUTES.forEach { m ->
                    FilterChip(
                        selected = !other && chosen == m,
                        onClick = { other = false; chosen = if (chosen == m) 0 else m },
                        label = { Text("$m") },
                    )
                }
                FilterChip(selected = other, onClick = { other = !other }, label = { Text("Other") })
            }
            if (other) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { text -> if (text.length <= 4 && text.all(Char::isDigit)) typed = text },
                    label = { Text("Minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (problem == null) {
                Text(ProgressPreview.line(task, minutes ?: 0, outcome))
            } else {
                Text(problem, color = LocalStatusColors.current.warningText)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                Button(
                    onClick = { onSave(minutes ?: 0, outcome) },
                    enabled = problem == null,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Save progress") }
            }
        }
    }
}

private const val DEFAULT_MINUTES = 30
