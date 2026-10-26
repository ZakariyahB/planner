package app.planner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Surface
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.empty.EmptyStates
import app.planner.model.Task
import app.planner.model.DayTaskList
import app.planner.model.Weeks
import app.planner.calendar.CalendarEvent
import app.planner.calendar.CalendarLayout
import app.planner.calendar.CalendarSpan
import app.planner.calendar.CalendarLane
import app.planner.calendar.EventTiming
import app.planner.calendar.FixedEvent
import app.planner.model.TaskStatus
import app.planner.plan.DayView
import app.planner.scheduler.Block
import app.planner.ui.theme.LocalStatusColors
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val HourHeight = 64.dp
private val GutterWidth: Dp
    @Composable get() = 44.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
private const val SNAP_MIN = 15
private const val MIN_BLOCK_MIN = 15

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun CalendarScreen(vm: PlanViewModel, calendarConnected: Boolean, onEditTask: (Task) -> Unit, onLogProgress: (Task) -> Unit) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    val start by vm.visibleStart.collectAsStateWithLifecycle()
    val days by vm.days.collectAsStateWithLifecycle()
    val tasks by vm.tasksById.collectAsStateWithLifecycle()
    val problem by vm.syncProblem.collectAsStateWithLifecycle()
    val allBlocks by vm.scheduledBlocks.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Block?>(null) }
    var fixedSelection by remember { mutableStateOf<FixedEvent?>(null) }
    var allDaySelection by remember { mutableStateOf<CalendarEvent?>(null) }
    var pickingDate by remember { mutableStateOf(false) }
    val zone = ZoneId.systemDefault()
    val placements = (allBlocks + days.flatMap { it.blocks }).distinctBy { it.id }
    val untimed = days.associate { day -> day.date to DayTaskList.on(day.date, LocalDate.now(zone), zone, tasks.values, placements, summary.overflow.map { it.id }.toSet()).untimed }

    Column(Modifier.fillMaxSize()) {
        Text("Your plan", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
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
            IconButton(onClick = { vm.shift(-1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous ${mode.label.lowercase()}") }
            TextButton(onClick = { pickingDate = true }, modifier = Modifier.weight(1f)) { Text(start.format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            IconButton(onClick = { vm.shift(1) }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next ${mode.label.lowercase()}") }
            TextButton(onClick = vm::replan) { Text("Adjust") }
        }
        problem?.let { Text(it, modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }

        if (days.isNotEmpty() && days.all { it.blocks.isEmpty() && it.fixed.isEmpty() && it.allDay.isEmpty() } && untimed.values.all { it.isEmpty() }) {
            val waiting = tasks.values.count { it.status == TaskStatus.OPEN || it.status == TaskStatus.PARTIAL }
            EmptyStateCard(EmptyStates.calendar(waiting, calendarConnected), Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Button(onClick = vm::replan, modifier = Modifier.heightIn(min = 48.dp)) { Text("Replan") }
            }
        }
        if (mode == CalendarMode.DAY) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                val monday = Weeks.mondayOf(start)
                repeat(7) { index ->
                    val date = monday.plusDays(index.toLong())
                    Surface(onClick = { vm.goToDate(date) }, shape = MaterialTheme.shapes.medium, color = if (date == start) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.background, modifier = Modifier.weight(1f)) {
                        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(date.dayOfWeek.short().take(1), style = MaterialTheme.typography.labelSmall)
                            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val contentWidth = maxOf(maxWidth, GutterWidth + 112.dp * days.size)
            val dayTopHeight = minOf(200.dp, maxHeight * .4f)
            Column(Modifier.horizontalScroll(rememberScrollState())) {
                Column(Modifier.width(contentWidth).fillMaxSize()) {
                    if (mode != CalendarMode.DAY) DayHeaders(days)
                    DayTopRows(days, untimed, dayTopHeight, onEvent = { allDaySelection = it }, onTask = onEditTask, onToggle = vm::toggleDone)
                    Timeline(days, tasks, onMove = vm::move, onSelect = { selected = it }, onFixed = { fixedSelection = it }, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    selected?.let { block ->
        val task = block.taskId?.let(tasks::get)
        BlockDialog(
            block = block,
            task = task,
            onDone = { task?.let(vm::toggleDone); selected = null },
            onLock = { vm.toggleLock(block); selected = null },
            onRemove = { vm.removeFromPlan(block); selected = null },
            onEdit = { task?.let(onEditTask); selected = null },
            onProgress = { task?.let(onLogProgress); selected = null },
            onDismiss = { selected = null },
        )
    }
    allDaySelection?.let { event ->
        AlertDialog(onDismissRequest = { allDaySelection = null }, title = { Text(event.title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("All day · No start or end time", color = MaterialTheme.colorScheme.primary)
                (event.timing as? EventTiming.AllDay)?.let { Text("${it.start.format(dateFormat)} – ${it.endExclusive.minusDays(1).format(dateFormat)}") }
                Text("This calendar event gives context to your day. It does not occupy the hourly schedule.")
            }
        }, confirmButton = { TextButton(onClick = { allDaySelection = null }) { Text("Close") } })
    }
    fixedSelection?.let { event ->
        AlertDialog(onDismissRequest = { fixedSelection = null }, title = { Text(event.title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fixed commitment", color = MaterialTheme.colorScheme.primary)
                Text("${event.start.atZone(zone).format(timeFormat)}–${event.end.atZone(zone).format(timeFormat)}")
                Text("Planner works around this commitment. Calendar events can be edited in their source calendar.")
            }
        }, confirmButton = { TextButton(onClick = { fixedSelection = null }) { Text("Close") } })
    }
    if (pickingDate) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = start.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickingDate = false }, confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let { vm.goToDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; pickingDate = false }) { Text("View day") }
        }, dismissButton = { TextButton(onClick = { vm.goToToday(); pickingDate = false }) { Text("Today") } }) { DatePicker(picker) }
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
            }
        }
    }
}

/** All events are shown, including multi-day events on each covered date; no fabricated midnight blocks. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DayTopRows(days: List<DayView>, untimed: Map<LocalDate, List<Task>>, maxHeight: Dp, onEvent: (CalendarEvent) -> Unit, onTask: (Task) -> Unit, onToggle: (Task) -> Unit) {
    if (days.none { it.allDay.isNotEmpty() } && untimed.values.all { it.isEmpty() }) return
    Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (days.any { it.allDay.isNotEmpty() }) Row(Modifier.fillMaxWidth()) {
            Text("All\nday", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(GutterWidth).padding(start = 6.dp, top = 8.dp))
            days.forEach { day ->
                FlowRow(Modifier.weight(1f).padding(horizontal = 3.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    day.allDay.forEach { event ->
                        Surface(onClick = { onEvent(event) }, color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = if (days.size == 1) Modifier else Modifier.fillMaxWidth()) {
                            Text(event.title, Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        if (untimed.values.any { it.isNotEmpty() }) Row(Modifier.fillMaxWidth()) {
            Text("Any\ntime", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(GutterWidth).padding(start = 6.dp, top = 10.dp))
            days.forEach { day ->
                Column(Modifier.weight(1f).padding(horizontal = 3.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    untimed[day.date].orEmpty().forEach { task ->
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = task.status == TaskStatus.DONE, onCheckedChange = { onToggle(task) })
                                Text(task.title, Modifier.weight(1f).clickable { onTask(task) }.padding(vertical = 8.dp, horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
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
    onFixed: (FixedEvent) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val scroll = rememberScrollState(initial = with(density) { (HourHeight * 7 - 12.dp).roundToPx() }) // leave room for the 07:00 label
    val lineColour = MaterialTheme.colorScheme.outlineVariant

    Row(modifier.fillMaxWidth().verticalScroll(scroll)) {
        Box(Modifier.width(GutterWidth).height(HourHeight * 24)) {
            for (h in 1..23) {
                Text(
                    "%02d:00".format(h),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    softWrap = false,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.offset(y = HourHeight * h - 8.dp).padding(start = 4.dp),
                )
            }
        }
        days.forEach { day ->
            DayColumn(day, tasks, lineColour, onMove, onSelect, onFixed, Modifier.weight(1f))
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
    onFixed: (FixedEvent) -> Unit,
    modifier: Modifier,
) {
    val zone = ZoneId.systemDefault()
    val density = LocalDensity.current
    val pxPerMin = with(density) { HourHeight.toPx() } / 60f

    val lanes = CalendarLayout.lanes(day.fixed.map { CalendarSpan("fixed:${it.calendarId}:${it.id}", minuteOn(day.date, it.start, zone), minuteOn(day.date, it.end, zone)) } + day.blocks.map { CalendarSpan("block:${it.id}", it.start.hour * 60 + it.start.minute, it.end.hour * 60 + it.end.minute) })
    BoxWithConstraints(modifier.height(HourHeight * 24)) {
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
                val lane = lanes["fixed:${e.calendarId}:${e.id}"] ?: CalendarLane(0, 1)
                val width = maxWidth / lane.count
                Surface(
                    onClick = { onFixed(e) },
                    Modifier
                        .offset(x = width * lane.lane)
                        .offset(y = HourHeight * (s / 60f))
                        .height(HourHeight * ((en - s) / 60f))
                        .width(width).padding(2.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        Text(e.title, style = MaterialTheme.typography.labelLarge, maxLines = if (en - s < 45) 1 else 2, overflow = TextOverflow.Ellipsis)
                        if (en - s >= 60) Text("${e.start.atZone(zone).format(timeFormat)}–${e.end.atZone(zone).format(timeFormat)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }

        day.prayers.forEach { (prayer, time) ->
            Box(Modifier.offset(y = HourHeight * ((time.hour * 60 + time.minute) / 60f)).fillMaxWidth()) {
                val prayerColour = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f)
                Canvas(Modifier.fillMaxWidth().height(1.dp)) { drawLine(prayerColour, Offset.Zero, Offset(size.width, 0f), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))) }
                Text(prayer.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
            }
        }

        day.blocks.forEach { block ->
            val task = block.taskId?.let(tasks::get)
            val lane = lanes["block:${block.id}"] ?: CalendarLane(0, 1)
            val width = maxWidth / lane.count
            BlockBox(block, task, pxPerMin, onMove, onSelect, Modifier.offset(x = width * lane.lane).width(width))
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
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val startMin = block.start.hour * 60 + block.start.minute
    val endMin = block.end.hour * 60 + block.end.minute
    var moveDy by remember(block.id, startMin, endMin) { mutableFloatStateOf(0f) }
    var resizeDy by remember(block.id, startMin, endMin) { mutableFloatStateOf(0f) }
    val done = task?.status == TaskStatus.DONE
    val accent = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary

    fun snapped(px: Float): Int = ((px / pxPerMin) / SNAP_MIN).roundToInt() * SNAP_MIN

    Box(
        modifier
            .offset { IntOffset(0, (startMin * pxPerMin + moveDy).roundToInt()) }
            .padding(horizontal = 2.dp)
            .fillMaxWidth()
            .height(with(density) { ((endMin - startMin) * pxPerMin + resizeDy).coerceAtLeast(MIN_BLOCK_MIN * pxPerMin).toDp() })
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (done) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primaryContainer)
            .clickable { onSelect(block) }
            .semantics { contentDescription = "${task?.title ?: "Task"}, ${timeFormat.format(block.start)} to ${timeFormat.format(block.end)}${if (block.locked) ", locked" else ""}" }
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
        Box(Modifier.width(3.dp).fillMaxSize().background(accent))
        Column(Modifier.padding(horizontal = 9.dp, vertical = 5.dp)) {
            Text(
                (if (block.locked) "🔒 " else "") + (task?.title ?: "Block"),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (block.minutes >= 60) Text("${timeFormat.format(block.start)}–${timeFormat.format(block.end)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
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
private fun BlockDialog(block: Block, task: Task?, onDone: () -> Unit, onLock: () -> Unit, onRemove: () -> Unit, onEdit: () -> Unit, onProgress: () -> Unit, onDismiss: () -> Unit) {
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
                    if (task != null) TextButton(onClick = onEdit) { Text("Edit task") }
                    if (task != null && task.status != TaskStatus.DONE) TextButton(onClick = onProgress) { Text("Log progress") }
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
