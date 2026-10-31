package app.planner.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmTokensTest {
    @Test
    fun contrastMatchesKnownValues() {
        assertEquals(21.0, Rhythm.contrast(0xFF000000, 0xFFFFFFFF), 0.01)
        assertEquals(1.0, Rhythm.contrast(0xFF777777, 0xFF777777), 0.0001)
    }

    @Test
    fun lightTextPairsMeetAA() = assertAll(Rhythm.light, "light")

    @Test
    fun darkTextPairsMeetAA() = assertAll(Rhythm.dark, "dark")

    private fun assertAll(tokens: RhythmTokens, name: String) {
        val failures = Rhythm.textPairs(tokens).mapNotNull { (pair, colours) ->
            val ratio = Rhythm.contrast(colours.first, colours.second)
            if (ratio < 4.5) "$name $pair: ${"%.2f".format(ratio)}" else null
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
