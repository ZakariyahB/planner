package app.planner.ui.rhythm

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.planner.ui.theme.RhythmTheme

data class NavItem(val key: String, val label: String, val badge: Int = 0)

/** The floating dark pill at the bottom: Today, Week, Backlog, Ask. The selected tab is a light pill inside it. */
@Composable
fun FloatingNav(items: List<NavItem>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = RhythmTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(c.navBar)
            .padding(6.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { item ->
            val isSelected = item.key == selected
            val spoken = if (item.badge > 0) "${item.label}, ${item.badge} waiting for approval" else item.label
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (isSelected) c.navSelected else c.navBar)
                    .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelect(item.key) })
                    .semantics { contentDescription = spoken },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) c.onNavSelected else c.navInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.badge > 0) CountBadge(item.badge)
                }
            }
        }
    }
}
