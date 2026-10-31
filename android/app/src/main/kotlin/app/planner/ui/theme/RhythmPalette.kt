package app.planner.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** The Rhythm colour roles as Compose colours. Read them with `RhythmTheme.colors`. */
@Immutable
data class RhythmColors(
    val isDark: Boolean,
    val ground: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val hairline: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val nowCard: Color,
    val onNowCard: Color,
    val onNowCardMuted: Color,
    val amberText: Color,
    val amberLine: Color,
    val amberSoft: Color,
    val fixedFill: Color,
    val navBar: Color,
    val navInk: Color,
    val navSelected: Color,
    val onNavSelected: Color,
    val badge: Color,
    val onBadge: Color,
    val error: Color,
    val good: Color,
)

private fun Long.c(): Color = Color(this.toInt())

fun RhythmTokens.toColors(isDark: Boolean): RhythmColors = RhythmColors(
    isDark = isDark,
    ground = ground.c(), surface = surface.c(), surfaceHigh = surfaceHigh.c(),
    ink = ink.c(), muted = muted.c(), line = line.c(), hairline = hairline.c(),
    accent = accent.c(), onAccent = onAccent.c(), accentSoft = accentSoft.c(), onAccentSoft = onAccentSoft.c(),
    nowCard = nowCard.c(), onNowCard = onNowCard.c(), onNowCardMuted = onNowCardMuted.c(),
    amberText = amberText.c(), amberLine = amberLine.c(), amberSoft = amberSoft.c(), fixedFill = fixedFill.c(),
    navBar = navBar.c(), navInk = navInk.c(), navSelected = navSelected.c(), onNavSelected = onNavSelected.c(),
    badge = badge.c(), onBadge = onBadge.c(), error = error.c(), good = good.c(),
)

val LightRhythm: RhythmColors = Rhythm.light.toColors(isDark = false)
val DarkRhythm: RhythmColors = Rhythm.dark.toColors(isDark = true)

val LocalRhythmColors = staticCompositionLocalOf { LightRhythm }

object RhythmTheme {
    val colors: RhythmColors
        @Composable @ReadOnlyComposable get() = LocalRhythmColors.current
}

/** Material's roles mapped onto Rhythm, so stock components (and the older screens) pick up the same look. */
fun RhythmColors.toMaterial(): ColorScheme {
    val scheme = if (isDark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accentSoft,
        onPrimaryContainer = onAccentSoft,
        secondary = accent,
        onSecondary = onAccent,
        secondaryContainer = hairline,
        onSecondaryContainer = ink,
        tertiary = amberLine,
        onTertiary = if (isDark) ground else surface,
        tertiaryContainer = amberSoft,
        onTertiaryContainer = if (isDark) ink else Color(0xFF1F1B16),
        background = ground,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = hairline,
        onSurfaceVariant = muted,
        surfaceTint = Color.Transparent,
        inverseSurface = navBar,
        inverseOnSurface = navInk,
        inversePrimary = navSelected,
        outline = line,
        outlineVariant = hairline,
        error = error,
        onError = if (isDark) ground else surface,
        surfaceContainerLowest = ground,
        surfaceContainerLow = ground,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceHigh,
        surfaceBright = surfaceHigh,
        surfaceDim = ground,
        scrim = Color.Black,
    )
}
