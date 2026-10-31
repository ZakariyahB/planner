package app.planner.ui.theme

// The Rhythm look's colour tokens as plain ARGB numbers, so their contrast can be checked on the JVM
// (RhythmTokensTest). Compose colours are built from these in RhythmPalette.kt; nothing else hardcodes a colour.
//
// Light: sand ground, warm white cards, near-black ink, deep teal accent, amber for extended or over-limit time.
// Dark: the same roles on a warm near-black ground, with a lighter teal and a softer amber so text stays above 4.5:1.

/** One full set of colour roles. Values are 0xAARRGGBB. */
data class RhythmTokens(
    val ground: Long,
    val surface: Long,
    /** A card raised above [surface] (sheets, the selected week row in dark mode). */
    val surfaceHigh: Long,
    val ink: Long,
    val muted: Long,
    /** Separators, outlines and the plain timeline line. */
    val line: Long,
    /** The faint hairline under cards and the empty part of bars. */
    val hairline: Long,
    val accent: Long,
    val onAccent: Long,
    /** A quiet tint of the accent (chips, selected rows, Material primaryContainer). */
    val accentSoft: Long,
    val onAccentSoft: Long,
    /** The large "now" card on Today. */
    val nowCard: Long,
    val onNowCard: Long,
    val onNowCardMuted: Long,
    /** Text for extended or over-limit time. */
    val amberText: Long,
    /** Lines and bar fills for extended time. */
    val amberLine: Long,
    /** Card outlines and the badge for extended time. */
    val amberSoft: Long,
    /** Bar fill for fixed events (classes, halaqa). */
    val fixedFill: Long,
    val navBar: Long,
    val navInk: Long,
    val navSelected: Long,
    val onNavSelected: Long,
    val badge: Long,
    val onBadge: Long,
    val error: Long,
    val good: Long,
)

object Rhythm {
    val light = RhythmTokens(
        ground = 0xFFF4EFE6,
        surface = 0xFFFFFDF8,
        surfaceHigh = 0xFFFFFDF8,
        ink = 0xFF1F1B16,
        muted = 0xFF6B5F50,
        line = 0xFFC9BFAE,
        hairline = 0xFFE3DACB,
        accent = 0xFF0F4C4A,
        onAccent = 0xFFF4EFE6,
        accentSoft = 0xFFDCEAE7,
        onAccentSoft = 0xFF0F4C4A,
        nowCard = 0xFF0F4C4A,
        onNowCard = 0xFFF4EFE6,
        onNowCardMuted = 0xFFCFE3E0,
        amberText = 0xFF8A4B17,
        amberLine = 0xFFB06A2E,
        amberSoft = 0xFFE2B88F,
        fixedFill = 0xFFA79C8A,
        navBar = 0xFF1F1B16,
        navInk = 0xFFE8DFD0,
        navSelected = 0xFFF4EFE6,
        onNavSelected = 0xFF1F1B16,
        badge = 0xFFE2B88F,
        onBadge = 0xFF1F1B16,
        error = 0xFFA3261C,
        good = 0xFF0F4C4A,
    )

    val dark = RhythmTokens(
        ground = 0xFF14120F,
        surface = 0xFF1E1B17,
        surfaceHigh = 0xFF29251F,
        ink = 0xFFF4EFE6,
        muted = 0xFFB8AD9C,
        line = 0xFF4A4339,
        hairline = 0xFF332E27,
        accent = 0xFF7CC4BE,
        onAccent = 0xFF0B2B2A,
        accentSoft = 0xFF1D4F4C,
        onAccentSoft = 0xFFCFE3E0,
        nowCard = 0xFF1D4F4C,
        onNowCard = 0xFFF4EFE6,
        onNowCardMuted = 0xFFCFE3E0,
        amberText = 0xFFE2B88F,
        amberLine = 0xFFC98A55,
        amberSoft = 0xFF8A5A33,
        fixedFill = 0xFF6E6457,
        navBar = 0xFF2B2721,
        navInk = 0xFFE8DFD0,
        navSelected = 0xFF7CC4BE,
        onNavSelected = 0xFF0B2B2A,
        badge = 0xFFE2B88F,
        onBadge = 0xFF1F1B16,
        error = 0xFFF2A99E,
        good = 0xFF7CC4BE,
    )

    /** WCAG relative luminance of an opaque 0xAARRGGBB colour. */
    fun luminance(argb: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((argb shr shift) and 0xFF).toDouble() / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** WCAG contrast ratio, 1.0 to 21.0. Body text needs at least 4.5. */
    fun contrast(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Every text-on-background pair the screens use, named for the test report. */
    fun textPairs(t: RhythmTokens): Map<String, Pair<Long, Long>> = mapOf(
        "ink on ground" to (t.ink to t.ground),
        "ink on surface" to (t.ink to t.surface),
        "ink on surfaceHigh" to (t.ink to t.surfaceHigh),
        "muted on ground" to (t.muted to t.ground),
        "muted on surface" to (t.muted to t.surface),
        "muted on surfaceHigh" to (t.muted to t.surfaceHigh),
        "accent on ground" to (t.accent to t.ground),
        "accent on surface" to (t.accent to t.surface),
        "onAccent on accent" to (t.onAccent to t.accent),
        "onAccentSoft on accentSoft" to (t.onAccentSoft to t.accentSoft),
        "onNowCard on nowCard" to (t.onNowCard to t.nowCard),
        "onNowCardMuted on nowCard" to (t.onNowCardMuted to t.nowCard),
        "amberText on ground" to (t.amberText to t.ground),
        "amberText on surface" to (t.amberText to t.surface),
        "navInk on navBar" to (t.navInk to t.navBar),
        "onNavSelected on navSelected" to (t.onNavSelected to t.navSelected),
        "onBadge on badge" to (t.onBadge to t.badge),
        "error on surface" to (t.error to t.surface),
        "error on ground" to (t.error to t.ground),
    )
}
