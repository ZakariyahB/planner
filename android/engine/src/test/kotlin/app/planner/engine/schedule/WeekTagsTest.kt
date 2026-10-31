package app.planner.engine.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeekTagsTest {
    @Test fun `strips short week tags and the separators they leave`() {
        assertEquals("PintOS", WeekTags.strip("PintOS w-1"))
        assertEquals("PintOS", WeekTags.strip("PintOS W3"))
        assertEquals("Lecture - OS", WeekTags.strip("Lecture - OS (w-3)"))
        assertEquals("Lecture - OS", WeekTags.strip("Lecture - OS [w12]"))
        assertEquals("PintOS", WeekTags.strip("PintOS - w-1"))
        assertEquals("P&S problem sheet", WeekTags.strip("w-2 P&S problem sheet"))
        assertEquals("ADA worksheet", WeekTags.strip("ADA w-2 worksheet"))
    }

    @Test fun `leaves content alone`() {
        listOf("Week 3 problem sheet", "Arabic hw1", "Draw-1 diagrams", "OS worksheet", "w-").forEach {
            assertFalse(WeekTags.has(it), it)
            assertEquals(it, WeekTags.strip(it))
        }
        assertTrue(WeekTags.has("CW w-4"))
        // A title that is only a tag is kept rather than emptied.
        assertEquals("w-1", WeekTags.strip("w-1"))
    }
}
