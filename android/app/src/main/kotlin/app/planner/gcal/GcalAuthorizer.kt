package app.planner.gcal

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import app.planner.engine.ports.AuthState
import app.planner.gcal.rest.AccessTokens
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface GcalAuthOutcome {
    data object Authorized : GcalAuthOutcome

    /** First run, revoked access or an expired Testing-mode grant: launch [sender] so the user can approve. */
    data class NeedsConsent(val sender: IntentSender) : GcalAuthOutcome

    data class Failed(val message: String) : GcalAuthOutcome
}

/**
 * Google Calendar authorization through Google Identity Services' AuthorizationClient (as the legacy CalendarAuth),
 * with the v2 scopes: calendar.events (read and write events) and calendar.calendarlist.readonly (list calendars).
 *
 * Background callers (sync, the writer) get tokens through [AccessTokens.token]; when Google needs the user on
 * screen it throws [CalendarException.AuthExpired] and [AuthTracker] shows "Sign in again". Nothing is queued.
 * The UI signs in with [authorize] and, after the consent screen, [completeConsent].
 *
 * While the OAuth consent screen is in Testing, Google expires the grant after 7 days; that is the EXPIRED state.
 */
class GcalAuthorizer(
    private val context: Context,
    private val auth: AuthTracker,
) : AccessTokens {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val lock = Mutex()
    @Volatile private var cached: String? = null

    init {
        if (prefs.getString(KEY_ACCOUNT, null) != null) auth.signedIn() else auth.signedOut()
    }

    val account: String? get() = prefs.getString(KEY_ACCOUNT, null)

    override suspend fun token(forceRefresh: Boolean): String = lock.withLock {
        val previous = cached
        if (!forceRefresh && previous != null) return@withLock previous
        if (forceRefresh && previous != null) clear(previous)
        cached = null
        val result = try {
            request()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Silent authorization failed: ${(e as? ApiException)?.statusCode}")
            markNeedsUser()
            throw CalendarException.AuthExpired()
        }
        val token = result.accessToken
        if (result.hasResolution() || token == null) {
            markNeedsUser()
            throw CalendarException.AuthExpired()
        }
        cached = token
        auth.signedIn()
        token
    }

    /** Signs in from the UI. On [GcalAuthOutcome.NeedsConsent], launch the sender and pass its result to [completeConsent]. */
    suspend fun authorize(): GcalAuthOutcome = try {
        val result = request()
        val pending = result.pendingIntent
        when {
            result.hasResolution() && pending != null -> GcalAuthOutcome.NeedsConsent(pending.intentSender)
            result.accessToken != null -> { cached = result.accessToken; auth.signedIn(); GcalAuthOutcome.Authorized }
            else -> GcalAuthOutcome.Failed(FAILED)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GcalAuthOutcome.Failed(FAILED)
    }

    /** Reads the consent screen's result, remembers the chosen account (so later calls skip the picker). */
    suspend fun completeConsent(data: Intent?): GcalAuthOutcome = try {
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        result.toGoogleSignInAccount()?.email?.let { prefs.edit().putString(KEY_ACCOUNT, it).apply() }
        val token = result.accessToken
        if (token != null) {
            cached = token
            auth.signedIn()
            GcalAuthOutcome.Authorized
        } else {
            GcalAuthOutcome.Failed(FAILED)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GcalAuthOutcome.Failed(FAILED)
    }

    /** Forgets the account here. Revoking access is done in the Google account settings. */
    fun signOut() {
        prefs.edit().remove(KEY_ACCOUNT).apply()
        cached = null
        auth.signedOut()
    }

    private fun markNeedsUser() {
        if (account != null) auth.expired() else auth.signedOut()
    }

    private suspend fun clear(token: String) = withContext(Dispatchers.IO) {
        try {
            GoogleAuthUtil.clearToken(context, token)
        } catch (e: Exception) {
            Log.w(TAG, "Could not clear a stale token", e)
        }
    }

    private suspend fun request(): AuthorizationResult {
        val builder = AuthorizationRequest.builder().setRequestedScopes(SCOPES.map(::Scope))
        account?.let { builder.setAccount(Account(it, GOOGLE_ACCOUNT_TYPE)) }
        val request = builder.build()
        return suspendCancellableCoroutine { cont ->
            Identity.getAuthorizationClient(context).authorize(request)
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { e -> if (cont.isActive) cont.resumeWith(Result.failure(e)) }
        }
    }

    companion object {
        private const val TAG = "GcalAuthorizer"
        private const val PREFS = "gcal_auth"
        private const val KEY_ACCOUNT = "account"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val FAILED = "Google sign-in did not finish. Try again."
        val SCOPES = listOf(
            "https://www.googleapis.com/auth/calendar.events",
            "https://www.googleapis.com/auth/calendar.calendarlist.readonly",
        )
    }
}
