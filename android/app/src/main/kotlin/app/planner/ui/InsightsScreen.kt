package app.planner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.insights.DoneRow
import app.planner.insights.LightsOutInsights
import app.planner.insights.LightsOutRow
import app.planner.insights.PlannedVsDone
import app.planner.insights.SleepInsights
import app.planner.insights.SleepRow
import app.planner.insights.SocialInsights
import app.planner.insights.SocialRow
import app.planner.insights.WeeklyReview
import app.planner.social.SocialNudges
import app.planner.ui.theme.LocalStatusColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun InsightsScreen(vm: InsightsViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Insights", style = MaterialTheme.typography.headlineMedium)
                val from = ui.from
                val to = ui.to
                if (from != null && to != null) Text(rangeLabel(from, to), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            !ui.loaded -> item { Text("Working it out…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            !ui.hasAnyData -> item { EmptyInsights() }
            else -> {
                ui.review?.let { item { ReviewSection(it) } }
                if (ui.sleep.isNotEmpty()) item { SleepSection(ui.sleep) }
                if (ui.lights.isNotEmpty()) item { LightsOutSection(ui.lights) }
                if (ui.done.any { it.plannedMin > 0 }) item { DoneSection(ui.done) }
                if (ui.social.isNotEmpty()) item { SocialSection(ui.social) }
            }
        }
        item { Box(Modifier.height(8.dp)) }
    }
}

@Composable
private fun EmptyInsights() {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Nothing to show yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Insights fill in over the first few days: sleep once Health Connect has some nights, planned and finished work once you have used the plan, and social time once usage access is on.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReviewSection(review: WeeklyReview) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                "Asleep per night",
                review.avgAsleepMin?.let { SocialNudges.duration(it) } ?: "–",
                "need 7h",
                Modifier.weight(1f),
            )
            StatTile(
                "Lights-out",
                review.avgLightsOutMin?.let { LightsOutInsights.label(it) } ?: "–",
                if ((review.avgLightsOutMin ?: 0) < 0) "before 00:15" else "after 00:15",
                Modifier.weight(1f),
            )
            StatTile(
                "Plan done",
                review.donePercent?.let { "$it%" } ?: "–",
                if (review.plannedMin > 0) "of ${SocialNudges.duration(review.plannedMin)}" else "nothing planned",
                Modifier.weight(1f),
                valueColour = if (review.donePercent != null) LocalStatusColors.current.good else Color.Unspecified,
            )
        }
        review.sentences.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun StatTile(label: String, value: String, note: String, modifier: Modifier, valueColour: Color = Color.Unspecified) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, color = valueColour)
        Text(note, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChartHeader(title: String, note: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SleepSection(rows: List<SleepRow>) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val blue = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChartHeader("Sleep", "Time asleep, last 7 nights. The line is your 00:15 target.")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Hour labels along the 21:00 to 12:00 track, lined up with the bars below.
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.width(LABEL_WIDTH))
                BoxWithConstraints(Modifier.weight(1f).height(16.dp)) {
                    SleepInsights.AXIS.forEach { mark ->
                        Text(
                            mark.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.offset(x = (maxWidth * mark.position.toFloat() - AXIS_LABEL_WIDTH / 2).coerceIn(0.dp, maxWidth - AXIS_LABEL_WIDTH)),
                        )
                    }
                }
                Box(Modifier.width(VALUE_WIDTH))
            }
            rows.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(row.weekday, Modifier.width(LABEL_WIDTH), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BoxWithConstraints(Modifier.weight(1f).height(20.dp).background(track, RoundedCornerShape(6.dp))) {
                        Box(Modifier.offset(x = maxWidth * SleepInsights.TARGET_POSITION.toFloat()).width(1.5.dp).fillMaxHeight().background(blue.copy(alpha = 0.8f)))
                        row.bars.forEachIndexed { index, bar ->
                            Box(
                                Modifier
                                    .offset(x = maxWidth * bar.start.toFloat())
                                    .width(maxWidth * (bar.end - bar.start).toFloat())
                                    .fillMaxHeight()
                                    .padding(vertical = 3.dp)
                                    .background(blue.copy(alpha = if (index == 0) 1f else 0.55f), RoundedCornerShape(4.dp)),
                            )
                        }
                    }
                    Text(SocialNudges.duration(row.asleepMin), Modifier.width(VALUE_WIDTH), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
                }
            }
        }
    }
}

