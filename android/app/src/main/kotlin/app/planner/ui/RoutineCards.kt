package app.planner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.planner.routines.LaunchTarget
import app.planner.routines.RoutineLaunch

/** Cards on Today that only exist while a routine is waiting for him. */
@Composable
fun RoutineCards(vm: RoutineCardsViewModel = hiltViewModel()) {
    val plan by vm.planPending.collectAsStateWithLifecycle()
    val review by vm.reviewPending.collectAsStateWithLifecycle()
    if (plan == null && review == null) return

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        plan?.let {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Tomorrow's plan is ready", style = MaterialTheme.typography.titleMedium)
                Text("Look it over, switch off anything you don't want, and approve.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { RoutineLaunch.open(LaunchTarget.TOMORROW_PLAN) }) { Text("Review the plan") }
            }
        }
        review?.let { week ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sunday review", style = MaterialTheme.typography.titleMedium)
                Text(
                    "How the week went, deadlines and Fajr for the week of ${dateFormat.format(week)}, then next week's tasks.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { RoutineLaunch.open(LaunchTarget.SUNDAY_REVIEW) }) { Text("Start the review") }
            }
        }
    }
}
