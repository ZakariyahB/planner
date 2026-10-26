package app.planner.ai

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import app.planner.BuildConfig
import app.planner.errors.PlainErrors
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TokenResult {
    data class Token(val value: String) : TokenResult

    /** Nobody is signed in for the assistant yet, or the sign-in can't be done without the user. */
    data object NeedsSignIn : TokenResult

    data class Unavailable(val message: String) : TokenResult
}

/**
 * Gets the Google ID token the proxy checks (its audience is the Web client ID and its email must be
 * yours). Tokens last about an hour, so one is reused until shortly before it expires.
 */
@Singleton
class IdTokenProvider @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val clock: Clock,
) {
    @Volatile
    private var cached: String? = null

    val isSignedIn: Boolean get() = cached?.let { Jwt.stillValid(it, clock.instant().epochSecond, MARGIN_SECONDS) } == true

    /** The address behind the token in use, or null when nobody is signed in. */
    val email: String? get() = cached?.takeIf { isSignedIn }?.let(Jwt::email)

    fun invalidate() {
        cached = null
    }

    /**
     * Pass an Activity as [activity] to allow Google's sign-in screen; without one, only an account that has
     * already signed in is used, silently, and anything else answers [TokenResult.NeedsSignIn].
     */
    suspend fun token(activity: Context? = null): TokenResult {
        cached?.takeIf { Jwt.stillValid(it, clock.instant().epochSecond, MARGIN_SECONDS) }?.let { return TokenResult.Token(it) }
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
            return TokenResult.Unavailable(PlainErrors.aiNotSetUp("GOOGLE_WEB_CLIENT_ID"))
        }

        val interactive = activity != null
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(!interactive)
            .setAutoSelectEnabled(!interactive)
            .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val context = activity ?: appContext

        return try {
            val credential = CredentialManager.create(context).getCredential(context, request).credential
            if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                cached = token
                TokenResult.Token(token)
            } else {
                TokenResult.Unavailable(PlainErrors.googleAuth(null))
            }
        } catch (e: NoCredentialException) {
            TokenResult.NeedsSignIn
        } catch (e: GetCredentialCancellationException) {
            if (interactive) TokenResult.Unavailable(PlainErrors.googleAuth(CANCELLED)) else TokenResult.NeedsSignIn
        } catch (e: GetCredentialException) {
            // Google needs the user: only worth explaining when they asked to sign in.
            if (interactive) TokenResult.Unavailable(PlainErrors.googleAuth(null)) else TokenResult.NeedsSignIn
        } catch (e: GoogleIdTokenParsingException) {
            TokenResult.Unavailable(PlainErrors.googleAuth(null))
        }
    }

    private companion object {
        const val MARGIN_SECONDS = 120L
        const val CANCELLED = 16 // Google Play services' status code for "the user backed out"
    }
}