@Composable
private fun LightsOutSection(rows: List<LightsOutRow>) {
    val good = LocalStatusColors.current.good
    val blue = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChartHeader("Lights-out against 00:15", "Minutes after the target. Green is on time or early.")
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.weekday, Modifier.width(LABEL_WIDTH), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f).height(10.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth(LightsOutInsights.barFraction(row.minutesAfterTarget).toFloat().coerceAtLeast(0.03f))
                            .fillMaxHeight()
                            .background(if (row.minutesAfterTarget <= 0) good else blue, RoundedCornerShape(5.dp)),
                    )
                }
                Text(LightsOutInsights.label(row.minutesAfterTarget), Modifier.width(VALUE_WIDTH + 8.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun DoneSection(rows: List<DoneRow>) {
    val blue = MaterialTheme.colorScheme.primary
    val heights = PlannedVsDone.heightFractions(rows)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChartHeader("Planned and done", "The outline is what was planned; the fill is what you finished.")
        Row(Modifier.fillMaxWidth().height(136.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            rows.forEachIndexed { index, row ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val barHeight = if (row.plannedMin == 0) 4.dp else 108.dp * heights[index].toFloat().coerceAtLeast(0.08f)
                    Box(Modifier.fillMaxWidth().height(barHeight).border(1.5.dp, blue.copy(alpha = if (row.plannedMin == 0) 0.4f else 1f), RoundedCornerShape(6.dp))) {
                        val filled = row.percent ?: 0
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .fillMaxHeight(filled / 100f)
                                .background(blue, RoundedCornerShape(5.dp)),
                        )
                    }
                    Text(row.weekday, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("Planned", Modifier.border(1.5.dp, blue, RoundedCornerShape(3.dp)))
            Legend("Done", Modifier.background(blue, RoundedCornerShape(3.dp)))
        }
    }
}

@Composable
private fun Legend(label: String, swatch: Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(swatch.size(12.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SocialSection(rows: List<SocialRow>) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val blue = MaterialTheme.colorScheme.primary
    val warn = LocalStatusColors.current.warning
    val warnText = LocalStatusColors.current.warningText
    val ink = MaterialTheme.colorScheme.onSurface
    val max = SocialInsights.axisMaxMin(rows)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChartHeader("Social media", "Minutes a day. Amber means over the budget (the marker).")
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.weekday, Modifier.width(LABEL_WIDTH - 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BoxWithConstraints(Modifier.weight(1f).height(12.dp).background(track, RoundedCornerShape(4.dp))) {
                    Box(
                        Modifier
                            .width(maxWidth * SocialInsights.fraction(row.minutes, max).toFloat())
                            .fillMaxHeight()
                            .background(if (row.over) warn else blue, RoundedCornerShape(4.dp)),
                    )
                    Box(Modifier.offset(x = maxWidth * SocialInsights.fraction(row.budgetMin, max).toFloat()).width(2.dp).fillMaxHeight().background(ink))
                }
                Text(row.label, Modifier.width(VALUE_WIDTH + 8.dp), style = MaterialTheme.typography.labelMedium, color = if (row.over) warnText else ink, textAlign = TextAlign.End)
            }
        }
    }
}

private val LABEL_WIDTH: Dp = 36.dp
private val VALUE_WIDTH: Dp = 56.dp
private val AXIS_LABEL_WIDTH: Dp = 34.dp

private fun rangeLabel(from: LocalDate, to: LocalDate): String {
    val day = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
    return if (from.month == to.month) "Week of ${from.dayOfMonth} to ${DateTimeFormatter.ofPattern("d MMMM", Locale.UK).format(to)}"
    else "Week of ${day.format(from)} to ${day.format(to)}"
}
