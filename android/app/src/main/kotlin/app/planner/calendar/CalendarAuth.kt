package app.planner.calendar

import android.content.Context
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
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
 */
@Singleton
class CalendarAuth @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun authorize(): AuthOutcome = suspendCancellableCoroutine { cont ->
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(SCOPES.map(::Scope))
            .build()
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
                if (cont.isActive) cont.resume(AuthOutcome.Failed(e.message ?: "Google authorization failed"))
            }
    }

    private companion object {
        val SCOPES = listOf(
            "https://www.googleapis.com/auth/calendar.readonly",
            "https://www.googleapis.com/auth/calendar.app.created",
        )
    }
}
