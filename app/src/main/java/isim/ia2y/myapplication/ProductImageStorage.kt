package isim.ia2y.myapplication

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.StorageReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream

object ProductImageStorage {
    private const val TAG = "ProductImageStorage"
    private const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024
    private const val INLINE_TARGET_MAX_BYTES = 220 * 1024
    private const val MAX_IMAGE_DIMENSION = 1600

    private data class PreparedImage(
        val bytes: ByteArray,
        val contentType: String,
        val extension: String
    )

    private val storage get() = FirebaseStorage.getInstance().reference

    suspend fun uploadProductImage(context: Context, productId: String, uri: Uri): String {
        val preparedImage = prepareImageForUpload(context, uri)
        val remoteRef = storage
            .child("product_images")
            .child(productId)
            .child("${System.currentTimeMillis()}.${preparedImage.extension}")

        val metadata = StorageMetadata.Builder()
            .setContentType(preparedImage.contentType)
            .build()

        return try {
            remoteRef.putBytes(preparedImage.bytes, metadata).await()
            awaitDownloadUrl(remoteRef)
        } catch (error: Exception) {
            Log.w(TAG, "Remote upload failed for $productId, using inline image fallback: ${error.message}")
            buildInlineImageDataUrl(preparedImage)
        }
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
        throw IllegalStateException("Image upload finished but the download URL was unavailable.")
    }

    private fun prepareImageForUpload(context: Context, uri: Uri): PreparedImage {
        val normalized = normalizeToCompressedJpeg(context, uri)
        if (normalized != null) return normalized

        val contentType = resolveContentType(context, uri)
        val extension = resolveExtension(context, uri, contentType)
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Unable to read the selected image.")
        if (rawBytes.size > MAX_UPLOAD_BYTES) {
            throw IllegalStateException("Selected image is too large to upload.")
        }
        return PreparedImage(
            bytes = rawBytes,
            contentType = contentType,
            extension = extension
        )
    }

    private fun normalizeToCompressedJpeg(context: Context, uri: Uri): PreparedImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        } ?: return null

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        } ?: return null

        val compressedBytes = compressBitmap(bitmap)
        bitmap.recycle()

        return PreparedImage(
            bytes = compressedBytes,
            contentType = "image/jpeg",
            extension = "jpg"
        )
    }

    private fun calculateInSampleSize(width: Int, height: Int): Int {
        var inSampleSize = 1
        var currentWidth = width
        var currentHeight = height
        while (currentWidth > MAX_IMAGE_DIMENSION || currentHeight > MAX_IMAGE_DIMENSION) {
            inSampleSize *= 2
            currentWidth /= 2
            currentHeight /= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun compressBitmap(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream()
        var quality = 86
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)

        while (output.size() > INLINE_TARGET_MAX_BYTES && quality > 42) {
            output.reset()
            quality -= 8
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        }

        if (output.size() > MAX_UPLOAD_BYTES) {
            throw IllegalStateException("Compressed image is still too large to upload.")
        }

        return output.toByteArray()
    }

    private fun buildInlineImageDataUrl(preparedImage: PreparedImage): String {
        val base64 = Base64.encodeToString(preparedImage.bytes, Base64.NO_WRAP)
        return "data:${preparedImage.contentType};base64,$base64"
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
