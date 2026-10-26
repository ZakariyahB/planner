package app.planner.theme

/** Which colours the app uses: always light, always dark, or whichever the phone is set to. */
enum class ThemeMode(val label: String) {
    SYSTEM("Match my phone"),
    LIGHT("Light"),
    DARK("Dark"),
    ;

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun decode(stored: String?): ThemeMode = entries.firstOrNull { it.name == stored } ?: SYSTEM
    }
}
