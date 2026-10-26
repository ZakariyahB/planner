package app.planner.calendar

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import app.planner.data.SettingsRepository
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

sealed interface AuthOutcome {
    data class Token(val accessToken: String) : AuthOutcome

    /** First run, revoked access or an expired Testing-mode grant: the user must approve on screen. */
    data class NeedsConsent(val sender: IntentSender) : AuthOutcome

    data class Failed(val message: String) : AuthOutcome
}

/**
 * Google Authorization API: returns an access token silently once consent has been given.
 * Only Calendar scopes for now; openid/email for the Worker's ID token arrive with M4.
 *
 * The account chosen during consent is remembered. Without it, every call on a phone with several
 * Google accounts would ask for the account picker again and consent could never complete.
 */
@Singleton
class CalendarAuth @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    suspend fun authorize(): AuthOutcome {
        val builder = AuthorizationRequest.builder().setRequestedScopes(SCOPES.map(::Scope))
        settings.googleAccount()?.let { builder.setAccount(Account(it, GOOGLE_ACCOUNT_TYPE)) }
        val request = builder.build()

        return suspendCancellableCoroutine { cont ->
            Identity.getAuthorizationClient(context).authorize(request)
                .addOnSuccessListener { result ->
                    val pendingIntent = result.pendingIntent
                    val token = result.accessToken
                    val outcome = when {
                        result.hasResolution() && pendingIntent != null -> AuthOutcome.NeedsConsent(pendingIntent.intentSender)
                        token != null -> AuthOutcome.Token(token)
                        else -> AuthOutcome.Failed("Google returned no access token")
                    }
                    if (cont.isActive) cont.resume(outcome)
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resume(AuthOutcome.Failed(describe(e)))
                }
        }
    }

    /** Reads the result of the consent screen, remembers the chosen account and returns a token. */
    suspend fun completeConsent(data: Intent?): AuthOutcome = try {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        result.toGoogleSignInAccount()?.email?.let { settings.saveGoogleAccount(it) }
        result.accessToken?.let { AuthOutcome.Token(it) } ?: AuthOutcome.Failed("Google returned no access token")
    } catch (e: ApiException) {
        AuthOutcome.Failed(describe(e))
    }

    private fun describe(e: Exception): String {
        val code = (e as? ApiException)?.statusCode?.let { " (code $it)" }.orEmpty()
        return "Google authorization failed$code: ${e.message ?: "no details"}"
    }

    private companion object {
        const val GOOGLE_ACCOUNT_TYPE = "com.google"
        val SCOPES = listOf(
            "https://www.googleapis.com/auth/calendar.readonly",
            "https://www.googleapis.com/auth/calendar.app.created",
        )
    }
}
