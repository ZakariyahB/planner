package app.planner.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SocialAppSelectionTest {
    private val everyDefault = DefaultSocialApps.all.flatMap { it.packages }.toSet()

    @Test
    fun `with nothing chosen every default app counts`() {
        assertEquals(everyDefault, SocialAppSelection().packages())
    }

    @Test
    fun `switching a default app off drops all of its packages`() {
        val selection = SocialAppSelection().withDefault("tiktok", enabled = false)
        assertFalse("com.zhiliaoapp.musically" in selection.packages())
        assertFalse("com.ss.android.ugc.trill" in selection.packages())
        assertTrue("com.instagram.android" in selection.packages())
        assertFalse(selection.isDefaultEnabled("tiktok"))
        assertTrue(selection.isDefaultEnabled("reddit"))
    }

    @Test
    fun `switching it back on restores it`() {
        val selection = SocialAppSelection().withDefault("x", false).withDefault("x", true)
        assertEquals(everyDefault, selection.packages())
    }

    @Test
    fun `extra apps are added to the set`() {
        val selection = SocialAppSelection().withExtra("com.discord")
        assertEquals(everyDefault + "com.discord", selection.packages())
    }

    @Test
    fun `an extra app can be removed again`() {
        val selection = SocialAppSelection().withExtra("com.discord").withoutExtra("com.discord")
        assertEquals(everyDefault, selection.packages())
    }

    @Test
    fun `extra apps stay when every default is off, so nothing selected is possible`() {
        val allOff = DefaultSocialApps.all.fold(SocialAppSelection()) { s, app -> s.withDefault(app.id, false) }
        assertTrue(allOff.packages().isEmpty())
        assertEquals(setOf("com.discord"), allOff.withExtra("com.discord").packages())
    }

    @Test
    fun `blank names and packages that a default app already covers are not added as extras`() {
        val selection = SocialAppSelection().withExtra(" ").withExtra("com.instagram.android")
        assertTrue(selection.extraPackages.isEmpty())
    }

    @Test
    fun `lists are saved as text and read back`() {
        val text = SocialAppSelection.encode(setOf("b.app", "a.app"))
        assertEquals("a.app,b.app", text)
        assertEquals(setOf("a.app", "b.app"), SocialAppSelection.decode(text))
    }

    @Test
    fun `reading a missing or messy saved list gives a clean set`() {
        assertEquals(emptySet(), SocialAppSelection.decode(null))
        assertEquals(emptySet(), SocialAppSelection.decode(""))
        assertEquals(setOf("a", "b"), SocialAppSelection.decode(" a, ,b ,a,"))
        assertEquals("", SocialAppSelection.encode(emptySet()))
    }

    @Test
    fun `a selection is rebuilt from its two saved lists`() {
        val selection = SocialAppSelection.from(disabledText = "reddit,threads", extraText = "com.discord")
        assertEquals(setOf("reddit", "threads"), selection.disabledDefaults)
        assertEquals(setOf("com.discord"), selection.extraPackages)
        assertFalse("com.reddit.frontpage" in selection.packages())
        assertTrue("com.discord" in selection.packages())
    }
}
