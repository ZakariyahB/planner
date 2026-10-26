package app.planner.ai

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiServiceTest {
    @Test
    fun `errors are put in plain words with the status`() {
        assertEquals("Your sign-in has expired. Sign in again to use the assistant.", AiErrors.messageFor(401))
        assertEquals("Monthly AI cap reached (402)", AiErrors.messageFor(402))
        assertEquals("AI is busy, try again in a minute (429)", AiErrors.messageFor(429))
        assertEquals("That conversation is too long (413)", AiErrors.messageFor(413))
        assertEquals("The assistant is having trouble right now. Try again in a few minutes (502).", AiErrors.messageFor(502))
        assertEquals("AI unavailable (418)", AiErrors.messageFor(418))
    }

    @Test
    fun `failures are grouped by what the user can do about them`() {
        assertEquals(AiFailure.SIGN_IN, AiErrors.failureFor(401))
        assertEquals(AiFailure.CAP_REACHED, AiErrors.failureFor(402))
        assertEquals(AiFailure.BUSY, AiErrors.failureFor(429))
        assertEquals(AiFailure.OTHER, AiErrors.failureFor(502))
    }

    @Test
    fun `the usage header from the proxy is read`() {
        val report = UsageReport.parse(
            """{"model":"claude-sonnet-5-5","inputTokens":1200,"outputTokens":300,"cacheWriteTokens":500,"cacheReadTokens":4000,"costGbp":0.0123,"monthGbp":1.5}""",
        )!!
        assertEquals("claude-sonnet-5-5", report.model)
        assertEquals(TokenUsage(1200, 300, 500, 4000), report.usage)
        assertEquals(0.0123, report.costGbp, 1e-9)
        assertEquals(1.5, report.monthGbp, 1e-9)
    }

    @Test
    fun `a missing or broken usage header is ignored`() {
        assertNull(UsageReport.parse(null))
        assertNull(UsageReport.parse("not json"))
        assertNull(UsageReport.parse("""{"model":"x"}"""))
    }

    private fun token(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return listOf("""{"alg":"RS256"}""", payload, "sig").mapIndexed { i, part ->
            if (i == 2) part else enc.encodeToString(part.toByteArray())
        }.joinToString(".")
    }

    @Test
    fun `a sign-in token's expiry is read from its payload`() {
        assertEquals(1_800_003_600L, Jwt.expirySeconds(token("""{"email":"a@b.c","exp":1800003600}""")))
    }

    @Test
    fun `a token without a readable expiry has none`() {
        assertNull(Jwt.expirySeconds("garbage"))
        assertNull(Jwt.expirySeconds(token("""{"email":"a@b.c"}""")))
    }

    @Test
    fun `the signed-in address is read from the token's payload`() {
        assertEquals("me@example.com", Jwt.email(token("""{"email":"me@example.com","exp":1}""")))
        assertNull(Jwt.email(token("""{"exp":1}""")))
        assertNull(Jwt.email("garbage"))
    }

    @Test
    fun `a cached token is reused until shortly before it expires`() {
        val t = token("""{"exp":1000}""")
        assertEquals(true, Jwt.stillValid(t, nowSeconds = 800, marginSeconds = 120))
        assertEquals(false, Jwt.stillValid(t, nowSeconds = 900, marginSeconds = 120))
        assertEquals(false, Jwt.stillValid("garbage", nowSeconds = 0, marginSeconds = 120))
    }
}
