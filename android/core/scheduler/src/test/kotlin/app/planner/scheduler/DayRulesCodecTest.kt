package app.planner.scheduler

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayRulesCodecTest {
    @Test
    fun `the default rules round trip through text`() {
        assertEquals(DayRules(), DayRulesCodec.decode(DayRulesCodec.encode(DayRules())))
    }

    @Test
    fun `edited rules round trip`() {
        val rules = DayRules(
            wake = LocalTime.of(9, 30),
            workStop = LocalTime.of(20, 0),
            workStopPacked = LocalTime.of(21, 30),
            windDown = LocalTime.of(23, 0),
            lightsOut = LocalTime.of(23, 45),
            blockBufferMin = 10,
            prayerBufferMin = 20,
            packedFixedMinutes = 240,
            fridayClearFrom = LocalTime.of(16, 0),
            sundayCatchUpMin = 90,
            maxTaskMinutesPerDay = 300,
        )
        assertEquals(rules, DayRulesCodec.decode(DayRulesCodec.encode(rules)))
    }

    @Test
    fun `nothing stored means the defaults`() {
        assertEquals(DayRules(), DayRulesCodec.decode(null))
        assertEquals(DayRules(), DayRulesCodec.decode(""))
        assertEquals(DayRules(), DayRulesCodec.decode("not json"))
    }

    @Test
    fun `a field that is missing keeps its default while the others are used`() {
        val rules = DayRulesCodec.decode("""{"wake":"07:30"}""")
        assertEquals(LocalTime.of(7, 30), rules.wake)
        assertEquals(DayRules().workStop, rules.workStop)
    }

    @Test
    fun `an unreadable time falls back to that field's default`() {
        assertEquals(DayRules().wake, DayRulesCodec.decode("""{"wake":"half past eight"}""").wake)
    }

    @Test
    fun `stored rules that break the rules together are replaced by the defaults`() {
        // work stopping before waking would produce an impossible day
        assertEquals(DayRules(), DayRulesCodec.decode("""{"wake":"22:00","workStop":"08:00","workStopPacked":"09:00"}"""))
    }

    @Test
    fun `problems are listed in plain words`() {
        val bad = DayRules(wake = LocalTime.of(10, 0), workStop = LocalTime.of(11, 0), workStopPacked = LocalTime.of(11, 30), windDown = LocalTime.of(10, 30))
        val problems = DayRulesCodec.problems(bad)
        assertTrue(problems.any { "at least 4 hours" in it }, problems.toString())
        assertTrue(problems.any { "wind-down" in it }, problems.toString())
    }

    @Test
    fun `sensible rules have no problems`() {
        assertEquals(emptyList(), DayRulesCodec.problems(DayRules()))
    }

    @Test
    fun `the limits for steppers are sensible`() {
        assertTrue(DayRulesCodec.BUFFER_RANGE_MIN.contains(15))
        assertTrue(DayRulesCodec.DAILY_CAP_RANGE_MIN.contains(360))
        assertTrue(DayRulesCodec.PACKED_RANGE_MIN.contains(180))
        assertTrue(DayRulesCodec.CATCH_UP_RANGE_MIN.contains(120))
    }
}
