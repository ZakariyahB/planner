package app.planner.social

enum class UsageEventType { FOREGROUND, BACKGROUND }

/** An app coming to the front or going to the back, as Android's usage events report it. */
data class UsageEvent(val pkg: String, val type: UsageEventType, val timeMillis: Long)

object UsageSessions {
    /**
     * Minutes each of [apps] was on screen between [fromMillis] and [toMillis], from its foreground and
     * background events. A session still open counts up to [nowMillis]; one that began before the window
     * counts from the start of it. Time is added up before rounding, so many short glances still count.
     */
    fun minutesPerApp(events: List<UsageEvent>, apps: Set<String>, fromMillis: Long, toMillis: Long, nowMillis: Long): Map<String, Int> {
        val end = minOf(toMillis, nowMillis)
        val millis = mutableMapOf<String, Long>()
        val open = mutableMapOf<String, Long>()
        val seen = mutableSetOf<String>()

        fun add(pkg: String, from: Long, to: Long) {
            val span = minOf(to, end) - maxOf(from, fromMillis)
            if (span > 0) millis[pkg] = (millis[pkg] ?: 0L) + span
        }

        for (event in events.filter { it.pkg in apps }.sortedBy { it.timeMillis }) {
            when (event.type) {
                UsageEventType.FOREGROUND -> {
                    seen += event.pkg
                    if (event.pkg !in open) open[event.pkg] = event.timeMillis
                }
                UsageEventType.BACKGROUND -> {
                    val started = open.remove(event.pkg)
                    when {
                        started != null -> add(event.pkg, started, event.timeMillis)
                        event.pkg !in seen -> add(event.pkg, fromMillis, event.timeMillis) // began before the window
                    }
                    seen += event.pkg
                }
            }
        }
        for ((pkg, started) in open) add(pkg, started, end)

        return millis.mapValues { (it.value + 30_000) / 60_000 }.filterValues { it > 0 }.mapValues { it.value.toInt() }
    }

    fun total(perApp: Map<String, Int>): Int = perApp.values.sum()
}
