package app.planner.assistant

import app.planner.BuildConfig
import app.planner.ai.AiSpendRepository
import app.planner.ai.IdTokenProvider
import app.planner.ai.TokenResult
import app.planner.ai.UsageReport
import app.planner.assistant.llm.LlmClient
import app.planner.assistant.llm.LlmException
import app.planner.assistant.llm.LlmFailure
import app.planner.assistant.llm.LlmRequest
import app.planner.assistant.llm.LlmResponse
import app.planner.assistant.llm.MessagesJson
import app.planner.assistant.llm.WorkerErrors
import app.planner.errors.PlainErrors
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The v2 assistant's [LlmClient]: posts Messages API requests to the Cloudflare Worker, which holds the API key and
 * enforces the monthly cap. Same HTTP style and Google ID token as the legacy WorkerLlmClient, but it uses the v2
 * codec (which keeps thinking blocks intact) and names the model from Settings in X-Planner-Model.
 */
@Singleton
class WorkerAssistantClient @Inject constructor(
    private val tokens: IdTokenProvider,
    private val spend: AiSpendRepository,
) : LlmClient {

    override suspend fun send(request: LlmRequest): LlmResponse {
        if (BuildConfig.WORKER_URL.isBlank()) {
            throw LlmException(LlmFailure.NOT_SET_UP, PlainErrors.aiNotSetUp("WORKER_URL"))
        }
        val token = when (val result = tokens.token()) {
            is TokenResult.Token -> result.value
            TokenResult.NeedsSignIn -> throw LlmException(LlmFailure.SIGN_IN, "Sign in to use the assistant.")
            is TokenResult.Unavailable -> throw LlmException(LlmFailure.OTHER, result.message)
        }

        val reply = try {
            withContext(Dispatchers.IO) { post(token, request) }
        } catch (e: IOException) {
            throw LlmException(LlmFailure.OFFLINE, "No connection to the assistant. Check the internet and try again.")
        }

        if (reply.status !in 200..299) {
            if (reply.status == 401) tokens.invalidate()
            throw WorkerErrors.exceptionFor(reply.status, reply.body)
        }
        val response = try {
            MessagesJson.parseResponse(reply.body)
        } catch (e: IllegalArgumentException) {
            throw LlmException(LlmFailure.OTHER, "The assistant sent a reply that couldn't be read. Try again.")
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
            // A turn with thinking and tool calls can take a while.
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("X-Planner-Purpose", request.purpose.header)
            request.model?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("X-Planner-Model", it) }
            conn.outputStream.use { it.write(MessagesJson.encodeRequest(request).toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Reply(status, body, conn.getHeaderField("X-Planner-Usage"))
        } finally {
            conn.disconnect()
        }
    }
}
