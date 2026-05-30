package isim.ia2y.myapplication

import android.util.Log
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.userProfileChangeRequest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.tasks.await

/**
 * Central wrapper around FirebaseAuth.
 * All auth operations are suspend functions to be called from a coroutine.
 */
object FirebaseAuthManager {
    private const val TAG = "FirebaseAuthManager"
    private const val AUTH_REQUEST_TIMEOUT_MS = 35_000L
    private const val CLAIMS_TIMEOUT_MS = 5_000L
    private const val PROFILE_SYNC_TIMEOUT_MS = 8_000L

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The currently signed-in user, or null if no one is logged in. */
    val currentUser: FirebaseUser? get() = auth.currentUser

    /** The current non-anonymous user. Anonymous checkout accounts are treated as guests in UI/local stores. */
    val currentRealUser: FirebaseUser? get() = auth.currentUser?.takeUnless { it.isAnonymous }

    val currentRealUid: String? get() = currentRealUser?.uid

    /** True if a real (non-guest) user is logged in. */
    val isLoggedIn: Boolean get() = currentRealUser != null

    /**
     * Sign in with Google ID token.
     */
    suspend fun signInWithGoogle(idToken: String): Result<FirebaseUser> {
        return runAuthAction {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = awaitAuthRequest { auth.signInWithCredential(credential).await() }
            val user = requireNotNull(result.user) { "Authenticated Google user missing" }
            setCrashlyticsUser(user)
            syncUserProfileInBackground(
                user = user,
                fallbackName = user.displayName ?: "User",
                fallbackEmail = user.email ?: ""
            )
            warmUserRoleInBackground(user)
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
            setCrashlyticsUser(user)
            syncUserProfileInBackground(
                user = user,
                fallbackName = user.displayName ?: user.email?.substringBefore("@").orEmpty(),
                fallbackEmail = user.email.orEmpty()
            )
            warmUserRoleInBackground(user)
            user
        }
    }

    suspend fun signInAnonymously(): Result<FirebaseUser> {
        return runAuthAction {
            val result = awaitAuthRequest { auth.signInAnonymously().await() }
            val user = requireNotNull(result.user) { "Anonymous user missing" }
            setCrashlyticsUser(user)
            syncUserProfileInBackground(
                user = user,
                fallbackName = "Guest",
                fallbackEmail = ""
            )
            warmUserRoleInBackground(user)
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
            setCrashlyticsUser(user)

            val profileUpdates = userProfileChangeRequest {
                this.displayName = displayName
            }
            awaitAuthRequest { user.updateProfile(profileUpdates).await() }

            syncUserProfileInBackground(
                user = user,
                fallbackName = displayName,
                fallbackEmail = email
            )
            warmUserRoleInBackground(user)
            user
        }
    }

    /**
     * Result of starting phone verification.
     * - [verificationId] is null when Play Services auto-retrieved the SMS and signed the user in.
     * - When non-null, prompt the user for the 6-digit code and call [signInWithSmsCode].
     */
    data class PhoneVerificationStart(
        val verificationId: String?,
        val autoSignedInUser: FirebaseUser?,
        val resendToken: PhoneAuthProvider.ForceResendingToken?
    )

    data class PhoneLinkVerificationStart(
        val verificationId: String?,
        val autoLinkedUser: FirebaseUser?
    )

