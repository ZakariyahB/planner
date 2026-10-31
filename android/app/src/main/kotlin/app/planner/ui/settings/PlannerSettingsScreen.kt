package app.planner.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.checkin.CheckInSetupCard
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.NotificationPrefs
import app.planner.engine.model.Prayer
import app.planner.engine.model.Settings
import app.planner.engine.ports.AuthState
import app.planner.theme.ThemeMode
import app.planner.ui.SettingsPage
import app.planner.ui.TimePickerDialog
import app.planner.ui.model.Fmt
import app.planner.ui.rhythm.GroupLabel
import app.planner.ui.rhythm.PillButton
import app.planner.ui.rhythm.PillKind
import app.planner.ui.rhythm.RhythmCard
import app.planner.ui.rhythm.ScreenHeader
import app.planner.ui.theme.RhythmTheme
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle
import java.util.Locale

class SettingsActions(
    val onUpdate: ((Settings) -> Settings) -> Unit = {},
    val onSignIn: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onTheme: (ThemeMode) -> Unit = {},
    val onOpenReflect: () -> Unit = {},
    val onOpenLegacy: (SettingsPage) -> Unit = {},
    val onBack: () -> Unit = {},
)

@Composable
fun PlannerSettingsRoute(vm: PlannerSettingsViewModel, themeMode: ThemeMode, actions: SettingsActions) {
    val ui by vm.state.collectAsStateWithLifecycle()
    Surface(Modifier.fillMaxSize(), color = RhythmTheme.colors.ground) {
        val state = ui
        if (state == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = RhythmTheme.colors.accent) }
        } else {
            PlannerSettingsContent(state, themeMode, actions)
        }
    }
}

