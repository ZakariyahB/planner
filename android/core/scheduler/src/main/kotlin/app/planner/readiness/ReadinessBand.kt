package app.planner.readiness

/** Sets the day's load: GREEN full plan, AMBER 20% lighter, RED only what is due within 48h. */
enum class ReadinessBand {
    GREEN, AMBER, RED;

    companion object {
        fun of(score: Int): ReadinessBand = when {
            score >= 70 -> GREEN
            score >= 40 -> AMBER
            else -> RED
        }
    }
}
