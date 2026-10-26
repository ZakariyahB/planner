package app.planner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.model.Task
import app.planner.model.TaskStatus
import app.planner.plan.DayView
import app.planner.scheduler.Block
import app.planner.ui.theme.LocalStatusColors
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

private val HourHeight = 56.dp
private val GutterWidth = 40.dp
private const val SNAP_MIN = 15
private const val MIN_BLOCK_MIN = 15

@Composable
fun CalendarScreen(vm: PlanViewModel) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    val start by vm.visibleStart.collectAsStateWithLifecycle()
    val days by vm.days.collectAsStateWithLifecycle()
    val tasks by vm.tasksById.collectAsStateWithLifecycle()
    val problem by vm.syncProblem.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Block?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CalendarMode.entries.forEach { m ->
                FilterChip(selected = m == mode, onClick = { vm.setMode(m) }, label = { Text(m.label) })
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { vm.shift(-1) }) { Text("‹") }
            TextButton(onClick = vm::goToToday) { Text(start.format(dateFormat)) }
            TextButton(onClick = { vm.shift(1) }) { Text("›") }
            OutlinedButton(onClick = vm::replan) { Text("Replan") }
        }
        problem?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }

        DayHeaders(days)
        Timeline(days, tasks, onMove = vm::move, onSelect = { selected = it })
    }

    selected?.let { block ->
        val task = block.taskId?.let(tasks::get)
        BlockDialog(
            block = block,
            task = task,
            onDone = { task?.let(vm::toggleDone); selected = null },
            onLock = { vm.toggleLock(block); selected = null },
            onRemove = { vm.removeFromPlan(block); selected = null },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun DayHeaders(days: List<DayView>) {
    val today = LocalDate.now()
    Row(Modifier.fillMaxWidth().padding(start = GutterWidth)) {
        days.forEach { day ->
            Column(Modifier.weight(1f).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "${day.date.dayOfWeek.short()} ${day.date.dayOfMonth}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (day.date == today) FontWeight.Bold else FontWeight.Normal,
                    color = if (day.date == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                day.allDay.firstOrNull()?.let {
                    Text(it.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun Timeline(
    days: List<DayView>,
    tasks: Map<Long, Task>,
    onMove: (blockId: Long, date: LocalDate, startMin: Int, endMin: Int) -> Unit,
    onSelect: (Block) -> Unit,
) {
    val density = LocalDensity.current
    val scroll = rememberScrollState(initial = with(density) { (HourHeight * 7).roundToPx() }) // open at 07:00
    val lineColour = MaterialTheme.colorScheme.outlineVariant

    Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
        Box(Modifier.width(GutterWidth).height(HourHeight * 24)) {
            for (h in 1..23) {
                Text(
                    "%02d:00".format(h),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.offset(y = HourHeight * h - 8.dp).padding(start = 4.dp),
                )
            }
        }
        days.forEach { day ->
            DayColumn(day, tasks, lineColour, onMove, onSelect, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DayColumn(
    day: DayView,
    tasks: Map<Long, Task>,
    lineColour: Color,
    onMove: (Long, LocalDate, Int, Int) -> Unit,
    onSelect: (Block) -> Unit,
    modifier: Modifier,
) {
    val zone = ZoneId.systemDefault()
    val density = LocalDensity.current
    val pxPerMin = with(density) { HourHeight.toPx() } / 60f

    Box(modifier.height(HourHeight * 24)) {
        Canvas(Modifier.fillMaxSize()) {
            for (h in 0..24) drawLine(lineColour, Offset(0f, h * 60 * pxPerMin), Offset(size.width, h * 60 * pxPerMin), strokeWidth = 1f)
            drawLine(lineColour, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1f)
        }

        day.asleep.forEach { a ->
            val s = minuteOn(day.date, a.start, zone)
            val en = minuteOn(day.date, a.end, zone)
            if (en > s) {
                Box(
                    Modifier
                        .offset(y = HourHeight * (s / 60f))
                        .height(HourHeight * ((en - s) / 60f))
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f))
                        .padding(3.dp),
                ) {
                    Text("Asleep", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        day.fixed.forEach { e ->
            val s = minuteOn(day.date, e.start, zone)
            val en = minuteOn(day.date, e.end, zone)
            if (en > s) {
                val colour = parseColour(day.colours[e.calendarId]) ?: MaterialTheme.colorScheme.onSurfaceVariant
                Box(
                    Modifier
                        .offset(y = HourHeight * (s / 60f))
                        .height(HourHeight * ((en - s) / 60f))
                        .fillMaxWidth()
                        .padding(horizontal = 1.dp)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .background(colour.copy(alpha = 0.28f))
                        .padding(3.dp),
                ) {
                    Text(e.title, style = MaterialTheme.typography.labelMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        day.prayers.forEach { (prayer, time) ->
            Box(Modifier.offset(y = HourHeight * ((time.hour * 60 + time.minute) / 60f)).fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)))
                Text(prayer.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
            }
        }

        day.blocks.forEach { block ->
            val task = block.taskId?.let(tasks::get)
            BlockBox(block, task, pxPerMin, onMove, onSelect)
        }

        if (day.date == LocalDate.now(zone)) {
            val now = LocalTime.now(zone)
            Box(
                Modifier
                    .offset(y = HourHeight * ((now.hour * 60 + now.minute) / 60f))
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/** Drag the body to move (snaps to 15 min), drag the bottom edge to resize; tap for details, lock and done. */
@Composable
private fun BlockBox(
    block: Block,
    task: Task?,
    pxPerMin: Float,
    onMove: (Long, LocalDate, Int, Int) -> Unit,
    onSelect: (Block) -> Unit,
) {
    val density = LocalDensity.current
    val startMin = block.start.hour * 60 + block.start.minute
    val endMin = block.end.hour * 60 + block.end.minute
    var moveDy by remember(block.id, startMin, endMin) { mutableFloatStateOf(0f) }
    var resizeDy by remember(block.id, startMin, endMin) { mutableFloatStateOf(0f) }
    val done = task?.status == TaskStatus.DONE
    val accent = if (done) LocalStatusColors.current.good else MaterialTheme.colorScheme.primary

    fun snapped(px: Float): Int = ((px / pxPerMin) / SNAP_MIN).roundToInt() * SNAP_MIN

    Box(
        Modifier
            .offset { IntOffset(0, (startMin * pxPerMin + moveDy).roundToInt()) }
            .padding(horizontal = 2.dp)
            .fillMaxWidth()
            .height(with(density) { ((endMin - startMin) * pxPerMin + resizeDy).coerceAtLeast(MIN_BLOCK_MIN * pxPerMin).toDp() })
            .clip(MaterialTheme.shapes.extraSmall)
            .background(accent.copy(alpha = 0.22f))
            .pointerInput(block.id, startMin, endMin) {
                detectTapGestures(onTap = { onSelect(block) })
            }
            .then(
                // A locked block stays put: unlock it from its menu to move or resize it.
                if (block.locked) Modifier else Modifier.pointerInput(block.id, startMin, endMin) {
                    detectDragGestures(
                        onDragEnd = {
                            val delta = snapped(moveDy)
                            moveDy = 0f
                            if (delta != 0) onMove(block.id, block.date, startMin + delta, endMin + delta)
                        },
                        onDragCancel = { moveDy = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            moveDy += amount.y
                        },
                    )
                },
            ),
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            Text(
                (if (block.locked) "🔒 " else "") + (task?.title ?: "Block"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${timeFormat.format(block.start)}–${timeFormat.format(block.end)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (!block.locked) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(14.dp)
                    .pointerInput(block.id, startMin, endMin) {
                        detectDragGestures(
                            onDragEnd = {
                                val delta = snapped(resizeDy)
                                resizeDy = 0f
                                if (delta != 0) onMove(block.id, block.date, startMin, endMin + delta)
                            },
                            onDragCancel = { resizeDy = 0f },
                            onDrag = { change, amount ->
                                change.consume()
                                resizeDy += amount.y
                            },
                        )
                    },
            ) {
                Box(Modifier.align(Alignment.Center).width(24.dp).height(3.dp).clip(MaterialTheme.shapes.extraSmall).background(accent))
            }
        }
    }
}

@Composable
private fun BlockDialog(block: Block, task: Task?, onDone: () -> Unit, onLock: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(task?.title ?: "Block") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${block.date.format(dateFormat)} · ${timeFormat.format(block.start)}–${timeFormat.format(block.end)}")
                task?.let { Text(taskMeta(it, ZoneId.systemDefault()), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (block.locked) Text("Locked: the planner keeps it here", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.padding(top = 8.dp)) {
                    if (task != null) TextButton(onClick = onDone) { Text(if (task.status == TaskStatus.DONE) "Mark not done" else "Mark done") }
                    TextButton(onClick = onLock) { Text(if (block.locked) "Unlock" else "Lock here") }
                    TextButton(onClick = onRemove) { Text("Remove from plan") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun minuteOn(date: LocalDate, instant: java.time.Instant, zone: ZoneId): Int {
    val local = instant.atZone(zone)
    return when {
        local.toLocalDate() < date -> 0
        local.toLocalDate() > date -> 24 * 60
        else -> local.hour * 60 + local.minute
    }
}

private fun parseColour(hex: String?): Color? =
    hex?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() }
