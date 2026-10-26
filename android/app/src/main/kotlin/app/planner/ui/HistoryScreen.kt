package app.planner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.history.HistoryDay
import app.planner.history.HistoryItem

@Composable
fun HistoryScreen(vm: HistoryViewModel) {
    val days by vm.days.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(
                "Every change to your plan, whoever made it. Undo puts the blocks back where they were.",
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.primary) } }
        if (days.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Nothing has changed yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Moves you make, and changes the assistant makes for you, will be listed here with an Undo.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(days, key = { it.title }) { day -> DaySection(day, onUndo = vm::undo) }
    }
}

@Composable
private fun DaySection(day: HistoryDay, onUndo: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(day.title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.fillMaxWidth()) {
            day.items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                HistoryRow(item, onUndo)
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem, onUndo: (Long) -> Unit) {
    val dim = if (item.undone) 0.55f else 1f
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim))
            Text("${item.time} · ${item.who}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.detail.isNotEmpty()) Text(item.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = { onUndo(item.id) }, enabled = !item.undone) { Text(if (item.undone) "Undone" else "Undo") }
    }
}
