package app.planner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.model.Anchor
import app.planner.model.Priority
import app.planner.model.Task
import app.planner.model.TemplateItem
import java.time.DayOfWeek
import java.util.UUID

@Composable
fun TemplateScreen(tasksVm: TasksViewModel, onBack: () -> Unit) {
    val template by tasksVm.template.collectAsStateWithLifecycle()
    val message by tasksVm.generated.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<TemplateItem?>(null) }
    var adding by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onBack) { Text("‹ Settings") }
                Text("Weekly template", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "Recurring tasks, e.g. a lecture catch-up every Tuesday. Generating a week never duplicates tasks.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DayOfWeek.entries.forEach { day ->
            val dayItems = template.items.filter { it.day == day }
            if (dayItems.isNotEmpty()) {
                item(key = day.name) { SectionTitle(day.full()) }
                items(dayItems, key = { it.id }) { item ->
                    Column(Modifier.fillMaxWidth().clickable { editing = item }.padding(vertical = 4.dp)) {
                        Text(item.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(item.module, "${item.estMin} min", item.anchor?.label()).joinToString(" · "),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (template.items.isEmpty()) item { Text("No recurring tasks yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = { adding = true }) { Text("Add recurring task") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { tasksVm.generateWeek(0) }, enabled = template.items.isNotEmpty()) { Text("Generate this week") }
                    OutlinedButton(onClick = { tasksVm.generateWeek(1) }, enabled = template.items.isNotEmpty()) { Text("Next week") }
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
    }

    if (adding || editing != null) {
        TemplateItemEditor(
            item = editing,
            onSave = { tasksVm.saveTemplateItem(it); adding = false; editing = null },
            onDelete = editing?.let { current -> { tasksVm.deleteTemplateItem(current.id); editing = null } },
            onDismiss = { adding = false; editing = null },
        )
    }
}

@Composable
private fun TemplateItemEditor(item: TemplateItem?, onSave: (TemplateItem) -> Unit, onDelete: (() -> Unit)?, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf(item?.title.orEmpty()) }
    var module by remember { mutableStateOf(item?.module.orEmpty()) }
    var minutes by remember { mutableStateOf((item?.estMin ?: 60).toString()) }
    var day by remember { mutableStateOf(item?.day ?: DayOfWeek.MONDAY) }
    var priority by remember { mutableStateOf(item?.priority ?: Priority.NORMAL) }
    var anchor by remember { mutableStateOf<Anchor?>(item?.anchor) }

    val estMin = minutes.toIntOrNull()
    val valid = title.isNotBlank() && estMin != null && estMin in 5..Task.MAX_EST_MIN

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (item == null) "Add recurring task" else "Edit recurring task") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayOfWeek.entries.forEach { d -> FilterChip(selected = d == day, onClick = { day = d }, label = { Text(d.short()) }) }
                }
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(module, { module = it }, label = { Text("Module (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    minutes,
                    { minutes = it.filter(Char::isDigit) },
                    label = { Text("Estimate (minutes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = estMin == null || estMin !in 5..Task.MAX_EST_MIN,
                    modifier = Modifier.fillMaxWidth(),
                )
                PriorityPicker(priority) { priority = it }
                AnchorPicker(anchor) { anchor = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        TemplateItem(
                            id = item?.id ?: UUID.randomUUID().toString(),
                            day = day,
                            title = title.trim(),
                            module = module.trim().ifBlank { null },
                            estMin = estMin!!,
                            anchor = anchor,
                            priority = priority,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
