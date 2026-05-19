package isim.ia2y.myapplication

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object UserAvatarService {
    private const val TAG = "UserAvatarService"

    suspend fun uploadAndSaveAvatar(context: Context, uid: String, uri: Uri): String = withContext(Dispatchers.IO) {
        require(uid.isNotBlank()) { "A signed-in user is required to update a profile photo." }

        val appContext = context.applicationContext
        val remoteUrl = UserAvatarStorage.uploadAvatar(appContext, uid, uri)
        UserService.updateUserAvatarUrl(uid, remoteUrl)

        runCatching { FirebaseAuthManager.updatePhotoUrl(remoteUrl).getOrThrow() }
            .onFailure { error -> Log.w(TAG, "Firebase Auth photo URL sync failed for user $uid.", error) }
        remoteUrl
    }
}
