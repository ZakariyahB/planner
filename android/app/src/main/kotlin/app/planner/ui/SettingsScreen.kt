package app.planner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.ai.AiSpendRepository
import app.planner.social.SocialSettingsSection

/** Where Settings can be. HOME is the list; the rest are the pages it leads to. */
enum class SettingsPage(val title: String) {
    HOME("Settings"),
    DAY_RULES("Day rules"),
    TEMPLATE("Weekly template"),
    CALENDARS("Calendars"),
    SLEEP("Sleep"),
    SOCIAL("Social media"),
    NOTIFICATIONS("Notifications"),
    ASSISTANT("Assistant"),
    HISTORY("Change history"),
}

@Composable
fun SettingsScreen(
    page: SettingsPage,
    onNavigate: (SettingsPage) -> Unit,
    calVm: CalendarViewModel,
    readinessVm: ReadinessViewModel,
    planVm: PlanViewModel,
    chatVm: ChatViewModel,
    tasksVm: TasksViewModel,
    onConnect: () -> Unit,
    onAllowHealth: () -> Unit,
) {
    when (page) {
        SettingsPage.HOME -> SettingsHome(onNavigate)
        SettingsPage.DAY_RULES -> DayRulesScreen(hiltViewModel())
        SettingsPage.TEMPLATE -> TemplateScreen(tasksVm, onBack = { onNavigate(SettingsPage.HOME) })
        SettingsPage.CALENDARS -> CalendarsPage(calVm, planVm, onConnect)
        SettingsPage.SLEEP -> SleepPage(readinessVm, onAllowHealth)
        SettingsPage.SOCIAL -> SettingsList { item { SocialSettingsSection() } }
        SettingsPage.NOTIFICATIONS -> SettingsList { item { RoutineSettingsSection() } }
        SettingsPage.ASSISTANT -> AssistantPage(chatVm)
        SettingsPage.HISTORY -> HistoryScreen(hiltViewModel())
    }
}

@Composable
private fun SettingsList(content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

private class HomeRow(val page: SettingsPage, val title: String, val status: (SettingsHomeUi) -> String)

private class HomeGroup(val title: String, val rows: List<HomeRow>)

private val HOME_GROUPS = listOf(
    HomeGroup("Plan", listOf(
        HomeRow(SettingsPage.DAY_RULES, "Day rules") { it.dayRules },
        HomeRow(SettingsPage.TEMPLATE, "Weekly template") { it.template },
        HomeRow(SettingsPage.CALENDARS, "Calendars") { it.calendars },
    )),
    HomeGroup("Body", listOf(
        HomeRow(SettingsPage.SLEEP, "Sleep and Health Connect") { it.sleep },
    )),
    HomeGroup("Time", listOf(
        HomeRow(SettingsPage.SOCIAL, "Social media") { it.social },
        HomeRow(SettingsPage.NOTIFICATIONS, "Notifications") { "Reminders and plan prompts" },
    )),
    HomeGroup("Assistant", listOf(
        HomeRow(SettingsPage.ASSISTANT, "Sign in and spend") { it.assistant },
    )),
    HomeGroup("Your data", listOf(
        HomeRow(SettingsPage.HISTORY, "Change history") { "Every change, each can be undone" },
    )),
)

@Composable
private fun SettingsHome(onNavigate: (SettingsPage) -> Unit) {
    val vm: SettingsHomeViewModel = hiltViewModel()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshSleepStatus() }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item { Box(Modifier.height(2.dp)) }
        HOME_GROUPS.forEach { group ->
            item(key = group.title) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(group.title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))) {
                        group.rows.forEachIndexed { index, row ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                Modifier.fillMaxWidth().clickable { onNavigate(row.page) }.padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(row.title, style = MaterialTheme.typography.bodyLarge)
                                    Text(row.status(ui), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        if (ui.assistantConfigured) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("AI spend this month", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(ui.assistant.substringBefore(" this month"), style = MaterialTheme.typography.bodyMedium)
                    }
                    Box(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .fillMaxWidth((ui.spendGbp / AiSpendRepository.CAP_GBP).toFloat().coerceIn(0.02f, 1f))
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
                        )
                    }
                }
            }
        }
        item {
            Text(
                "Everything stays on this phone except what you send to the assistant, which is summaries only.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}
