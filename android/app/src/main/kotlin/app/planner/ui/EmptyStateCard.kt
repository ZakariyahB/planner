package app.planner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.planner.empty.EmptyCard
import app.planner.empty.EmptyStates
import app.planner.empty.TemplateAction

/** A calm card for a place with nothing in it yet: what is going on, then what to do about it. */
@Composable
fun EmptyStateCard(card: EmptyCard, modifier: Modifier = Modifier, actions: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(card.title, style = MaterialTheme.typography.titleMedium)
        Text(card.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}

/**
 * Today or the Tasks tab with no tasks: Add task, and either fill the week from the weekly template or go and
 * make one. [message] is what the template button just did (for example "Added 6 tasks").
 */
@Composable
fun EmptyTasksCard(
    today: Boolean,
    templateItems: Int,
    message: String?,
    onAdd: () -> Unit,
    onGenerate: () -> Unit,
    onSetUpTemplate: () -> Unit,
) {
    val empty = EmptyStates.tasks(templateItems, today)
    EmptyStateCard(empty.card) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add task") }
            OutlinedButton(
                onClick = if (empty.template == TemplateAction.GENERATE) onGenerate else onSetUpTemplate,
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(empty.template.label) }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
    }
}
