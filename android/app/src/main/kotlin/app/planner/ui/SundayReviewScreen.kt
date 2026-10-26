package app.planner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.model.Task
import app.planner.routines.NotificationTexts
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private const val STEPS = 5

/**
 * The Sunday review, one question at a time: how the week went, upcoming deadlines, this week's Fajr, the
 * weekly template, then generating next week. Every step can be skipped; the week is generated from the
 * template on Monday anyway.
 */
@Composable
fun SundayReviewScreen(onDismiss: () -> Unit, onOpenTemplate: () -> Unit, vm: SundayReviewViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(Unit) { vm.start() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Sunday review", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Step ${step + 1} of $STEPS · planning the week of ${dateFormat.format(ui.planWeek)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (step) {
                        0 -> WeekStep(ui)
                        1 -> DeadlinesStep(ui, onAdd = vm::addDeadline)
                        2 -> FajrStep(ui, onPick = vm::saveFajr)
                        3 -> TemplateStep(ui, onOpenTemplate)
                        else -> GenerateStep(ui, onGenerate = vm::generate)
                    }
                }

                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (step > 0) OutlinedButton(onClick = { step -= 1 }) { Text("Back") }
                    if (step < STEPS - 1) {
                        Button(onClick = { step += 1 }) { Text("Next") }
                        TextButton(onClick = onDismiss) { Text("Close") }
                    } else {
                        Button(onClick = onDismiss) { Text("Done") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekStep(ui: ReviewUi) {
    SectionTitle("How the week went")
    val report = ui.report
    if (!ui.loaded || report == null) {
        Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (report.plannedMin == 0) {
        Text("Nothing was planned last week.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val percent = Math.round((report.doneRatio ?: 0.0) * 100).toInt()
    Text(
        "${NotificationTexts.duration(report.doneMin)} done of ${NotificationTexts.duration(report.plannedMin)} planned ($percent%).",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text("${report.doneTasks} of ${report.plannedTasks} tasks finished.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (report.notDone.isNotEmpty()) {
        Text("Still open, and carried into the plan:", style = MaterialTheme.typography.titleMedium)
        report.notDone.forEach { Text("• ${it.title}") }
    }
}

@Composable
private fun DeadlinesStep(ui: ReviewUi, onAdd: (title: String, estMin: Int, due: LocalDate) -> Unit) {
    SectionTitle("Upcoming deadlines")
    Text(
        "Anything due in the next two weeks that the planner doesn't know about yet? Tasks come from you only, so add them here.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val zone = ZoneId.systemDefault()
    if (ui.knownDeadlines.isNotEmpty()) {
        Text("Already on your list:", style = MaterialTheme.typography.titleMedium)
        ui.knownDeadlines.forEach { Text("• ${deadlineLine(it, zone)}") }
    }
    if (ui.addedDeadlines.isNotEmpty()) {
        Text("Added now:", style = MaterialTheme.typography.titleMedium)
        ui.addedDeadlines.forEach { Text("• ${deadlineLine(it, zone)}", color = MaterialTheme.colorScheme.primary) }
    }

    var title by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("120") }
    var due by remember { mutableStateOf<LocalDate?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    val estMin = minutes.toIntOrNull()
    val valid = title.isNotBlank() && due != null && estMin != null && estMin in 5..Task.MAX_EST_MIN

    OutlinedTextField(title, { title = it }, label = { Text("What's due") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(
        minutes,
        { minutes = it.filter(Char::isDigit) },
        label = { Text("Time needed (minutes)") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        isError = estMin == null || estMin !in 5..Task.MAX_EST_MIN,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { pickingDate = true }) { Text(due?.let { "Due ${dateFormat.format(it)}" } ?: "Pick the due date") }
        Button(
            enabled = valid,
            onClick = {
                onAdd(title, estMin!!, due!!)
                title = ""
                due = null
            },
        ) { Text("Add") }
    }

    if (pickingDate) {
        DateDialog(
            initial = due ?: LocalDate.now(zone),
            onConfirm = { due = it; pickingDate = false },
            onDismiss = { pickingDate = false },
        )
    }
}

private fun deadlineLine(task: Task, zone: ZoneId): String =
    listOfNotNull(task.title, task.dueAt?.atZone(zone)?.toLocalDate()?.let { "due ${dateFormat.format(it)}" }, "${task.estMin} min").joinToString(" · ")

@Composable
private fun FajrStep(ui: ReviewUi, onPick: (LocalTime) -> Unit) {
    SectionTitle("Fajr this week")
    Text(
        "A rough time is enough. It anchors prayer-based tasks for the week of ${dateFormat.format(ui.planWeek)}.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    var picking by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { picking = true }) {
        Text(ui.fajr?.let { "Fajr ${timeFormat.format(it)}: change" } ?: "Set Fajr time")
    }
    if (picking) {
        TimePickerDialog(
            title = "Fajr this week",
            initial = ui.fajr ?: LocalTime.of(5, 30),
            onConfirm = { onPick(it); picking = false },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun TemplateStep(ui: ReviewUi, onOpenTemplate: () -> Unit) {
    SectionTitle("Weekly template")
    Text(
        "These repeat every week. Anything that changed for the new week, add or edit now.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (ui.template.items.isEmpty()) {
        Text("No recurring tasks yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ui.template.items.forEach { item ->
        Text("• ${item.title} · ${item.allDays.joinToString(", ") { it.short() }} · ${item.estMin} min")
    }
    OutlinedButton(onClick = onOpenTemplate) { Text("Edit the weekly template") }
    Text("Editing closes this review; come back here from Settings → Weekly template → Next week.", color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun GenerateStep(ui: ReviewUi, onGenerate: () -> Unit) {
    SectionTitle("Generate next week")
    Text(
        "Creates the template's tasks for the week of ${dateFormat.format(ui.planWeek)} and plans it. Nothing is duplicated if you run it twice.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onGenerate, enabled = !ui.generating) { Text(if (ui.generating) "Planning…" else "Generate and plan") }
    ui.generated?.let { created ->
        Text(
            if (created == 0) "Nothing new: those tasks already exist. The plan is up to date." else "Added $created task${if (created == 1) "" else "s"} and planned the week.",
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** The picker works in UTC midnights; convert to a plain LocalDate and never touch the zone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initial: LocalDate, onConfirm: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onConfirm(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = state) }
}
