package app.planner.calendar

data class CalendarSpan(val key: String, val startMin: Int, val endMin: Int)
data class CalendarLane(val lane: Int, val count: Int)

/** Partition overlapping events together; touching endpoints can use the same lane. */
object CalendarLayout {
    fun lanes(spans: List<CalendarSpan>): Map<String, CalendarLane> {
        val result = mutableMapOf<String, CalendarLane>()
        var group = mutableListOf<CalendarSpan>()
        var end = -1
        fun flush() {
            val laneEnds = mutableListOf<Int>()
            val assigned = group.map { span ->
                var lane = laneEnds.indexOfFirst { it <= span.startMin }
                if (lane < 0) { lane = laneEnds.size; laneEnds += span.endMin } else laneEnds[lane] = span.endMin
                span.key to lane
            }
            assigned.forEach { (key, lane) -> result[key] = CalendarLane(lane, laneEnds.size) }
            group = mutableListOf()
        }
        spans.filter { it.endMin > it.startMin }.sortedWith(compareBy<CalendarSpan> { it.startMin }.thenBy { it.key }).forEach { span ->
            if (group.isNotEmpty() && span.startMin >= end) { flush(); end = -1 }
            group += span
            end = maxOf(end, span.endMin)
        }
        if (group.isNotEmpty()) flush()
        return result
    }
}
