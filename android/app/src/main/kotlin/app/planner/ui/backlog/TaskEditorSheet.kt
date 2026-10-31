package app.planner.ui.backlog

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.Prayer
import app.planner.engine.model.TaskKind
import app.planner.ui.TimePickerDialog
import app.planner.ui.model.Fmt
import app.planner.ui.model.TaskDraft
import app.planner.ui.rhythm.PillButton
import app.planner.ui.rhythm.PillKind
import app.planner.ui.rhythm.QuietButton
import app.planner.ui.theme.RhythmTheme
import java.time.LocalDate
import java.time.LocalTime

/** Add or edit a backlog task. Every control is at least 48dp and labelled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditorSheet(d: TaskDraft, actions: BacklogActions) {
    val c = RhythmTheme.colors
    var pickingTime by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = actions.onDismissEditor,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.ground,
    ) {
        Column(
            Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                when {
                    d.fromTemplate != null -> "Fix template item"
                    d.id == null -> "New task"
                    else -> "Edit task"
                },
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
            d.fromTemplate?.let { Text("Was “${it.title}”. Fixing it here adds a task; rename the all-day event in Google Calendar too so the next import understands it.", style = MaterialTheme.typography.bodySmall, color = c.muted) }

            OutlinedTextField(
                value = d.title,
                onValueChange = { actions.onDraft(d.copy(title = it)) },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, focusedLabelColor = c.accent, cursorColor = c.accent),
            )

            Section("Calendar") {
                ChipRow(CalendarCategory.entries, d.category, label = { it.calendarName }) { actions.onDraft(d.copy(category = it)) }
            }

            Section("Duration") {
                Stepper(Fmt.duration(d.durationMin), "duration", onMinus = { actions.onDraft(d.copy(durationMin = (d.durationMin - 15).coerceAtLeast(15))) }, onPlus = { actions.onDraft(d.copy(durationMin = (d.durationMin + 15).coerceAtMost(12 * 60))) })
            }

            Section("Day") {
                DayStepper(d.day, "day") { actions.onDraft(d.copy(day = it)) }
            }

            Section("Not before") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(d.earliestStart?.let { Fmt.time(it) } ?: "Any time", { pickingTime = true }, kind = PillKind.SECONDARY, contentDescription = "Earliest start, ${d.earliestStart?.let { Fmt.time(it) } ?: "any time"}")
                    if (d.earliestStart != null) QuietButton("Clear", { actions.onDraft(d.copy(earliestStart = null)) })
                }
            }

            Section("After a prayer") {
                ChipRow(listOf<Prayer?>(null) + listOf(Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA), d.afterPrayer, label = { it?.let(Fmt::prayer) ?: "No" }) {
                    actions.onDraft(d.copy(afterPrayer = it))
                }
            }

            Section("Deadline") {
                DayStepper(d.deadline, "deadline") { actions.onDraft(d.copy(deadline = it)) }
            }

            Section("Kind") {
                ChipRow(TaskKind.entries, d.kind, label = {
                    when (it) {
                        TaskKind.DEEP -> "Deep work"
                        TaskKind.PASSIVE -> "Passive (lectures)"
                        TaskKind.OVERFLOW -> "Catch-up"
                    }
                }) { actions.onDraft(d.copy(kind = it)) }
            }

            Section("Priority") {
                Stepper(if (d.priority == 0) "Normal" else "Priority ${d.priority}", "priority", onMinus = { actions.onDraft(d.copy(priority = (d.priority - 1).coerceAtLeast(0))) }, onPlus = { actions.onDraft(d.copy(priority = (d.priority + 1).coerceAtMost(5))) })
            }

            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = d.splittable, role = Role.Switch, onValueChange = { actions.onDraft(d.copy(splittable = it)) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Can be split", style = MaterialTheme.typography.titleMedium)
                    Text("Into chunks of at least 45 minutes, only when it does not fit whole", style = MaterialTheme.typography.bodySmall, color = c.muted)
                }
                Switch(checked = d.splittable, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
            }

            OutlinedTextField(
                value = d.notes,
                onValueChange = { actions.onDraft(d.copy(notes = it)) },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, focusedLabelColor = c.accent, cursorColor = c.accent),
            )

            d.problems.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.amberText) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Cancel", actions.onDismissEditor, kind = PillKind.SECONDARY)
                PillButton("Save", actions.onSave, Modifier.weight(1f), enabled = d.canSave)
            }
            if (d.id != null) QuietButton("Remove from backlog", actions.onDelete, color = c.error)
        }
    }
    if (pickingTime) {
        TimePickerDialog(
            title = "Not before",
            initial = d.earliestStart ?: LocalTime.of(14, 0),
            onConfirm = { actions.onDraft(d.copy(earliestStart = it)); pickingTime = false },
            onDismiss = { pickingTime = false },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = RhythmTheme.colors.muted)
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val c = RhythmTheme.colors
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(label(o)) },
                modifier = Modifier.heightIn(min = 48.dp),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accentSoft, selectedLabelColor = c.onAccentSoft),
            )
        }
    }
}

@Composable
private fun Stepper(value: String, what: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton("−", onMinus, kind = PillKind.SECONDARY, contentDescription = "Less $what")
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
        PillButton("+", onPlus, kind = PillKind.SECONDARY, contentDescription = "More $what")
    }
}

@Composable
private fun DayStepper(day: LocalDate?, what: String, onChange: (LocalDate?) -> Unit) {
    val today = LocalDate.now()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton("‹", { onChange((day ?: today.plusDays(1)).minusDays(1)) }, kind = PillKind.SECONDARY, contentDescription = "Earlier $what")
        Text(day?.let(Fmt::shortDate) ?: "None", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 4.dp))
        PillButton("›", { onChange((day ?: today.minusDays(1)).plusDays(1)) }, kind = PillKind.SECONDARY, contentDescription = "Later $what")
        if (day != null) QuietButton("Clear", { onChange(null) })
    }
}
