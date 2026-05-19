package isim.ia2y.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

data class UploadedChatImage(
    val imageUrl: String,
    val thumbnailUrl: String,
    val storagePath: String
)

object ChatMediaStorage {
    suspend fun uploadImage(
        context: Context,
        conversationId: String,
        uid: String,
        clientMessageId: String,
        uri: Uri,
        onProgress: (Int) -> Unit
    ): UploadedChatImage {
        val imageBytes = compressImage(context, uri, maxSize = 1600, quality = 82)
        val thumbnailBytes = compressImage(context, uri, maxSize = 360, quality = 76)
        val root = FirebaseStorage.getInstance().reference
            .child("chat_media")
            .child(conversationId)
            .child(uid)
            .child(clientMessageId)
        val imageRef = root.child("image.jpg")
        val thumbRef = root.child("thumb.jpg")
        val jpegMetadata = StorageMetadata.Builder()
            .setContentType("image/jpeg")
            .build()

        imageRef.putBytes(imageBytes, jpegMetadata)
            .addOnProgressListener { snapshot ->
                val total = snapshot.totalByteCount.coerceAtLeast(1L)
                onProgress(((snapshot.bytesTransferred * 85) / total).toInt().coerceIn(1, 85))
            }
            .await()
        thumbRef.putBytes(thumbnailBytes, jpegMetadata)
            .addOnProgressListener { snapshot ->
                val total = snapshot.totalByteCount.coerceAtLeast(1L)
                val partial = ((snapshot.bytesTransferred * 15) / total).toInt()
                onProgress((85 + partial).coerceIn(86, 99))
            }
            .await()

        val imageUrl = imageRef.downloadUrl.await().toString()
        val thumbnailUrl = thumbRef.downloadUrl.await().toString()
        onProgress(100)
        return UploadedChatImage(
            imageUrl = imageUrl,
            thumbnailUrl = thumbnailUrl,
            storagePath = imageRef.path
        )
    }

    private suspend fun compressImage(
        context: Context,
        uri: Uri,
        maxSize: Int,
        quality: Int
    ): ByteArray = withContext(Dispatchers.IO) {
        val original = context.contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input) ?: throw IllegalArgumentException("Could not read image.")
        }
        val scale = minOf(1f, maxSize.toFloat() / maxOf(original.width, original.height).coerceAtLeast(1))
        val targetWidth = (original.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (original.height * scale).toInt().coerceAtLeast(1)
        val resized = if (targetWidth == original.width && targetHeight == original.height) {
            original
        } else {
            Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true)
        }
        ByteArrayOutputStream().use { output ->
            resized.compress(Bitmap.CompressFormat.JPEG, quality, output)
            if (resized !== original) resized.recycle()
            original.recycle()
            output.toByteArray()
        }
    }
}
