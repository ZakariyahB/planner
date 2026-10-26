package app.planner.social

/**
 * Which apps count as social media: the defaults minus any switched off, plus apps the user added.
 * The two lists are what is saved, so adding a default app to [DefaultSocialApps] later turns it on by itself.
 */
data class SocialAppSelection(
    val disabledDefaults: Set<String> = emptySet(),
    val extraPackages: Set<String> = emptySet(),
) {
    fun packages(): Set<String> = DefaultSocialApps.packagesFor(DefaultSocialApps.all.map { it.id }.toSet() - disabledDefaults) + extraPackages

    fun isDefaultEnabled(id: String): Boolean = id !in disabledDefaults

    fun withDefault(id: String, enabled: Boolean): SocialAppSelection =
        copy(disabledDefaults = if (enabled) disabledDefaults - id else disabledDefaults + id)

    /** Blank names and packages a default app already covers are ignored; those are turned on with their switch. */
    fun withExtra(pkg: String): SocialAppSelection {
        val name = pkg.trim()
        return if (name.isEmpty() || DefaultSocialApps.byPackage(name) != null) this else copy(extraPackages = extraPackages + name)
    }

    fun withoutExtra(pkg: String): SocialAppSelection = copy(extraPackages = extraPackages - pkg)

    companion object {
        /** Package and app ids never contain commas, so a plain comma list is enough to save. */
        fun encode(items: Set<String>): String = items.sorted().joinToString(",")

        fun decode(text: String?): Set<String> = text.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        fun from(disabledText: String?, extraText: String?) = SocialAppSelection(decode(disabledText), decode(extraText))
    }
}
