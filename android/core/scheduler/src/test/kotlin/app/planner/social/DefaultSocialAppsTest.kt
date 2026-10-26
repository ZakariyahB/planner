package app.planner.social

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultSocialAppsTest {
    @Test
    fun `the default list is the seven apps from the plan`() {
        assertEquals(
            listOf("Instagram", "TikTok", "Snapchat", "X", "Reddit", "Facebook", "Threads"),
            DefaultSocialApps.all.map { it.name },
        )
    }

    @Test
    fun `package names are the ones Android reports`() {
        assertEquals(setOf("com.instagram.android"), DefaultSocialApps.byId("instagram")?.packages)
        assertEquals(setOf("com.snapchat.android"), DefaultSocialApps.byId("snapchat")?.packages)
        assertEquals(setOf("com.twitter.android"), DefaultSocialApps.byId("x")?.packages)
        assertEquals(setOf("com.reddit.frontpage"), DefaultSocialApps.byId("reddit")?.packages)
        assertEquals(setOf("com.facebook.katana"), DefaultSocialApps.byId("facebook")?.packages)
        assertEquals(setOf("com.instagram.barcelona"), DefaultSocialApps.byId("threads")?.packages)
    }

    @Test
    fun `tiktok has two packages because of regional builds`() {
        assertEquals(setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), DefaultSocialApps.byId("tiktok")?.packages)
    }

    @Test
    fun `ids are unique and no package belongs to two apps`() {
        assertEquals(DefaultSocialApps.all.size, DefaultSocialApps.all.map { it.id }.toSet().size)
        val packages = DefaultSocialApps.all.flatMap { it.packages }
        assertEquals(packages.size, packages.toSet().size)
    }

    @Test
    fun `an app can be found by one of its packages`() {
        assertEquals("TikTok", DefaultSocialApps.byPackage("com.ss.android.ugc.trill")?.name)
        assertNull(DefaultSocialApps.byPackage("com.android.chrome"))
        assertNotNull(DefaultSocialApps.byId("reddit"))
        assertNull(DefaultSocialApps.byId("nope"))
    }

    @Test
    fun `packages for a set of ids joins their packages and skips unknown ids`() {
        val packages = DefaultSocialApps.packagesFor(setOf("instagram", "tiktok", "nope"))
        assertEquals(setOf("com.instagram.android", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill"), packages)
        assertTrue(DefaultSocialApps.packagesFor(emptySet()).isEmpty())
    }
}
