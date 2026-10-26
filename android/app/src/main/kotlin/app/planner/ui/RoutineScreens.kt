package app.planner.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.routines.LaunchTarget
import app.planner.routines.RoutineLaunch

/**
 * The screens a routine notification (or a card on Today) opens over whatever is showing.
 * [onAsk] switches to the Ask tab with a draft in the box; [onOpenTemplate] opens the weekly template.
 */
@Composable
fun RoutineScreens(onAsk: (String) -> Unit, onOpenTemplate: () -> Unit) {
    val target by RoutineLaunch.requests.collectAsStateWithLifecycle()
    when (target) {
        LaunchTarget.TOMORROW_PLAN -> TomorrowPlanSheet(
            onDismiss = RoutineLaunch::consume,
            onAsk = { draft ->
                onAsk(draft)
                RoutineLaunch.consume()
            },
        )
        LaunchTarget.SUNDAY_REVIEW -> SundayReviewScreen(
            onDismiss = RoutineLaunch::consume,
            onOpenTemplate = {
                onOpenTemplate()
                RoutineLaunch.consume()
            },
        )
        LaunchTarget.TODAY -> LaunchedEffect(Unit) { RoutineLaunch.consume() } // nothing to open: the app itself is the target
        null -> Unit
    }
}
