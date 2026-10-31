package app.planner.ui.backlog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.engine.model.Task
import app.planner.engine.model.UnparsedTemplate
import app.planner.ui.model.BacklogTaskUi
import app.planner.ui.model.TaskDraft
import app.planner.ui.rhythm.EmptyNote
import app.planner.ui.rhythm.GroupLabel
import app.planner.ui.rhythm.PillButton
import app.planner.ui.rhythm.PillKind
import app.planner.ui.rhythm.QuietButton
import app.planner.ui.rhythm.RhythmCard
import app.planner.ui.rhythm.ScreenHeader
import app.planner.ui.theme.RhythmTheme

class BacklogActions(
    val onAdd: () -> Unit = {},
    val onEdit: (Task) -> Unit = {},
    val onPlan: (Task) -> Unit = {},
    val onFix: (UnparsedTemplate) -> Unit = {},
    val onImport: () -> Unit = {},
    val onDraft: (TaskDraft) -> Unit = {},
    val onSave: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onDismissEditor: () -> Unit = {},
)

@Composable
fun BacklogRoute(vm: BacklogViewModel, snackbar: SnackbarHostState, onOpenApproval: (String?) -> Unit, bottomPadding: Dp) {
    val ui by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.messages.collect { e ->
            when (e) {
                is BacklogEvent.OpenApproval -> onOpenApproval(e.changeSetId)
                is BacklogEvent.Message -> snackbar.showSnackbar(e.text)
            }
        }
    }
    val state = ui
    if (state == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RhythmTheme.colors.accent) }
        return
    }
    BacklogContent(
        state,
        BacklogActions(
            onAdd = vm::add, onEdit = vm::edit, onPlan = { vm.plan(it) }, onFix = vm::fix, onImport = { vm.importTemplate() },
            onDraft = vm::update, onSave = { vm.save() }, onDelete = { vm.delete() }, onDismissEditor = vm::dismissEditor,
        ),
        bottomPadding,
    )
}

@Composable
fun BacklogContent(ui: BacklogScreenUi, actions: BacklogActions, bottomPadding: Dp = 96.dp) {
    val c = RhythmTheme.colors
    val b = ui.backlog
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") {
            ScreenHeader(b.summary, "Backlog", "Tasks wait here until you plan them. Planning asks for approval first.") {
                IconButton(onClick = actions.onAdd) { Icon(Icons.Outlined.Add, contentDescription = "Add task", tint = c.ink) }
            }
        }
        item(key = "actions") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("Add task", actions.onAdd)
                PillButton("Import from calendar", actions.onImport, kind = PillKind.SECONDARY, contentDescription = "Import this week's and next week's all-day template items")
                if (ui.busy) CircularProgressIndicator(color = c.accent)
            }
        }
        if (b.toPlan.isEmpty() && b.planned.isEmpty() && b.needsALook.isEmpty()) {
            item(key = "empty") {
                EmptyNote(
                    "Nothing in the backlog",
                    "Add a task, or import your weekly template: numbered all-day events like “1. PintOS (3h)” become tasks.",
                )
            }
        }
        if (b.needsALook.isNotEmpty()) {
            item(key = "needs-label") { GroupLabel("Needs a look · title did not parse", color = c.amberText) }
            items(b.needsALook, key = { "n-" + it.item.ref.eventId }) { n ->
                RhythmCard(amber = true, padding = PaddingValues(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(n.quoted, style = MaterialTheme.typography.titleMedium)
                            Text(n.detail, style = MaterialTheme.typography.bodySmall, color = c.muted)
                        }
                        QuietButton("Fix", { actions.onFix(n.item) })
                    }
                }
            }
        }
        if (b.toPlan.isNotEmpty()) {
            item(key = "plan-label") { GroupLabel("To plan") }
            items(b.toPlan, key = { "t-" + it.task.id }) { TaskRow(it, actions, plannable = true) }
        }
        if (b.planned.isNotEmpty()) {
            item(key = "planned-label") { GroupLabel("On the calendar") }
            items(b.planned, key = { "p-" + it.task.id }) { TaskRow(it, actions, plannable = false) }
        }
    }
    ui.editor?.let { TaskEditorSheet(it, actions) }
}

@Composable
private fun TaskRow(t: BacklogTaskUi, actions: BacklogActions, plannable: Boolean) {
    val c = RhythmTheme.colors
    RhythmCard(amber = t.overdue, onClick = { actions.onEdit(t.task) }, padding = PaddingValues(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = t.description }, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row {
                    Text(t.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                    Text("  " + t.duration, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = c.muted)
                }
                Text(t.category, style = MaterialTheme.typography.labelMedium, color = c.accent)
                if (t.chips.isNotEmpty()) {
                    Text(t.chips.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = if (t.overdue) c.amberText else c.muted)
                }
            }
            if (plannable) QuietButton("Plan", { actions.onPlan(t.task) })
        }
    }
}
