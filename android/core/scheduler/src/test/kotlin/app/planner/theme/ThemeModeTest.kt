package app.planner.theme

import kotlin.test.Test
import kotlin.test.assertEquals

class ThemeModeTest {
    @Test
    fun `following the phone uses whatever the phone is set to`() {
        assertEquals(true, ThemeMode.SYSTEM.isDark(systemDark = true))
        assertEquals(false, ThemeMode.SYSTEM.isDark(systemDark = false))
    }

    @Test
    fun `a chosen theme ignores the phone`() {
        assertEquals(false, ThemeMode.LIGHT.isDark(systemDark = true))
        assertEquals(true, ThemeMode.DARK.isDark(systemDark = false))
    }

    @Test
    fun `a stored choice is read back, and anything else follows the phone`() {
        assertEquals(ThemeMode.DARK, ThemeMode.decode("DARK"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.decode("LIGHT"))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.decode(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.decode(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.decode("PURPLE"))
    }

    @Test
    fun `each choice has words for the settings page`() {
        assertEquals(listOf("Match my phone", "Light", "Dark"), ThemeMode.entries.map { it.label })
    }
}
