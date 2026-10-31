package app.planner.gcal

import app.planner.engine.ports.AuthState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The Google sign-in state the Today screen shows. The app's authorizer reports sign-in and sign-out; sync and the
 * writer report expiry when Google refuses the token (the 7-day refresh-token limit while the consent screen is in
 * Testing). Expiry never throws past the caller: it shows a "Sign in again" banner and nothing destructive is queued.
 */
class AuthTracker(initial: AuthState = AuthState.SIGNED_OUT) {
    private val mutable = MutableStateFlow(initial)
    val state: StateFlow<AuthState> = mutable.asStateFlow()

    fun signedIn() { mutable.value = AuthState.SIGNED_IN }
    fun signedOut() { mutable.value = AuthState.SIGNED_OUT }
    fun expired() { mutable.value = AuthState.EXPIRED }
}

/** Messages shown when Google cannot be reached. Plain English, no codes. */
object SyncMessages {
    const val SIGN_IN_AGAIN = "Google sign-in expired. Sign in again to sync and apply changes."
    const val OFFLINE = "Could not reach Google Calendar. Showing the last synced events."
    const val UNKNOWN = "Google Calendar sync failed. Showing the last synced events."
}
