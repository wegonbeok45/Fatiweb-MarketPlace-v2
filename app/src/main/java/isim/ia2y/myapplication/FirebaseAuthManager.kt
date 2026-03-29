package isim.ia2y.myapplication

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
import kotlinx.coroutines.tasks.await

/**
 * Central wrapper around FirebaseAuth.
 * All auth operations are suspend functions to be called from a coroutine.
 */
object FirebaseAuthManager {

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    /** The currently signed-in user, or null if no one is logged in. */
    val currentUser: FirebaseUser? get() = auth.currentUser

    /** True if a real (non-guest) user is logged in. */
    val isLoggedIn: Boolean get() = auth.currentUser != null

    /**
     * Sign in with Facebook token.
     */
    suspend fun signInWithFacebook(token: String): Result<FirebaseUser> {
        return try {
            val credential = FacebookAuthProvider.getCredential(token)
            val result = auth.signInWithCredential(credential).await()
            val user = result.user!!
            FirestoreService.saveUserProfile(
                uid = user.uid,
                name = user.displayName ?: "User",
                email = user.email ?: ""
            )
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Sign in with Google ID token.
     */
    suspend fun signInWithGoogle(idToken: String): Result<FirebaseUser> {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = auth.signInWithCredential(credential).await()
            val user = result.user!!
            FirestoreService.saveUserProfile(
                uid = user.uid,
                name = user.displayName ?: "User",
                email = user.email ?: ""
            )
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Sign in with email and password.
     */
    suspend fun signIn(email: String, password: String): Result<FirebaseUser> {
        return try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val user = result.user!!
            FirestoreService.saveUserProfile(
                uid = user.uid,
                name = user.displayName ?: user.email?.substringBefore("@").orEmpty(),
                email = user.email.orEmpty()
            )
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Create a new account with email and password, then save a user profile doc in Firestore.
     */
    suspend fun register(email: String, password: String, displayName: String): Result<FirebaseUser> {
        return try {
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user!!

            val profileUpdates = userProfileChangeRequest {
                this.displayName = displayName
            }
            user.updateProfile(profileUpdates).await()

            FirestoreService.saveUserProfile(
                uid = user.uid,
                name = displayName,
                email = email
            )

            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> {
        return try {
            auth.sendPasswordResetEmail(email).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateDisplayName(displayName: String): Result<FirebaseUser> {
        val user = auth.currentUser ?: return Result.failure(IllegalStateException("No user logged in"))
        return try {
            val profileUpdates = userProfileChangeRequest {
                this.displayName = displayName
            }
            user.updateProfile(profileUpdates).await()
            FirestoreService.updateUserProfileName(user.uid, displayName)
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Sign out the current user.
     */
    fun signOut() {
        auth.signOut()
    }

    /**
     * Returns a friendly error message from a Firebase Auth exception.
     */
    fun friendlyError(error: Throwable): String {
        return when {
            error is FirebaseAuthUserCollisionException ->
                "Cette adresse e-mail est deja utilisee."
            error is FirebaseAuthInvalidUserException ->
                "Aucun compte trouve avec cet e-mail."
            error is FirebaseAuthRecentLoginRequiredException ->
                "Reconnectez-vous avant de modifier ce profil."
            error is FirebaseTooManyRequestsException ->
                "Trop de tentatives. Reessayez dans quelques instants."
            error is FirebaseNetworkException ->
                "Erreur reseau. Verifiez votre connexion."
            error is FirebaseAuthInvalidCredentialsException -> {
                when (error.errorCode) {
                    "ERROR_WRONG_PASSWORD" -> "Mot de passe incorrect."
                    "ERROR_INVALID_EMAIL" -> "Adresse e-mail invalide."
                    "ERROR_WEAK_PASSWORD" -> "Le mot de passe doit contenir au moins 6 caracteres."
                    else -> "Informations de connexion invalides."
                }
            }
            else -> "Une erreur est survenue. Reessayez."
        }
    }

    fun friendlyError(context: android.content.Context, error: Throwable): String {
        return when {
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
}
