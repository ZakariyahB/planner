@file:OptIn(ExperimentalTextApi::class)

package app.planner.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import app.planner.R

// Fraunces (display) and DM Sans (body) come from the Google Play services font provider, so no font files ship in
// the APK. Each family lists the phone's own serif / sans-serif after the downloadable fonts: Compose draws with the
// fallback until the download arrives, and keeps it if the provider is missing or the download fails.

private val provider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private fun family(name: String, fallback: String, weights: List<FontWeight>): FontFamily {
    val google = GoogleFont(name)
    val downloaded = weights.map { androidx.compose.ui.text.googlefonts.Font(googleFont = google, fontProvider = provider, weight = it) }
    val device = weights.map { Font(familyName = DeviceFontFamilyName(fallback), weight = it) }
    return FontFamily(downloaded + device)
}

val Fraunces: FontFamily = family("Fraunces", "serif", listOf(FontWeight.Normal, FontWeight.SemiBold))
val DmSans: FontFamily = family("DM Sans", "sans-serif", listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.Bold))

private fun display(size: Int, line: Int) =
    TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.Normal, fontSize = size.sp, lineHeight = line.sp)

// Tabular figures keep times lined up in lists.
private fun body(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = DmSans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, fontFeatureSettings = "tnum")

val RhythmTypography = Typography(
    displayLarge = display(56, 60),
    displayMedium = display(44, 50),
    displaySmall = display(36, 40),
    headlineLarge = display(34, 38),
    headlineMedium = display(30, 34),
    headlineSmall = display(26, 30),
    titleLarge = display(22, 28),
    titleMedium = body(15, 21, FontWeight.Medium),
    titleSmall = body(14, 20, FontWeight.Medium),
    bodyLarge = body(15, 22),
    bodyMedium = body(14, 20),
    bodySmall = body(13, 18),
    labelLarge = body(14, 20, FontWeight.Bold),
    labelMedium = body(12, 16, FontWeight.Medium),
    labelSmall = body(11, 14, FontWeight.Medium),
)

/** The small Fraunces used for prayer names on the Today timeline. */
val MarkerStyle: TextStyle = display(18, 24)
