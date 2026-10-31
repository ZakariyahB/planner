package app.planner.ui.rhythm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.planner.ui.theme.RhythmTheme

// Building blocks of the Rhythm look, shared by every new screen. Touch targets are at least 48dp.

enum class PillKind { PRIMARY, SECONDARY, ON_ACCENT, ON_ACCENT_OUTLINE }

@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: PillKind = PillKind.PRIMARY,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val c = RhythmTheme.colors
    val shape = RoundedCornerShape(50)
    val padding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
    val size = modifier.defaultMinSize(minHeight = 48.dp)
    val label: @Composable RowScope.() -> Unit = {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (kind == PillKind.PRIMARY || kind == PillKind.ON_ACCENT) FontWeight.Bold else FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val desc = contentDescription?.let { d -> Modifier.semantics { this.contentDescription = d } } ?: Modifier
    when (kind) {
        PillKind.PRIMARY -> Button(
            onClick, size.then(desc), enabled = enabled, shape = shape, contentPadding = padding,
            colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent, disabledContainerColor = c.hairline, disabledContentColor = c.muted),
            content = label,
        )
        PillKind.ON_ACCENT -> Button(
            onClick, size.then(desc), enabled = enabled, shape = shape, contentPadding = padding,
            colors = ButtonDefaults.buttonColors(containerColor = c.onNowCard, contentColor = c.nowCard),
            content = label,
        )
        PillKind.SECONDARY -> OutlinedButton(
            onClick, size.then(desc), enabled = enabled, shape = shape, contentPadding = padding,
            border = BorderStroke(1.dp, c.line),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.ink),
            content = label,
        )
        PillKind.ON_ACCENT_OUTLINE -> OutlinedButton(
            onClick, size.then(desc), enabled = enabled, shape = shape, contentPadding = padding,
            border = BorderStroke(1.dp, c.onNowCard.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.onNowCard),
            content = label,
        )
    }
}

/** A quiet text action, still 48dp tall. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = RhythmTheme.colors.accent) {
    TextButton(onClick = onClick, modifier = modifier.defaultMinSize(minHeight = 48.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

/** Warm white card with the hairline underneath (or an amber outline for extended time). */
@Composable
fun RhythmCard(
    modifier: Modifier = Modifier,
    amber: Boolean = false,
    onClick: (() -> Unit)? = null,
    color: Color = RhythmTheme.colors.surface,
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = RhythmTheme.colors
    val border = BorderStroke(1.dp, if (amber) c.amberSoft else c.hairline)
    val shape = RoundedCornerShape(16.dp)
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = color, contentColor = c.ink, border = border) {
            Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
    } else {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, contentColor = c.ink, border = border) {
            Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
    }
}

/** Screen title block: a small date line, a Fraunces title, a muted line under it. */
@Composable
fun ScreenHeader(
    eyebrow: String?,
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = RhythmTheme.colors
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            eyebrow?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = c.muted) }
            Text(title, style = MaterialTheme.typography.displaySmall, color = c.ink, modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
        }
        if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** Small uppercase label above a group. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier, color: Color = RhythmTheme.colors.muted) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        letterSpacing = 0.6.sp,
        color = color,
        modifier = modifier.padding(top = 8.dp, bottom = 2.dp).semantics { heading() },
    )
}

@Composable
fun FlagChip(label: String, warning: Boolean, modifier: Modifier = Modifier) {
    val c = RhythmTheme.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (warning) Color.Transparent else c.hairline,
        border = if (warning) BorderStroke(1.dp, c.amberSoft) else null,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (warning) c.amberText else c.ink,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

enum class BannerTone { INFO, WARNING }

/** A full-width notice with one action, e.g. "Sign in again" or "2 changes waiting". */
@Composable
fun Banner(
    text: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    tone: BannerTone = BannerTone.INFO,
    detail: String? = null,
) {
    val c = RhythmTheme.colors
    RhythmCard(modifier = modifier, amber = tone == BannerTone.WARNING, padding = PaddingValues(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text, style = MaterialTheme.typography.titleMedium, color = if (tone == BannerTone.WARNING) c.amberText else c.ink)
                detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
            }
            if (actionLabel != null) PillButton(actionLabel, onAction, kind = PillKind.PRIMARY)
        }
    }
}

/** A round count badge (pending approvals on the Ask tab). */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val c = RhythmTheme.colors
    Box(
        modifier.widthIn(min = 18.dp).heightIn(min = 18.dp).background(c.badge, CircleShape).padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(count.toString(), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = c.onBadge)
    }
}

/** A coloured square used in legends. */
@Composable
fun Swatch(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
}

/** Muted one-line empty state inside a card. */
@Composable
fun EmptyNote(title: String, body: String?, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val c = RhythmTheme.colors
    RhythmCard(modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        body?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.muted) }
        action?.invoke()
    }
}
