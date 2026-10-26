package app.planner.social

/** A social-media app the budget counts. [id] is stable for storage; [packages] are what Android reports it as. */
data class SocialApp(val id: String, val name: String, val packages: Set<String>)

object DefaultSocialApps {
    val all: List<SocialApp> = listOf(
        SocialApp("instagram", "Instagram", setOf("com.instagram.android")),
        // TikTok ships under two package names depending on region.
        SocialApp("tiktok", "TikTok", setOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")),
        SocialApp("snapchat", "Snapchat", setOf("com.snapchat.android")),
        SocialApp("x", "X", setOf("com.twitter.android")),
        SocialApp("reddit", "Reddit", setOf("com.reddit.frontpage")),
        SocialApp("facebook", "Facebook", setOf("com.facebook.katana")),
        SocialApp("threads", "Threads", setOf("com.instagram.barcelona")),
    )

    fun byId(id: String): SocialApp? = all.firstOrNull { it.id == id }

    fun byPackage(pkg: String): SocialApp? = all.firstOrNull { pkg in it.packages }

    /** Every package of the default apps with these ids; unknown ids are skipped. */
    fun packagesFor(ids: Set<String>): Set<String> = all.filter { it.id in ids }.flatMapTo(mutableSetOf()) { it.packages }
}
