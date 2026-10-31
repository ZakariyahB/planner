package app.planner.gcal.rest

data class HttpRequest(
    val method: String,
    val url: String,
    val body: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

data class HttpResponse(val code: Int, val body: String)

/** One HTTP exchange. Throws java.io.IOException when the network fails; any status code is a normal response. */
fun interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}

/**
 * OAuth access tokens for the Calendar scopes. [token] throws [app.planner.gcal.CalendarException.AuthExpired] when
 * the user must sign in again (no grant, revoked, or the 7-day Testing-mode refresh token has expired).
 * [forceRefresh] is set after Google answered 401 with the previous token.
 */
fun interface AccessTokens {
    suspend fun token(forceRefresh: Boolean): String
}
