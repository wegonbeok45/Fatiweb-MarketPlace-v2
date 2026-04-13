package isim.ia2y.myapplication

import android.util.Log
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.userProfileChangeRequest
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.tasks.await

/**
 * Central wrapper around FirebaseAuth.
 * All auth operations are suspend functions to be called from a coroutine.
 */
object FirebaseAuthManager {
    private const val TAG = "FirebaseAuthManager"
    private const val AUTH_REQUEST_TIMEOUT_MS = 15_000L
    private const val CLAIMS_TIMEOUT_MS = 5_000L
    private const val PROFILE_SYNC_TIMEOUT_MS = 8_000L

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    /** The currently signed-in user, or null if no one is logged in. */
    val currentUser: FirebaseUser? get() = auth.currentUser

    /** True if a real (non-guest) user is logged in. */
    val isLoggedIn: Boolean get() = auth.currentUser != null

    /**
     * Sign in with Facebook token.
     */
    suspend fun signInWithFacebook(token: String): Result<FirebaseUser> {
        return runAuthAction {
            val credential = FacebookAuthProvider.getCredential(token)
            val result = awaitAuthRequest { auth.signInWithCredential(credential).await() }
            val user = requireNotNull(result.user) { "Authenticated Facebook user missing" }
            syncUserProfileSafely(
                user = user,
                fallbackName = user.displayName ?: "User",
                fallbackEmail = user.email ?: ""
            )
            user
        }
    }

    /**
     * Sign in with Google ID token.
     */
    suspend fun signInWithGoogle(idToken: String): Result<FirebaseUser> {
        return runAuthAction {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = awaitAuthRequest { auth.signInWithCredential(credential).await() }
            val user = requireNotNull(result.user) { "Authenticated Google user missing" }
            syncUserProfileSafely(
                user = user,
                fallbackName = user.displayName ?: "User",
                fallbackEmail = user.email ?: ""
            )
            user
        }
    }

    /**
     * Sign in with email and password.
     */
    suspend fun signIn(email: String, password: String): Result<FirebaseUser> {
        return runAuthAction {
            val result = awaitAuthRequest { auth.signInWithEmailAndPassword(email, password).await() }
            val user = requireNotNull(result.user) { "Authenticated email user missing" }
            syncUserProfileSafely(
                user = user,
                fallbackName = user.displayName ?: user.email?.substringBefore("@").orEmpty(),
                fallbackEmail = user.email.orEmpty()
            )
            user
        }
    }

    /**
     * Create a new account with email and password, then save a user profile doc in Firestore.
     */
    suspend fun register(email: String, password: String, displayName: String): Result<FirebaseUser> {
        return runAuthAction {
            val result = awaitAuthRequest { auth.createUserWithEmailAndPassword(email, password).await() }
            val user = requireNotNull(result.user) { "Registered user missing" }

            val profileUpdates = userProfileChangeRequest {
                this.displayName = displayName
            }
            awaitAuthRequest { user.updateProfile(profileUpdates).await() }

            syncUserProfileSafely(
                user = user,
                fallbackName = displayName,
                fallbackEmail = email
            )
            user
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> {
        return runAuthAction {
            awaitAuthRequest { auth.sendPasswordResetEmail(email).await() }
        }
    }

    suspend fun updateDisplayName(displayName: String): Result<FirebaseUser> {
        val user = auth.currentUser ?: return Result.failure(IllegalStateException("No user logged in"))
        return runAuthAction {
            val profileUpdates = userProfileChangeRequest {
                this.displayName = displayName
            }
            awaitAuthRequest { user.updateProfile(profileUpdates).await() }
            awaitAuthRequest { FirestoreService.updateUserProfileName(user.uid, displayName) }
            user
        }
    }

    /**
     * Sign out the current user.
     */
    fun signOut() {
        auth.signOut()
    }

    private suspend fun resolveRoleFromClaims(user: FirebaseUser): String? {
        val claims = user.getIdToken(true).await().claims
        return when {
            claims["admin"] == true -> "admin"
            claims["role"] is String -> claims["role"] as String
            else -> null
        }
    }


    suspend fun sendEmailVerification(): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(IllegalStateException("No user logged in"))
        return runAuthAction {
            awaitAuthRequest { user.sendEmailVerification().await() }
        }
    }

    fun friendlyError(context: android.content.Context, error: Throwable): String {
        return when {
            error is TimeoutCancellationException ->
                context.getString(R.string.auth_error_timeout)
            error is FirebaseAuthUserCollisionException ->
                context.getString(R.string.auth_error_email_in_use)
            error is FirebaseAuthInvalidUserException ->
                context.getString(R.string.auth_error_no_account)
            error is FirebaseAuthRecentLoginRequiredException ->
                context.getString(R.string.auth_error_recent_login)
            error is FirebaseTooManyRequestsException ->
                context.getString(R.string.auth_error_too_many_requests)
            error is FirebaseNetworkException ->
                context.getString(R.string.auth_error_network)
            error is FirebaseAuthInvalidCredentialsException -> {
                when (error.errorCode) {
                    "ERROR_WRONG_PASSWORD" -> context.getString(R.string.auth_error_wrong_password)
                    "ERROR_INVALID_EMAIL" -> context.getString(R.string.auth_error_invalid_email)
                    "ERROR_WEAK_PASSWORD" -> context.getString(R.string.auth_error_weak_password)
                    else -> context.getString(R.string.auth_error_invalid_credentials)
                }
            }
            else -> context.getString(R.string.auth_error_generic)
        }
    }

    private suspend fun <T> runAuthAction(block: suspend () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: TimeoutCancellationException) {
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun <T> awaitAuthRequest(block: suspend () -> T): T {
        return withTimeout(AUTH_REQUEST_TIMEOUT_MS) {
            block()
        }
    }

    private suspend fun syncUserProfileSafely(
        user: FirebaseUser,
        fallbackName: String,
        fallbackEmail: String
    ) {
        val resolvedName = fallbackName.ifBlank {
            fallbackEmail.substringBefore("@").ifBlank { "User" }
        }
        try {
            withTimeout(PROFILE_SYNC_TIMEOUT_MS) {
                FirestoreService.saveUserProfile(
                    uid = user.uid,
                    name = resolvedName,
                    email = fallbackEmail,
                    roleOverride = resolveRoleFromClaimsSafely(user)
                )
            }
        } catch (error: TimeoutCancellationException) {
            Log.w(TAG, "Skipping profile sync for user ${user.uid}", error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Skipping profile sync for user ${user.uid}", error)
        }
    }

    private suspend fun resolveRoleFromClaimsSafely(user: FirebaseUser): String? {
        return try {
            withTimeout(CLAIMS_TIMEOUT_MS) {
                resolveRoleFromClaims(user)
            }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}
