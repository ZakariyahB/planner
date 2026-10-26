package app.planner.ai

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.Base64

/** What the user can do about a failed AI call. */
enum class AiFailure { SIGN_IN, CAP_REACHED, BUSY, OTHER }

object AiErrors {
    fun failureFor(status: Int): AiFailure = when (status) {
        401 -> AiFailure.SIGN_IN
        402 -> AiFailure.CAP_REACHED
        429 -> AiFailure.BUSY
        else -> AiFailure.OTHER
    }

    /** Short, plain wording for the chat. Planning, ticking and the calendar keep working without the AI. */
    fun messageFor(status: Int): String = when (status) {
        401 -> "Your sign-in has expired. Sign in again to use the assistant."
        402 -> "Monthly AI cap reached (402)"
        413 -> "That conversation is too long (413)"
        429 -> "AI is busy, try again in a minute (429)"
        in 500..599 -> "The assistant is having trouble right now. Try again in a few minutes ($status)."
        else -> "AI unavailable ($status)"
    }
}

/** What one call used and cost, from the proxy's X-Planner-Usage header. */
data class UsageReport(val model: String, val usage: TokenUsage, val costGbp: Double, val monthGbp: Double) {
    companion object {
        fun parse(header: String?): UsageReport? {
            if (header == null) return null
            val obj = try {
                Json.parseToJsonElement(header) as? JsonObject
            } catch (e: SerializationException) {
                null
            } ?: return null
            val model = obj.text("model") ?: return null
            val cost = obj.number("costGbp") ?: return null
            return UsageReport(
                model = model,
                usage = TokenUsage(obj.int("inputTokens"), obj.int("outputTokens"), obj.int("cacheWriteTokens"), obj.int("cacheReadTokens")),
                costGbp = cost,
                monthGbp = obj.number("monthGbp") ?: cost,
            )
        }

        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
        private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull ?: 0
    }
}

/** Reads the expiry of a Google ID token so it can be reused until just before it runs out. */
object Jwt {
    fun expirySeconds(token: String): Long? {
        val payload = token.split(".").getOrNull(1) ?: return null
        return try {
            val json = String(Base64.getUrlDecoder().decode(payload))
            (Json.parseToJsonElement(json) as? JsonObject)?.get("exp")?.let { (it as? JsonPrimitive)?.longOrNull }
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: SerializationException) {
            null
        }
    }

    fun stillValid(token: String, nowSeconds: Long, marginSeconds: Long): Boolean =
        expirySeconds(token)?.let { it - marginSeconds > nowSeconds } ?: false
}
