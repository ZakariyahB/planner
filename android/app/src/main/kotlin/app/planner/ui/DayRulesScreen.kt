package app.planner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.scheduler.DayRules
import app.planner.scheduler.DayRulesCodec
import app.planner.social.SocialNudges
import java.time.LocalTime

private enum class TimeField(val title: String, val note: String) {
    WAKE("Wake", "Nothing is planned before this"),
    WORK_STOP("Work stops", "Normal days"),
    WORK_STOP_PACKED("Work stops, packed days", "Days with a lot of fixed events"),
    WIND_DOWN("Wind-down", "When tomorrow's plan is offered"),
    LIGHTS_OUT("Lights-out", "The target your readiness is measured against"),
}

private class Stepper(
    val title: String,
    val note: String,
    val value: (DayRules) -> String,
    val step: (DayRules, Int) -> DayRules,
)

private fun duration(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes % 60 == 0 -> "${minutes / 60} h"
    else -> "${minutes / 60} h ${minutes % 60} min"
}

private val STEPPERS = listOf(
    Stepper("Buffer after each block", "Time to breathe between things", { duration(it.blockBufferMin) }) { r, d ->
        r.copy(blockBufferMin = (r.blockBufferMin + 5 * d).coerceIn(DayRulesCodec.BUFFER_RANGE_MIN))
    },
    Stepper("Buffer around prayers", "Kept clear after each prayer time", { duration(it.prayerBufferMin) }) { r, d ->
        r.copy(prayerBufferMin = (r.prayerBufferMin + 5 * d).coerceIn(DayRulesCodec.BUFFER_RANGE_MIN))
    },
    Stepper("Packed day from", "Hours of fixed events", { duration(it.packedFixedMinutes) }) { r, d ->
        r.copy(packedFixedMinutes = (r.packedFixedMinutes + 30 * d).coerceIn(DayRulesCodec.PACKED_RANGE_MIN))
    },
    Stepper("Planned work per day", "Stops the week piling onto Monday", { duration(it.maxTaskMinutesPerDay) }) { r, d ->
        r.copy(maxTaskMinutesPerDay = (r.maxTaskMinutesPerDay + 30 * d).coerceIn(DayRulesCodec.DAILY_CAP_RANGE_MIN))
    },
    Stepper("Sunday catch-up", "Room for work that didn't fit earlier", { duration(it.sundayCatchUpMin) }) { r, d ->
        r.copy(sundayCatchUpMin = (r.sundayCatchUpMin + 30 * d).coerceIn(DayRulesCodec.CATCH_UP_RANGE_MIN))
    },
)

@Composable
fun DayRulesScreen(vm: DayRulesViewModel) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val problem by vm.problem.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<TimeField?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { Box(Modifier.height(4.dp)) }
        item { DayStrip(rules) }
        problem?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }

        item {
            Group("Times") {
                TimeField.entries.forEachIndexed { index, field ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RuleRow(field.title, field.note) {
                        OutlinedButton(onClick = { picking = field }) { Text(timeFormat.format(timeOf(rules, field))) }
                    }
                }
            }
        }
        item {
            Group("Gaps and limits") {
                STEPPERS.forEachIndexed { index, stepper ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RuleRow(stepper.title, stepper.note) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { vm.edit { stepper.step(it, -1) } }) { Text("−") }
                            Text(stepper.value(rules), Modifier.width(72.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                            OutlinedButton(onClick = { vm.edit { stepper.step(it, 1) } }) { Text("+") }
                        }
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Changes apply to the next plan. Blocks you have locked or pinned to a time stay where they are.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = vm::reset) { Text("Reset to defaults") }
            }
        }
        item { Box(Modifier.height(8.dp)) }
    }

    picking?.let { field ->
        TimePickerDialog(
            title = field.title,
            initial = timeOf(rules, field),
            onConfirm = { time -> vm.edit { withTime(it, field, time) }; picking = null },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun DayStrip(rules: DayRules) {
    val wake = rules.wake.hour * 60 + rules.wake.minute
    val stop = rules.workStop.hour * 60 + rules.workStop.minute
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("A normal day", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().height(28.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))) {
            if (wake > 0) Box(Modifier.weight(wake.toFloat()).fillMaxHeight())
            if (stop > wake) Box(Modifier.weight((stop - wake).toFloat()).fillMaxHeight().background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)))
            if (stop < 24 * 60) Box(Modifier.weight((24 * 60 - stop).toFloat()).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("00:00", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${timeFormat.format(rules.wake)} wake", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${timeFormat.format(rules.workStop)} work stops", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${SocialNudges.duration(stop - wake)} of focused time, minus prayers, buffers and fixed commitments.")
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))) { content() }
    }
}

@Composable
private fun RuleRow(title: String, note: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        control()
    }
}

private fun timeOf(rules: DayRules, field: TimeField): LocalTime = when (field) {
    TimeField.WAKE -> rules.wake
    TimeField.WORK_STOP -> rules.workStop
    TimeField.WORK_STOP_PACKED -> rules.workStopPacked
    TimeField.WIND_DOWN -> rules.windDown
    TimeField.LIGHTS_OUT -> rules.lightsOut
}

private fun withTime(rules: DayRules, field: TimeField, time: LocalTime): DayRules = when (field) {
    TimeField.WAKE -> rules.copy(wake = time)
    TimeField.WORK_STOP -> rules.copy(workStop = time)
    TimeField.WORK_STOP_PACKED -> rules.copy(workStopPacked = time)
    TimeField.WIND_DOWN -> rules.copy(windDown = time)
    TimeField.LIGHTS_OUT -> rules.copy(lightsOut = time)
}
