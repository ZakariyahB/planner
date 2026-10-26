package app.planner.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

// All colours, fonts and shapes live in this file so the look changes in one place.
// Black/white neutrals; blue is the single accent; green = done / on track; amber is for warnings only.

private val Blue = Color(0xFF1A6BFF)
private val BlueDark = Color(0xFF6B9BFF)
private val Green = Color(0xFF1E9E5A)
private val GreenDark = Color(0xFF4CC585)
private val Amber = Color(0xFFD98A00)
private val AmberDark = Color(0xFFFFB84D)

@Immutable
data class StatusColors(val good: Color, val warning: Color)

val LocalStatusColors = staticCompositionLocalOf { StatusColors(Green, Amber) }

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF0B0B0C),
    surface = Color.White,
    onSurface = Color(0xFF0B0B0C),
    surfaceVariant = Color(0xFFF4F4F5),
    onSurfaceVariant = Color(0xFF5C5C61),
    outlineVariant = Color(0xFFE4E4E7),
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color(0xFF00133A),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F3),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFF2F2F3),
    surfaceVariant = Color(0xFF151517),
    onSurfaceVariant = Color(0xFFA1A1A8),
    outlineVariant = Color(0xFF2A2A2E),
)

// Roboto (system default) until Inter is bundled; swap FontFamily here.
private val Sans = FontFamily.Default

private val base = TextStyle(fontFamily = Sans, fontFeatureSettings = "tnum") // tabular figures for times
val PlannerTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.merge(base),
        titleMedium = titleMedium.merge(base),
        bodyLarge = bodyLarge.merge(base),
        bodyMedium = bodyMedium.merge(base),
        labelLarge = labelLarge.merge(base),
        labelMedium = labelMedium.merge(base),
    )
}

val PlannerShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

@Composable
fun PlannerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val status = if (darkTheme) StatusColors(GreenDark, AmberDark) else StatusColors(Green, Amber)
    CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = PlannerTypography,
            shapes = PlannerShapes,
            content = content,
        )
    }
}