@Composable
fun PlannerSettingsContent(ui: SettingsUi, themeMode: ThemeMode, actions: SettingsActions) {
    val c = RhythmTheme.colors
    val s = ui.settings
    val n = s.notifications
    val set = actions.onUpdate
    fun prefs(f: (NotificationPrefs) -> NotificationPrefs) = set { it.copy(notifications = f(it.notifications)) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back", tint = c.ink) }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { ScreenHeader("Your rhythm, your rules", "Settings") }

            group("Google Calendar") {
                val (title, detail, action) = when (ui.auth) {
                    AuthState.SIGNED_IN -> Triple("Signed in", ui.lastSync?.let { st -> (if (st.ok) "Last sync " else "Last sync failed ") + Fmt.time(st.at.atZone(s.zone).toLocalDateTime()) + (st.message?.let { ": $it" } ?: "") } ?: "Not synced yet", null)
                    AuthState.EXPIRED -> Triple("Sign-in expired", "Google asks again every 7 days while the project is in testing. Nothing is written until you sign in.", "Sign in again")
                    AuthState.SIGNED_OUT -> Triple("Not connected", "Connect to read your calendars and write approved changes.", "Connect")
                }
                RhythmCard {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = if (ui.auth == AuthState.SIGNED_IN) c.ink else c.amberText)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = c.muted)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (action != null) PillButton(action, actions.onSignIn)
                        PillButton("Sync now", actions.onRefresh, kind = PillKind.SECONDARY)
                        if (ui.busy) CircularProgressIndicator(color = c.accent)
                    }
                }
            }

            item { CheckInSetupCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) }

            group("Your day") {
                TimeRow("Start of the work day", s.wakeTime) { t -> set { it.copy(wakeTime = t) } }
                TimeRow("Normal end", s.normalEnd) { t -> set { it.copy(normalEnd = t) } }
                TimeRow("Extended end", s.extendedEnd) { t -> set { it.copy(extendedEnd = t) } }
                TimeRow("Hard cap (never later)", s.hardCap) { t -> set { it.copy(hardCap = t) } }
                Text("Before Maghrib is normal time; Maghrib to the normal end is the rest zone, used only when the day is full.", style = MaterialTheme.typography.bodySmall, color = c.muted)
            }

            group("Prayers and blocks") {
                StepRow("Prayer slot", "${s.prayerSlotMin} min", { set { it.copy(prayerSlotMin = (it.prayerSlotMin - 5).coerceAtLeast(5)) } }, { set { it.copy(prayerSlotMin = (it.prayerSlotMin + 5).coerceAtMost(45)) } })
                listOf(Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA).forEach { p ->
                    val off = s.prayerOffsetMin[p] ?: 0
                    StepRow(
                        "${Fmt.prayer(p)} offset",
                        if (off == 0) "at begin time" else (if (off > 0) "+" else "−") + "${kotlin.math.abs(off)} min",
                        { set { it.copy(prayerOffsetMin = it.prayerOffsetMin + (p to ((it.prayerOffsetMin[p] ?: 0) - 5).coerceAtLeast(-60))) } },
                        { set { it.copy(prayerOffsetMin = it.prayerOffsetMin + (p to ((it.prayerOffsetMin[p] ?: 0) + 5).coerceAtMost(60))) } },
                    )
                }
                StepRow("Smallest chunk when splitting", Fmt.duration(s.minChunkMin), { set { it.copy(minChunkMin = (it.minChunkMin - 15).coerceAtLeast(15)) } }, { set { it.copy(minChunkMin = (it.minChunkMin + 15).coerceAtMost(180)) } })
                StepRow("Lecture recordings ready after", Fmt.duration(s.recordingDelayMin), { set { it.copy(recordingDelayMin = (it.recordingDelayMin - 15).coerceAtLeast(0)) } }, { set { it.copy(recordingDelayMin = (it.recordingDelayMin + 15).coerceAtMost(24 * 60)) } })
                Text("Catch-up day (work that does not fit goes here)", style = MaterialTheme.typography.bodyMedium)
                ChipRow(DayOfWeek.entries, s.catchUpDay, { it.getDisplayName(TextStyle.SHORT, Locale.UK) }) { d -> set { it.copy(catchUpDay = d) } }
            }

            group("Calendars the app may change (with your approval)") {
                CalendarCategory.entries.forEach { cat ->
                    SwitchRow(cat.calendarName, null, cat in s.writableCategories) { on ->
                        set { it.copy(writableCategories = if (on) it.writableCategories + cat else it.writableCategories - cat) }
                    }
                }
                Text("Prayer times is always read-only.", style = MaterialTheme.typography.bodySmall, color = c.muted)
                SwitchRow("Turn template items into timed blocks", "Scheduling edits the all-day “1. PintOS (3h)” event instead of adding a copy", s.convertAllDayOnSchedule) { on -> set { it.copy(convertAllDayOnSchedule = on) } }
                SwitchRow("Strip week tags on rollover", "Remove tags like “w-1” when copying the template into next week", s.stripWeekTagsOnRollover) { on -> set { it.copy(stripWeekTagsOnRollover = on) } }
            }

            group("Check-ins") {
                SwitchRow("Start and end check-ins", null, n.checkInsEnabled) { on -> prefs { it.copy(checkInsEnabled = on) } }
                SwitchRow("One “Next up” ping for back-to-back blocks", null, n.mergeBackToBack) { on -> prefs { it.copy(mergeBackToBack = on) } }
                SwitchRow("Quiet during prayer slots", null, n.quietDuringPrayer) { on -> prefs { it.copy(quietDuringPrayer = on) } }
                SwitchRow("Prayer break ping inside long blocks", null, n.midTaskPrayerPing) { on -> prefs { it.copy(midTaskPrayerPing = on) } }
                SwitchRow("Ask how focused I was after Done", null, n.askFocusOnDone) { on -> prefs { it.copy(askFocusOnDone = on) } }
                StepRow("Start ping only for blocks under", Fmt.duration(n.startOnlyUnderMin), { prefs { it.copy(startOnlyUnderMin = (it.startOnlyUnderMin - 15).coerceAtLeast(0)) } }, { prefs { it.copy(startOnlyUnderMin = (it.startOnlyUnderMin + 15).coerceAtMost(180)) } })
                StepRow("Snooze", "${n.snoozeMin} min", { prefs { it.copy(snoozeMin = (it.snoozeMin - 5).coerceAtLeast(5)) } }, { prefs { it.copy(snoozeMin = (it.snoozeMin + 5).coerceAtMost(60)) } })
                StepRow("“Need more” adds", "${n.extendMin} min", { prefs { it.copy(extendMin = (it.extendMin - 15).coerceAtLeast(15)) } }, { prefs { it.copy(extendMin = (it.extendMin + 15).coerceAtMost(120)) } })
            }

            group("Prompts") {
                SwitchRow("Night-before 5-minute review", null, n.nightBeforeReviewEnabled) { on -> prefs { it.copy(nightBeforeReviewEnabled = on) } }
                TimeRow("Review at", n.nightBeforeReview) { t -> prefs { it.copy(nightBeforeReview = t) } }
                SwitchRow("Sunday prompts (deadlines, Fajr times)", null, n.sundayPrompts) { on -> prefs { it.copy(sundayPrompts = on) } }
                TimeRow("“Any deadlines coming up?” at", n.sundayDeadlinesAt) { t -> prefs { it.copy(sundayDeadlinesAt = t) } }
                TimeRow("Fajr times prompt at", n.fajrPromptAt) { t -> prefs { it.copy(fajrPromptAt = t) } }
                Text("Fajr prompt day", style = MaterialTheme.typography.bodyMedium)
                ChipRow(DayOfWeek.entries, n.fajrPromptDay, { it.getDisplayName(TextStyle.SHORT, Locale.UK) }) { d -> prefs { it.copy(fajrPromptDay = d) } }
            }

            group("Sleep") {
                StepRow("Sleep need", Fmt.duration(s.sleepNeedMin), { set { it.copy(sleepNeedMin = (it.sleepNeedMin - 15).coerceAtLeast(240)) } }, { set { it.copy(sleepNeedMin = (it.sleepNeedMin + 15).coerceAtMost(720)) } })
                Text("Used for readiness and coaching only, never for scheduling.", style = MaterialTheme.typography.bodySmall, color = c.muted)
            }

            group("Assistant") {
                var model by remember(s.assistantModel) { mutableStateOf(s.assistantModel) }
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, focusedLabelColor = c.accent, cursorColor = c.accent),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Save model", { val m = model.trim(); if (m.isNotEmpty()) set { it.copy(assistantModel = m) } }, kind = PillKind.SECONDARY, enabled = model.trim() != s.assistantModel && model.isNotBlank())
                    PillButton("Default", { model = Settings().assistantModel; set { it.copy(assistantModel = Settings().assistantModel) } }, kind = PillKind.SECONDARY)
                }
                LegacyRow("Assistant spend and connection", "The monthly cap and Worker status") { actions.onOpenLegacy(SettingsPage.ASSISTANT) }
            }

            group("Appearance") {
                ChipRow(ThemeMode.entries, themeMode, { it.label }) { actions.onTheme(it) }
            }

            group("Reflect and more") {
                LegacyRow("Reflect", "Sleep, focus and social time over the weeks", actions.onOpenReflect)
                LegacyRow("Sleep and Health Connect", "Where sleep data comes from") { actions.onOpenLegacy(SettingsPage.SLEEP) }
                LegacyRow("Social time", "Daily budget and nudges") { actions.onOpenLegacy(SettingsPage.SOCIAL) }
                LegacyRow("Morning brief and lights-out", "The older daily routines") { actions.onOpenLegacy(SettingsPage.NOTIFICATIONS) }
                LegacyRow("Backup and restore", "Save everything to a file, or restore one") { actions.onOpenLegacy(SettingsPage.BACKUP) }
            }
        }
    }
}

