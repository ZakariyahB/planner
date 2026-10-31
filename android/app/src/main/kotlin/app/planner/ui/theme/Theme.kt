@file:OptIn(ExperimentalTextApi::class)

package app.planner.ui.theme

import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineExceptionHandler

// The Rhythm look: colours in RhythmTokens.kt / RhythmPalette.kt, fonts in RhythmType.kt, shapes here.
// The app follows the phone's light/dark setting unless Appearance says otherwise (MainActivity decides `darkTheme`).

/** Older screens read these; they map onto the Rhythm roles. */
@Immutable
data class StatusColors(val good: Color, val warning: Color, val warningText: Color)

val LocalStatusColors = staticCompositionLocalOf { StatusColors(LightRhythm.good, LightRhythm.amberLine, LightRhythm.amberText) }

val PlannerTypography: Typography = RhythmTypography

val PlannerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PlannerTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkRhythm else LightRhythm
    val context = LocalContext.current
    // A failed font download must never crash the app; the families fall back to the phone's fonts.
    val fontResolver = remember(context) {
        createFontFamilyResolver(context, CoroutineExceptionHandler { _, e -> Log.w("PlannerTheme", "Font download failed", e) })
    }
    CompositionLocalProvider(
        LocalRhythmColors provides colors,
        LocalStatusColors provides StatusColors(colors.good, colors.amberLine, colors.amberText),
        LocalFontFamilyResolver provides fontResolver,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = RhythmTypography,
            shapes = PlannerShapes,
            content = content,
        )
    }
}
