package app.planner.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.planner.model.Anchor
import app.planner.model.Prayer
import app.planner.model.Priority
import app.planner.model.Task
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)
val dayHeaderFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)
val syncFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.UK)
val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)

fun Prayer.label() = name.lowercase().replaceFirstChar { it.uppercase() }

fun Priority.label() = name.lowercase().replaceFirstChar { it.uppercase() }

fun DayOfWeek.short(): String = getDisplayName(TextStyle.SHORT, Locale.UK)

fun DayOfWeek.full(): String = getDisplayName(TextStyle.FULL, Locale.UK)

fun Anchor.label(): String = when {
    offsetMin == 0 -> "at ${prayer.label()}"
    offsetMin > 0 -> "${prayer.label()} +$offsetMin"
    else -> "${prayer.label()} $offsetMin"
}

fun taskMeta(task: Task, zone: ZoneId): String = listOfNotNull(
    task.module,
    "${task.estMin} min",
    task.dueAt?.atZone(zone)?.toLocalDate()?.format(dateFormat)?.let { "due $it" },
    task.anchor?.label(),
).joinToString(" · ")

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
}

@Composable
fun PriorityPicker(selected: Priority, onSelect: (Priority) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Priority.entries.reversed().forEach { p ->
            FilterChip(selected = p == selected, onClick = { onSelect(p) }, label = { Text(p.label()) })
        }
    }
}

/** "None" or a prayer, with an offset in minutes once a prayer is chosen. */
@Composable
fun AnchorPicker(anchor: Anchor?, onChange: (Anchor?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = anchor == null, onClick = { onChange(null) }, label = { Text("No anchor") })
            Prayer.entries.forEach { p ->
                FilterChip(
                    selected = anchor?.prayer == p,
                    onClick = { onChange(Anchor(p, anchor?.offsetMin ?: 0)) },
                    label = { Text(p.label()) },
                )
            }
        }
        if (anchor != null) {
            OutlinedTextField(
                value = anchor.offsetMin.toString(),
                onValueChange = { text -> text.toIntOrNull()?.let { onChange(anchor.copy(offsetMin = it)) } },
                label = { Text("Minutes after ${anchor.prayer.label()} (negative = before)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