private fun LazyListScope.group(title: String, content: @Composable () -> Unit) {
    item(key = title) {
        Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            GroupLabel(title)
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = RhythmTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
        }
        Switch(checked = checked, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, checkedThumbColor = c.onAccent))
    }
}

@Composable
private fun StepRow(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodySmall, color = RhythmTheme.colors.muted)
        }
        PillButton("−", onMinus, kind = PillKind.SECONDARY, contentDescription = "Less: $title")
        PillButton("+", onPlus, kind = PillKind.SECONDARY, contentDescription = "More: $title")
    }
}

@Composable
private fun TimeRow(title: String, value: LocalTime, onChange: (LocalTime) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        PillButton(Fmt.time(value), { picking = true }, kind = PillKind.SECONDARY, contentDescription = "$title, ${Fmt.time(value)}. Change")
    }
    if (picking) TimePickerDialog(title, value, onConfirm = { onChange(it); picking = false }, onDismiss = { picking = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChipRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    val c = RhythmTheme.colors
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(label(o)) },
                modifier = Modifier.heightIn(min = 48.dp),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accentSoft, selectedLabelColor = c.onAccentSoft),
            )
        }
    }
}

@Composable
private fun LegacyRow(title: String, detail: String, onClick: () -> Unit) {
    val c = RhythmTheme.colors
    Column {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = "$title. $detail" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = c.muted)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = c.muted)
        }
        HorizontalDivider(color = c.hairline)
    }
}
