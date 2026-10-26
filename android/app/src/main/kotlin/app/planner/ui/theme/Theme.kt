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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// All colours, fonts and shapes live in this file so the look changes in one place.
// Black/white neutrals; blue is the single accent; green = done / on track; amber is for warnings only.

private val Blue = Color(0xFF2563EB)
private val BlueDark = Color(0xFF91B8FF)
private val Green = Color(0xFF1E9E5A)
private val GreenDark = Color(0xFF4CC585)
private val Amber = Color(0xFFD98A00)
private val AmberDark = Color(0xFFFFB84D)

// Amber is too faint to read as text on white (about 2.9:1), so words use a darker shade of it.
private val AmberText = Color(0xFF8A5A00)

@Immutable
data class StatusColors(val good: Color, val warning: Color, val warningText: Color)

val LocalStatusColors = staticCompositionLocalOf { StatusColors(Green, Amber, AmberText) }

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDF3FF),
    onPrimaryContainer = Color(0xFF1D4ED8),
    secondaryContainer = Color(0xFFEEEEEE),
    onSecondaryContainer = Color(0xFF252525),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF252525),
    surface = Color.White,
    onSurface = Color(0xFF252525),
    surfaceVariant = Color(0xFFEEEEEE),
    onSurfaceVariant = Color(0xFF707070),
    outlineVariant = Color(0xFFE3E3E3),
    surfaceContainer = Color(0xFFFAFAFA),
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainerHigh = Color.White,
    surfaceTint = Color.Transparent,
)

private val DarkColors = darkColorScheme(
    primary = BlueDark,
    onPrimary = Color(0xFF102653),
    primaryContainer = Color(0xFF22314A),
    onPrimaryContainer = Color(0xFFB7CFFF),
    secondaryContainer = Color(0xFF303030),
    onSecondaryContainer = Color(0xFFEDEDED),
    background = Color(0xFF191919),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF242424),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF303030),
    onSurfaceVariant = Color(0xFFADADAD),
    outlineVariant = Color(0xFF3D3D3D),
    surfaceContainer = Color(0xFF191919),
    surfaceContainerLow = Color(0xFF191919),
    surfaceContainerHigh = Color(0xFF242424),
    surfaceTint = Color.Transparent,
)

// Roboto (system default) until Inter is bundled; swap FontFamily here.
private val Sans = FontFamily.Default

private val base = TextStyle(fontFamily = Sans, fontFeatureSettings = "tnum") // tabular figures for times
val PlannerTypography = Typography().run {
    copy(
        headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal),
        headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal),
        headlineSmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Normal),
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
    medium = RoundedCornerShape(17.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PlannerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val status = if (darkTheme) StatusColors(GreenDark, AmberDark, AmberDark) else StatusColors(Green, Amber, AmberText)
    CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = PlannerTypography,
            shapes = PlannerShapes,
            content = content,
        )
    }
}
