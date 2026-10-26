package app.planner.ai

import app.planner.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiException(val failure: AiFailure, message: String) : Exception(message)

/** Sends model requests through the Cloudflare Worker, which holds the API key and enforces the spend cap. */
@Singleton
class WorkerLlmClient @Inject constructor(
    private val tokens: IdTokenProvider,
    private val spend: AiSpendRepository,
) : LlmClient {

    override suspend fun send(request: LlmRequest): LlmResponse {
        if (BuildConfig.WORKER_URL.isBlank()) {
            throw AiException(AiFailure.OTHER, "AI isn't set up: add WORKER_URL to android/local.properties")
        }
        val token = when (val result = tokens.token()) {
            is TokenResult.Token -> result.value
            TokenResult.NeedsSignIn -> throw AiException(AiFailure.SIGN_IN, "Sign in to use the assistant")
            is TokenResult.Unavailable -> throw AiException(AiFailure.OTHER, result.message)
        }

        val reply = try {
            withContext(Dispatchers.IO) { post(token, request) }
        } catch (e: IOException) {
            throw AiException(AiFailure.OTHER, "No connection to the assistant")
        }

        if (reply.status !in 200..299) {
            if (reply.status == 401) tokens.invalidate()
            throw AiException(AiErrors.failureFor(reply.status), AiErrors.messageFor(reply.status))
        }
        val response = try {
            AnthropicJson.parseResponse(reply.body)
        } catch (e: IllegalArgumentException) {
            throw AiException(AiFailure.OTHER, "The assistant sent a reply I couldn't read")
        }
        val report = UsageReport.parse(reply.usageHeader)
        report?.let { spend.record(it) }
        return response.copy(costGbp = report?.costGbp ?: 0.0)
    }

    private class Reply(val status: Int, val body: String, val usageHeader: String?)

    private fun post(token: String, request: LlmRequest): Reply {
        val conn = URL("${BuildConfig.WORKER_URL.trimEnd('/')}/v1/messages").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Planner-Purpose", request.purpose.header)
            conn.outputStream.use { it.write(AnthropicJson.encodeRequest(request).toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Reply(status, body, conn.getHeaderField("X-Planner-Usage"))
        } finally {
            conn.disconnect()
        }
    }
}
