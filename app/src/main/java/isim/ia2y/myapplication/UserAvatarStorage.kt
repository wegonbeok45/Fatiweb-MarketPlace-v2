package isim.ia2y.myapplication

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

object UserAvatarStorage {
    private val storage get() = FirebaseStorage.getInstance().reference

    suspend fun uploadAvatar(context: Context, uid: String, uri: Uri): String {
        val contentType = resolveContentType(context, uri)
        val extension = resolveExtension(context, uri, contentType)
        val remoteRef = storage
            .child("user_avatars")
            .child(uid)
            .child("avatar.$extension")

        val metadata = StorageMetadata.Builder()
            .setContentType(contentType)
            .build()

        remoteRef.putFile(uri, metadata).await()
        return awaitDownloadUrl(remoteRef)
    }

    private suspend fun awaitDownloadUrl(remoteRef: StorageReference): String {
        repeat(5) { attempt ->
            try {
                return remoteRef.downloadUrl.await().toString()
            } catch (error: Exception) {
                val storageError = error as? StorageException
                if (storageError?.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND || attempt == 4) {
                    throw error
                }
                delay(250L * (attempt + 1))
            }
        }
        throw IllegalStateException("Avatar upload finished but the download URL was unavailable.")
    }

    private fun resolveExtension(context: Context, uri: Uri, contentType: String): String {
        val mime = context.contentResolver.getType(uri).orEmpty().ifBlank { contentType }
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: "jpg"
    }

    private fun resolveContentType(context: Context, uri: Uri): String {
        return context.contentResolver.getType(uri)
            ?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"
    }
}
