package app.planner.ui.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.planner.engine.changeset.ChangeSource
import app.planner.engine.fakes.FakePlannerQueries
import app.planner.engine.fakes.FakeProposalPort
import app.planner.engine.fakes.InMemoryChangeSetStore
import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.Settings
import app.planner.engine.model.UnparsedTemplate
import app.planner.engine.ports.AuthState
import app.planner.ui.approval.ApprovalActions
import app.planner.ui.approval.ChangeCard
import app.planner.ui.backlog.BacklogContent
import app.planner.ui.backlog.BacklogScreenUi
import app.planner.ui.model.ApprovalCards
import app.planner.ui.model.BacklogItems
import app.planner.ui.model.DayTimelines
import app.planner.ui.model.WeekRhythm
import app.planner.ui.theme.PlannerTheme
import app.planner.ui.theme.RhythmTheme
import app.planner.ui.today.TodayActions
import app.planner.ui.today.TodayContent
import app.planner.ui.today.TodayUi
import app.planner.ui.week.WeekActions
import app.planner.ui.week.WeekContent
import app.planner.ui.week.WeekScreenUi
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

// Previews built from the shared fakes (the reference week of 2 to 8 November 2026), in light and dark.

private val previewNow = ReferenceWeek.days[1].atTime(10, 40)
private val previewQueries = FakePlannerQueries(now = { previewNow })

private fun todayUi(auth: AuthState = AuthState.SIGNED_IN): TodayUi = runBlocking {
    val t = previewQueries.observeToday().first()
    TodayUi(
        timeline = DayTimelines.build(t.date, t.blocks, t.anchors, previewNow, Settings().normalEnd),
        auth = auth, syncLine = "Synced with Google 10:31", syncFailed = false, unscheduled = emptyList(),
        pendingCount = 2, dayFlags = listOf("Extended day"), settings = Settings(), focusFor = null, busy = false,
    )
}

private fun weekUi(): WeekScreenUi = runBlocking {
    val w = previewQueries.week(ReferenceWeek.monday)
    WeekScreenUi(WeekRhythm.build(w, ReferenceWeek.anchors, Settings(), ReferenceWeek.days[1]), isThisWeek = true, busy = false)
}

@Composable
private fun Frame(dark: Boolean, content: @Composable () -> Unit) {
    PlannerTheme(darkTheme = dark) { Surface(color = RhythmTheme.colors.ground) { content() } }
}

@Preview(name = "Today, light", widthDp = 390, heightDp = 844)
@Composable
private fun TodayLight() = Frame(false) { TodayContent(todayUi(), TodayActions()) }

@Preview(name = "Today, dark, sign-in expired", widthDp = 390, heightDp = 844)
@Composable
private fun TodayDark() = Frame(true) { TodayContent(todayUi(AuthState.EXPIRED), TodayActions()) }

@Preview(name = "Week, light", widthDp = 390, heightDp = 844)
@Composable
private fun WeekLight() = Frame(false) { WeekContent(weekUi(), WeekActions()) }

@Preview(name = "Week, dark", widthDp = 390, heightDp = 844)
@Composable
private fun WeekDark() = Frame(true) { WeekContent(weekUi(), WeekActions()) }

@Preview(name = "Approval card", widthDp = 390)
@Composable
private fun ApprovalCardPreview() = Frame(false) {
    val cs = FakeProposalPort(InMemoryChangeSetStore(), clock = { Instant.parse("2026-11-04T12:45:00Z") }).sample(ChangeSource.NOTIFICATION, "Halaqa ran over: move 2 blocks")
    val card = ApprovalCards.changeSet(cs, Settings()).cards.first()
    Box(Modifier.padding(16.dp)) { ChangeCard(card, approved = true, scope = card.scope, showToggle = true, actions = ApprovalActions()) }
}

@Preview(name = "Backlog, dark", widthDp = 390, heightDp = 844)
@Composable
private fun BacklogDark() = Frame(true) {
    val needs = listOf(UnparsedTemplate(app.planner.engine.model.EventRef("cal-academic", "x"), "5. ADA revision sometime", "Academic", ReferenceWeek.days[3]))
    BacklogContent(BacklogScreenUi(BacklogItems.build(ReferenceWeek.tasks.take(6), needs, ReferenceWeek.days[1]), editor = null, busy = false), app.planner.ui.backlog.BacklogActions())
}
