package app.planner.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.ui.model.BlockTone
import app.planner.ui.model.FillKind
import app.planner.ui.model.WeekRhythm
import app.planner.ui.model.WeekRow
import app.planner.ui.rhythm.FlagChip
import app.planner.ui.rhythm.PillButton
import app.planner.ui.rhythm.QuietButton
import app.planner.ui.rhythm.RhythmCard
import app.planner.ui.rhythm.ScreenHeader
import app.planner.ui.rhythm.Swatch
import app.planner.ui.theme.RhythmTheme

class WeekActions(
    val onPrevious: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onThisWeek: () -> Unit = {},
    val onCopyTemplate: () -> Unit = {},
    val onOpenReflect: () -> Unit = {},
)

@Composable
fun WeekRoute(vm: WeekViewModel, snackbar: SnackbarHostState, onOpenApproval: (String?) -> Unit, onOpenReflect: () -> Unit, bottomPadding: Dp) {
    val ui by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) {
        vm.messages.collect { e ->
            when (e) {
                is WeekEvent.OpenApproval -> onOpenApproval(e.changeSetId)
                is WeekEvent.Message -> snackbar.showSnackbar(e.text)
            }
        }
    }
    val state = ui ?: return Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RhythmTheme.colors.accent) }
    WeekContent(
        state,
        WeekActions(vm::previous, vm::next, vm::thisWeek, { vm.copyTemplateIntoNextWeek() }, onOpenReflect),
        bottomPadding,
    )
}

@Composable
fun WeekContent(ui: WeekScreenUi, actions: WeekActions, bottomPadding: Dp = 96.dp) {
    val c = RhythmTheme.colors
    val week = ui.week
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "header") {
            ScreenHeader(week.rangeLabel, "The week’s rhythm", "How full each stretch between prayers is") {
                IconButton(onClick = actions.onPrevious) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = "Previous week", tint = c.ink) }
                IconButton(onClick = actions.onNext) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = "Next week", tint = c.ink) }
            }
        }
        if (!ui.isThisWeek) item(key = "this-week") { QuietButton("Back to this week", actions.onThisWeek) }
        item(key = "columns") {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.width(44.dp))
                WeekRhythm.segmentLabels.forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1)
                }
                Text("Ends", style = MaterialTheme.typography.labelSmall, color = c.muted, textAlign = TextAlign.End, modifier = Modifier.width(48.dp).padding(end = 8.dp))
            }
        }
        items(week.rows, key = { it.date.toString() }) { row ->
            val open = expanded == row.date.toString()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DayRow(row, open, onClick = { expanded = if (open) null else row.date.toString() })
                if (open) DayBlocks(row)
            }
        }
        item(key = "legend") {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Legend("Work", c.accent)
                Legend("Extended", c.amberLine)
                Legend("Fixed", c.fixedFill)
                if (week.hasEstimatedTimes) Text("~ estimated", style = MaterialTheme.typography.labelSmall, color = c.muted)
            }
        }
        item(key = "rollover") {
            RhythmCard(Modifier.padding(top = 14.dp), padding = PaddingValues(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Next week from your template", style = MaterialTheme.typography.titleMedium)
                        Text("Re-timed to ${week.nextWeekLabel} prayer times. You approve before anything is written.", style = MaterialTheme.typography.bodySmall, color = c.muted)
                    }
                    if (ui.busy) CircularProgressIndicator(color = c.accent)
                    else PillButton("Preview", actions.onCopyTemplate, contentDescription = "Copy template into the week of ${week.nextWeekLabel}")
                }
            }
        }
        item(key = "reflect") {
            QuietButton("Reflect: sleep, focus and social time", actions.onOpenReflect, Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun Legend(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Swatch(color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = RhythmTheme.colors.muted)
    }
}

@Composable
private fun DayRow(row: WeekRow, open: Boolean, onClick: () -> Unit) {
    val c = RhythmTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (row.isToday) c.surface else c.ground)
            .clickable(onClickLabel = if (open) "Hide blocks" else "Show blocks", onClick = onClick)
            .heightIn(min = 52.dp)
            .padding(start = 8.dp, top = 10.dp, bottom = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = row.description
                stateDescription = if (open) "Expanded" else "Collapsed"
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(44.dp)) {
            Text(row.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            if (row.sub.isNotBlank()) Text(row.sub, style = MaterialTheme.typography.labelSmall, color = c.muted, maxLines = 1)
        }
        row.segments.forEach { seg ->
            val fill = when (seg.kind) {
                FillKind.WORK -> c.accent
                FillKind.EXTENDED -> c.amberLine
                FillKind.FIXED -> c.fixedFill
                FillKind.EMPTY -> c.hairline
            }
            Box(Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(8.dp)).background(c.hairline), contentAlignment = Alignment.BottomCenter) {
                if (seg.fraction > 0f) Box(Modifier.fillMaxWidth().fillMaxHeight(seg.fraction).background(fill))
            }
        }
        Text(
            row.endLabel,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = if (row.late) c.amberText else c.ink,
            textAlign = TextAlign.End,
            modifier = Modifier.width(48.dp).padding(end = 8.dp),
        )
    }
}

@Composable
private fun DayBlocks(row: WeekRow) {
    val c = RhythmTheme.colors
    Column(Modifier.padding(start = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (row.flags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.flags.take(2).forEach { FlagChip(it, warning = !it.startsWith("Uses")) } }
        }
        if (row.blocks.isEmpty()) Text("Nothing planned", style = MaterialTheme.typography.bodySmall, color = c.muted)
        row.blocks.forEach { b ->
            RhythmCard(amber = b.tone == BlockTone.EXTENDED, color = if (b.isFixed) c.ground else c.surface) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.title, style = MaterialTheme.typography.titleMedium)
                        Text(b.detail, style = MaterialTheme.typography.bodySmall, color = if (b.tone == BlockTone.EXTENDED) c.amberText else c.muted)
                    }
                    Text(b.time, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
