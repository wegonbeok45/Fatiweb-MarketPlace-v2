package isim.ia2y.myapplication

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

object UserAvatarStorage {
    private val storage get() = FirebaseStorage.getInstance().reference

    suspend fun uploadAvatar(context: Context, uid: String, uri: Uri): String {
        val extension = resolveExtension(context, uri)
        val remoteRef = storage
            .child("user_avatars")
            .child(uid)
            .child("avatar.$extension")

        remoteRef.putFile(uri).await()
        return remoteRef.downloadUrl.await().toString()
    }

    private fun resolveExtension(context: Context, uri: Uri): String {
        val mime = context.contentResolver.getType(uri).orEmpty()
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: "jpg"
    }
}
