package app.planner.social

enum class UsageEventType {
    FOREGROUND,
    BACKGROUND,

    /** The screen went off or the phone shut down: whatever was on screen is no longer in use. */
    SCREEN_OFF,
}

/**
 * An app's screen coming to the front or going to the back, as Android's usage events report it (the same
 * events its own Digital Wellbeing figures are built from). [activity] tells the screens of one app apart.
 */
data class UsageEvent(val pkg: String, val type: UsageEventType, val timeMillis: Long, val activity: String = "")

object UsageSessions {
    /**
     * Minutes each of [apps] was on screen between [fromMillis] and [toMillis], from the phone's own foreground
     * and background events. An app is in use from the moment its first screen comes to the front until its
     * last one goes away, so moving between its screens, or having two open side by side, neither splits nor
     * doubles the time. Screen-off and shutdown end every open session. A session still open counts up to
     * [nowMillis]; one that began before the window counts from the start of it. Time is added up before
     * rounding, so many short glances still count.
     */
    fun minutesPerApp(events: List<UsageEvent>, apps: Set<String>, fromMillis: Long, toMillis: Long, nowMillis: Long): Map<String, Int> {
        val end = minOf(toMillis, nowMillis)
        val millis = mutableMapOf<String, Long>()
        val screens = mutableMapOf<String, MutableSet<String>>()
        val since = mutableMapOf<String, Long>()
        val seen = mutableSetOf<String>()

        fun add(pkg: String, from: Long, to: Long) {
            val span = minOf(to, end) - maxOf(from, fromMillis)
            if (span > 0) millis[pkg] = (millis[pkg] ?: 0L) + span
        }

        for (event in events.filter { it.type == UsageEventType.SCREEN_OFF || it.pkg in apps }.sortedBy { it.timeMillis }) {
            when (event.type) {
                UsageEventType.FOREGROUND -> {
                    seen += event.pkg
                    val open = screens.getOrPut(event.pkg) { mutableSetOf() }
                    if (open.isEmpty()) since[event.pkg] = event.timeMillis
                    open += event.activity
                }
                UsageEventType.BACKGROUND -> {
                    val open = screens[event.pkg]
                    when {
                        open != null && event.activity in open -> {
                            open -= event.activity
                            if (open.isEmpty()) since.remove(event.pkg)?.let { add(event.pkg, it, event.timeMillis) }
                        }
                        event.pkg !in seen -> add(event.pkg, fromMillis, event.timeMillis) // began before the window
                    }
                    seen += event.pkg
                }
                UsageEventType.SCREEN_OFF -> {
                    for ((pkg, started) in since) add(pkg, started, event.timeMillis)
                    since.clear()
                    screens.values.forEach { it.clear() }
                }
            }
        }
        for ((pkg, started) in since) add(pkg, started, end)

        return millis.mapValues { (it.value + 30_000) / 60_000 }.filterValues { it > 0 }.mapValues { it.value.toInt() }
    }

    fun total(perApp: Map<String, Int>): Int = perApp.values.sum()
}
