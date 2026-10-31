package app.planner.engine.schedule

/**
 * Week tags in template titles, such as `w-1`, `W3`, `(w-2)` or `[w4]` (newplan 5.7, decision 6). Only that short
 * form counts: "Week 3 problem sheet" is content, not a tag, and is never touched.
 */
object WeekTags {
    private val TAG = Regex("""[(\[]?(?<![A-Za-z0-9])[wW]-?\d{1,2}(?![A-Za-z0-9])[)\]]?""")
    private val EDGE = Regex("""^[\s\-–—:,|]+|[\s\-–—:,|]+$""")
    private val SPACES = Regex("""\s{2,}""")

    fun has(title: String): Boolean = TAG.containsMatchIn(title)

    /** [title] without its week tags and any separator they leave dangling; unchanged if stripping leaves nothing. */
    fun strip(title: String): String {
        if (!has(title)) return title
        val out = TAG.replace(title, " ").replace(SPACES, " ").replace(EDGE, "")
        return out.ifBlank { title }
    }
}
