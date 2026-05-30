package isim.ia2y.myapplication

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.storage.StorageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.IOException

object UserAvatarService {
    private const val TAG = "UserAvatarService"

    enum class FailureReason { NETWORK, PERMISSION, TOO_LARGE, UNKNOWN }

    class AvatarUploadException(
        val reason: FailureReason,
        cause: Throwable,
    ) : Exception(cause.message, cause)

    suspend fun uploadAndSaveAvatar(context: Context, uid: String, uri: Uri): String = withContext(Dispatchers.IO) {
        require(uid.isNotBlank()) { "A signed-in user is required to update a profile photo." }

        val appContext = context.applicationContext

        // Refresh the auth token first so a recently-promoted vendor account has fresh role
        // claims and a non-expired session before hitting Storage and Firestore rules.
        runCatching {
            FirebaseAuthManager.currentUser?.getIdToken(true)?.await()
        }.onFailure { Log.w(TAG, "Auth token refresh failed for user $uid.", it) }

        val remoteUrl = try {
            UserAvatarStorage.uploadAvatar(appContext, uid, uri)
        } catch (e: Exception) {
            throw AvatarUploadException(classify(e), e)
        }

        try {
            UserService.updateUserAvatarUrl(uid, remoteUrl)
        } catch (e: Exception) {
            throw AvatarUploadException(classify(e), e)
        }

        runCatching { FirebaseAuthManager.updatePhotoUrl(remoteUrl).getOrThrow() }
            .onFailure { error -> Log.w(TAG, "Firebase Auth photo URL sync failed for user $uid.", error) }
        remoteUrl
    }

    private fun classify(error: Throwable): FailureReason {
        return when {
            error is FirebaseFirestoreException &&
                error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED -> FailureReason.PERMISSION
            error is FirebaseFirestoreException &&
                error.code == FirebaseFirestoreException.Code.UNAUTHENTICATED -> FailureReason.PERMISSION
            error is FirebaseFirestoreException &&
                error.code == FirebaseFirestoreException.Code.UNAVAILABLE -> FailureReason.NETWORK
            error is StorageException &&
                error.errorCode == StorageException.ERROR_NOT_AUTHENTICATED -> FailureReason.PERMISSION
            error is StorageException &&
                error.errorCode == StorageException.ERROR_NOT_AUTHORIZED -> FailureReason.PERMISSION
            error is StorageException &&
                error.errorCode == StorageException.ERROR_QUOTA_EXCEEDED -> FailureReason.TOO_LARGE
            error is StorageException &&
                error.errorCode == StorageException.ERROR_RETRY_LIMIT_EXCEEDED -> FailureReason.NETWORK
            error is IOException -> FailureReason.NETWORK
            error is IllegalStateException && error.message?.contains("too large", ignoreCase = true) == true ->
                FailureReason.TOO_LARGE
            else -> FailureReason.UNKNOWN
        }
    }
}