    /**
     * Start phone number verification. SMS is sent to [e164PhoneNumber] (must be E.164, e.g. +21612345678).
     * The [activity] is required by Firebase to mount the reCAPTCHA / Play Integrity flow if needed.
     */
    suspend fun startPhoneVerification(
        activity: android.app.Activity,
        e164PhoneNumber: String,
        resendToken: PhoneAuthProvider.ForceResendingToken? = null
    ): Result<PhoneVerificationStart> = runAuthAction {
        suspendCancellableCoroutine { cont ->
            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    backgroundScope.launch {
                        val signInResult = runCatching {
                            auth.signInWithCredential(credential).await()
                        }
                        if (cont.isActive) {
                            signInResult.fold(
                                onSuccess = { res ->
                                    val user = res.user
                                    if (user != null) {
                                        setCrashlyticsUser(user)
                                        syncUserProfileInBackground(
                                            user = user,
                                            fallbackName = user.displayName ?: user.phoneNumber ?: "User",
                                            fallbackEmail = user.email.orEmpty()
                                        )
                                        warmUserRoleInBackground(user)
                                    }
                                    cont.resume(
                                        PhoneVerificationStart(
                                            verificationId = null,
                                            autoSignedInUser = user,
                                            resendToken = null
                                        )
                                    )
                                },
                                onFailure = { cont.resumeWithException(it) }
                            )
                        }
                    }
                }

                override fun onVerificationFailed(e: com.google.firebase.FirebaseException) {
                    Log.w(TAG, "Phone verification failed", e)
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onCodeSent(
                    verificationId: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    if (cont.isActive) {
                        cont.resume(
                            PhoneVerificationStart(
                                verificationId = verificationId,
                                autoSignedInUser = null,
                                resendToken = token
                            )
                        )
                    }
                }
            }

            val options = PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(e164PhoneNumber)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(activity)
                .setCallbacks(callbacks)
                .apply { if (resendToken != null) setForceResendingToken(resendToken) }
                .build()
            PhoneAuthProvider.verifyPhoneNumber(options)
        }
    }

    /**
     * Complete phone sign-in with the SMS code the user typed in.
     */
    suspend fun signInWithSmsCode(verificationId: String, smsCode: String): Result<FirebaseUser> {
        return runAuthAction {
            val credential = PhoneAuthProvider.getCredential(verificationId, smsCode)
            val result = awaitAuthRequest { auth.signInWithCredential(credential).await() }
            val user = requireNotNull(result.user) { "Authenticated phone user missing" }
            setCrashlyticsUser(user)
            syncUserProfileInBackground(
                user = user,
                fallbackName = user.displayName ?: user.phoneNumber ?: "User",
                fallbackEmail = user.email.orEmpty()
            )
            warmUserRoleInBackground(user)
            user
        }
    }

    suspend fun startPhoneLinkVerification(
        activity: android.app.Activity,
        e164PhoneNumber: String
    ): Result<PhoneLinkVerificationStart> = runAuthAction {
        val current = auth.currentUser ?: throw IllegalStateException("No user logged in")
        suspendCancellableCoroutine { cont ->
            val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    backgroundScope.launch {
                        val linkResult = runCatching {
                            val result = current.linkWithCredential(credential).await()
                            requireNotNull(result.user) { "Linked user missing" }
                        }
                        if (cont.isActive) {
                            linkResult.fold(
                                onSuccess = { user ->
                                    setCrashlyticsUser(user)
                                    warmUserRoleInBackground(user)
                                    cont.resume(
                                        PhoneLinkVerificationStart(
                                            verificationId = null,
                                            autoLinkedUser = user
                                        )
                                    )
                                },
                                onFailure = { cont.resumeWithException(it) }
                            )
                        }
                    }
                }

                override fun onVerificationFailed(e: com.google.firebase.FirebaseException) {
                    Log.w(TAG, "Phone link verification failed", e)
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onCodeSent(
                    verificationId: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    if (cont.isActive) {
                        cont.resume(
                            PhoneLinkVerificationStart(
                                verificationId = verificationId,
                                autoLinkedUser = null
                            )
                        )
                    }
                }
            }

            val options = PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(e164PhoneNumber)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(activity)
                .setCallbacks(callbacks)
                .build()
            PhoneAuthProvider.verifyPhoneNumber(options)
        }
    }

    suspend fun linkCurrentUserWithSmsCode(verificationId: String, smsCode: String): Result<FirebaseUser> {
        val user = auth.currentUser ?: return Result.failure(IllegalStateException("No user logged in"))
        return runAuthAction {
            val credential = PhoneAuthProvider.getCredential(verificationId, smsCode)
            val result = awaitAuthRequest { user.linkWithCredential(credential).await() }
            val linkedUser = requireNotNull(result.user) { "Linked user missing" }
            setCrashlyticsUser(linkedUser)
            warmUserRoleInBackground(linkedUser)
            linkedUser
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

    suspend fun updatePhotoUrl(photoUrl: String): Result<FirebaseUser> {
        val user = auth.currentUser ?: return Result.failure(IllegalStateException("No user logged in"))
        return runAuthAction {
            val profileUpdates = userProfileChangeRequest {
                photoUri = Uri.parse(photoUrl)
            }
            awaitAuthRequest { user.updateProfile(profileUpdates).await() }
            user
        }
    }

    /**
     * Sign out the current user.
     */
    fun signOut() {
        val uid = auth.currentUser?.uid
        UserService.clearCache()
        AdminService.clearAllCaches()
        AdminSession.clear()
        AppStartupCoordinator.resetDeferred()
        CatalogSyncManager.stop()
        if (!uid.isNullOrBlank()) {
            FcmTokenService.clearTokenForSignOut(MyApplication.instance, uid)
        }
        ConversationCache.clearAll(MyApplication.instance)
        MessagingRepository.clearCaches()
        auth.signOut()
        clearCrashlyticsUser()
    }

    fun syncCrashlyticsUser() {
        setCrashlyticsUser(auth.currentUser)
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

    suspend fun reloadAndCheckEmailVerified(): Result<Boolean> {
        val user = auth.currentUser ?: return Result.failure(IllegalStateException("No user logged in"))
        return runAuthAction {
            awaitAuthRequest { user.reload().await() }
            auth.currentUser?.isEmailVerified ?: false
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
            Log.w(TAG, "Firebase auth request timed out after ${AUTH_REQUEST_TIMEOUT_MS}ms", e)
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Firebase auth request failed: ${e.message}", e)
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

    private fun syncUserProfileInBackground(
        user: FirebaseUser,
        fallbackName: String,
        fallbackEmail: String
    ) {
        backgroundScope.launch {
            syncUserProfileSafely(user, fallbackName, fallbackEmail)
        }
    }

    private fun warmUserRoleInBackground(user: FirebaseUser) {
        backgroundScope.launch {
            runCatching { UserService.fetchUserRole(user.uid, forceRefresh = true) }
        }
    }

    private fun setCrashlyticsUser(user: FirebaseUser?) {
        if (user == null) {
            clearCrashlyticsUser()
            return
        }
        CrashlyticsHelper.setUserId(user.uid)
        CrashlyticsHelper.setCustomKey("auth_is_anonymous", user.isAnonymous)
        CrashlyticsHelper.setCustomKey(
            "auth_email_domain",
            user.email?.substringAfter("@", missingDelimiterValue = "")?.takeIf { it.isNotBlank() } ?: "none"
        )
    }

    private fun clearCrashlyticsUser() {
        CrashlyticsHelper.setUserId(null)
        CrashlyticsHelper.setCustomKey("auth_is_anonymous", false)
        CrashlyticsHelper.setCustomKey("auth_email_domain", "none")
    }

    private suspend fun resolveRoleFromClaimsSafely(user: FirebaseUser): String? {
        return try {
            withTimeout(CLAIMS_TIMEOUT_MS) {
                resolveRoleFromClaims(user)
            }
                ?.takeIf { role -> role == UserRoles.ADMIN || role == UserRoles.VENDEUR }
        } catch (_: TimeoutCancellationException) {
            null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}
