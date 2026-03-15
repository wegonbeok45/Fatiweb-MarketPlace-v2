package isim.ia2y.myapplication

import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.userProfileChangeRequest
import kotlinx.coroutines.tasks.await

/**
 * Central wrapper around FirebaseAuth.
 * All auth operations are suspend functions to be called from a coroutine.
 */
// Cette classe organise cette partie de l'app.
object FirebaseAuthManager {

    private val auth: FirebaseAuth get() = FirebaseAuth.getInstance()

    /** The currently signed-in user, or null if no one is logged in. */
    val currentUser: FirebaseUser? get() = auth.currentUser

    /** True if a real (non-guest) user is logged in. */
    val isLoggedIn: Boolean get() = auth.currentUser != null

    /**
     * Sign in with Facebook token.
     * @return Result.success(user) on success, Result.failure(exception) on error.
     */
    // Cette fonction fait une action de cette partie de l'app.
    suspend fun signInWithFacebook(token: String): Result<FirebaseUser> {
        return try {
            val credential = FacebookAuthProvider.getCredential(token)
            val result = auth.signInWithCredential(credential).await()
            val user = result.user!!

            // Also check if we need to save to Firestore (if new user)
            // For now, consistent with email register, we can save profile
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
     * @return Result.success(user) on success, Result.failure(exception) on error.
     */
    // Cette fonction fait une action de cette partie de l'app.
    suspend fun signInWithGoogle(idToken: String): Result<FirebaseUser> {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = auth.signInWithCredential(credential).await()
            val user = result.user!!

            // Save profile to Firestore (for new users)
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
     * @return Result.success(user) on success, Result.failure(exception) on error.
     */
    // Cette fonction fait une action de cette partie de l'app.
    suspend fun signIn(email: String, password: String): Result<FirebaseUser> {
        return try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            Result.success(result.user!!)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Create a new account with email and password, then save a user profile doc in Firestore.
     * @return Result.success(user) on success, Result.failure(exception) on error.
     */
    // Cette fonction fait une action de cette partie de l'app.
    suspend fun register(email: String, password: String, displayName: String): Result<FirebaseUser> {
        return try {
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user!!

            // Update Firebase Auth display name
            val profileUpdates = userProfileChangeRequest {
                this.displayName = displayName
            }
            user.updateProfile(profileUpdates).await()

            // Save user document to Firestore
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

    /**
     * Sign out the current user.
     */
    // Cette fonction fait une action de cette partie de l'app.
    fun signOut() {
        auth.signOut()
    }

    /**
     * Returns a friendly error message from a Firebase Auth exception.
     */
    // Cette fonction fait une action de cette partie de l'app.
    fun friendlyError(e: Throwable): String {
        val msg = e.message?.lowercase() ?: ""
        return when {
            msg.contains("email address is already in use") -> "Cette adresse e-mail est déjà utilisée."
            msg.contains("password is invalid") || msg.contains("wrong password") -> "Mot de passe incorrect."
            msg.contains("no user record") || msg.contains("user not found") -> "Aucun compte trouvé avec cet e-mail."
            msg.contains("badly formatted") -> "Adresse e-mail invalide."
            msg.contains("network") -> "Erreur réseau. Vérifiez votre connexion."
            else -> "Une erreur est survenue. Réessayez."
        }
    }
}
