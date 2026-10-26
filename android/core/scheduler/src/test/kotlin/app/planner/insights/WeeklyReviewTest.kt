package app.planner.insights

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeeklyReviewTest {
    private val mon = LocalDate.of(2026, 10, 26)

    private fun sleep(offset: Int, asleep: Int) = SleepRow(mon.plusDays(offset.toLong()), SleepInsights.weekday(mon.plusDays(offset.toLong()).dayOfWeek), emptyList(), asleep)

    private fun late(offset: Int, minutes: Int) = LightsOutRow(mon.plusDays(offset.toLong()), SleepInsights.weekday(mon.plusDays(offset.toLong()).dayOfWeek), minutes)

    private fun done(offset: Int, planned: Int, did: Int) = DoneRow(mon.plusDays(offset.toLong()), SleepInsights.weekday(mon.plusDays(offset.toLong()).dayOfWeek), planned, did)

    private fun social(offset: Int, minutes: Int, budget: Int) =
        SocialInsights.rows(listOf(SocialPoint(mon.plusDays(offset.toLong()), minutes, budget))).single()

    private fun review(
        sleep: List<SleepRow> = emptyList(),
        lights: List<LightsOutRow> = emptyList(),
        done: List<DoneRow> = emptyList(),
        social: List<SocialRow> = emptyList(),
    ) = WeeklyReviews.build(sleep, lights, done, social)

    @Test
    fun `the headline figures are the averages and the share done`() {
        val r = review(
            sleep = listOf(sleep(0, 360), sleep(1, 480)),
            lights = listOf(late(0, 60), late(1, -20)),
            done = listOf(done(0, 100, 100), done(1, 300, 0)),
        )
        assertEquals(420, r.avgAsleepMin)
        assertEquals(20, r.avgLightsOutMin)
        assertEquals(25, r.donePercent)
        assertEquals(400, r.plannedMin)
    }

    @Test
    fun `with no data there are no figures and no sentences`() {
        val r = review()
        assertNull(r.avgAsleepMin)
        assertNull(r.avgLightsOutMin)
        assertNull(r.donePercent)
        assertTrue(r.sentences.isEmpty())
    }

    @Test
    fun `short nights are counted against the seven hours needed`() {
        val r = review(sleep = listOf(sleep(0, 359), sleep(1, 420), sleep(2, 300), sleep(3, 450)))
        assertTrue("2 of 4 nights" in r.sentences.first(), r.sentences.first())
    }

    @Test
    fun `enough sleep every night is said so`() {
        val r = review(sleep = listOf(sleep(0, 430), sleep(1, 450)))
        assertTrue(r.sentences.any { "every night" in it })
    }

    @Test
    fun `days after a short night are compared with days after a full one`() {
        val r = review(
            sleep = listOf(sleep(0, 300), sleep(1, 480), sleep(2, 310), sleep(3, 470)),
            done = listOf(done(0, 100, 40), done(1, 100, 90), done(2, 100, 50), done(3, 100, 90)),
        )
        val line = r.sentences.first { "short night" in it }
        assertTrue("45%" in line && "90%" in line, line)
    }

    @Test
    fun `no comparison is made when sleep did not matter or there is nothing to compare`() {
        val same = review(
            sleep = listOf(sleep(0, 300), sleep(1, 480)),
            done = listOf(done(0, 100, 90), done(1, 100, 90)),
        )
        assertFalse(same.sentences.any { "short night" in it })
        val onlyShort = review(sleep = listOf(sleep(0, 300)), done = listOf(done(0, 100, 50)))
        assertFalse(onlyShort.sentences.any { "short night" in it })
    }

    @Test
    fun `the latest lights-out nights are named`() {
        val r = review(lights = listOf(late(0, 10), late(2, 72), late(5, 110), late(6, 40)))
        val line = r.sentences.first { "Lights-out" in it }
        assertTrue("Sat and Wed" in line, line)
    }

    @Test
    fun `a week of on-time nights has no lights-out advice`() {
        assertFalse(review(lights = listOf(late(0, 10), late(1, -30), late(2, 30))).sentences.any { "Lights-out" in it })
    }

    @Test
    fun `social time over budget is counted, and a clean week is noted`() {
        val over = review(social = listOf(social(0, 95, 120), social(1, 140, 120), social(5, 210, 180)))
        assertTrue(over.sentences.any { "2 days" in it })
        val clean = review(social = listOf(social(0, 95, 120), social(1, 60, 120)))
        assertTrue(clean.sentences.any { "within your social-media budget" in it })
        assertEquals(1, review(social = listOf(social(0, 140, 120))).sentences.count { "1 day" in it })
    }
}
