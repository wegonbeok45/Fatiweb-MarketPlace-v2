package isim.ia2y.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream

object ProductImageStorage {
    private const val TAG = "ProductImageStorage"
    private const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024
    private const val MAX_SOURCE_IMAGE_BYTES = 30 * 1024 * 1024
    private const val MAX_IMAGE_DIMENSION = 1600
    private const val PRODUCT_IMAGES_ROOT = "product_images"
    private const val USER_AVATARS_ROOT = "user_avatars"

    private data class PreparedImage(
        val bytes: ByteArray,
        val contentType: String
    )

    suspend fun uploadProductImage(
        context: Context,
        productId: String,
        uri: Uri,
        ownerId: String = FirebaseAuthManager.currentRealUid.orEmpty()
    ): String = withContext(Dispatchers.IO) {
        val safeProductId = productId.safeStorageSegment("product")
        val safeOwnerId = ownerId.safeStorageSegment("owner")
        uploadFirebaseImage(
            context = context,
            uri = uri,
            storagePath = "$PRODUCT_IMAGES_ROOT/$safeOwnerId/$safeProductId/${safeProductId}_${System.currentTimeMillis()}.jpg",
            traceName = "product_image_upload"
        )
    }

    suspend fun uploadProductImages(
        context: Context,
        productId: String,
        uris: List<Uri>,
        maxParallelUploads: Int = 2,
        ownerId: String = FirebaseAuthManager.currentRealUid.orEmpty()
    ): List<String> = coroutineScope {
        if (uris.isEmpty()) return@coroutineScope emptyList()
        val semaphore = Semaphore(maxParallelUploads.coerceIn(1, 3))
        uris.mapIndexed { index, uri ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    uploadProductImage(context, "${productId}_${index + 1}", uri, ownerId)
                }
            }
        }.awaitAll()
    }

    suspend fun uploadUserAvatar(
        context: Context,
        uid: String,
        uri: Uri
    ): String = withContext(Dispatchers.IO) {
        val safeUid = uid.trim().replace("/", "_").take(128).ifBlank { "user" }
        uploadFirebaseImage(
            context = context,
            uri = uri,
            storagePath = "$USER_AVATARS_ROOT/$safeUid/avatar_${System.currentTimeMillis()}.jpg",
            traceName = "user_avatar_storage_upload"
        )
    }

    private suspend fun uploadFirebaseImage(
        context: Context,
        uri: Uri,
        storagePath: String,
        traceName: String
    ): String {
        val trace = FirebasePerformance.getInstance().newTrace(traceName)
        trace.start()
        val preparedImage = prepareImageForUpload(context, uri)
        trace.putMetric("compressed_bytes", preparedImage.bytes.size.toLong())

        return try {
            val metadata = StorageMetadata.Builder()
                .setContentType(preparedImage.contentType)
                .setCacheControl("public, max-age=31536000, immutable")
                .build()
            val ref = Firebase.storage.reference.child(storagePath)
            ref.putBytes(preparedImage.bytes, metadata).await()
            val downloadUrl = ref.downloadUrl.await().toString()
            trace.putMetric("uploaded_bytes", preparedImage.bytes.size.toLong())
            Log.d(TAG, "Uploaded image to Firebase Storage: $storagePath")
            downloadUrl
        } catch (error: Exception) {
            Log.w(TAG, "Firebase Storage image upload failed for $storagePath: ${error.message}", error)
            throw error
        } finally {
            trace.stop()
        }
    }

    private fun prepareImageForUpload(context: Context, uri: Uri): PreparedImage {
        rejectOversizedSource(context, uri)
        val normalized = try {
            normalizeToCompressedJpeg(context, uri)
        } catch (error: OutOfMemoryError) {
            throw IllegalStateException("Selected image is too large to process.", error)
        }
        if (normalized != null) return normalized

        val rawBytes = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Unable to read the selected image.")
        } catch (error: OutOfMemoryError) {
            throw IllegalStateException("Selected image is too large to upload.", error)
        }
        if (rawBytes.size > MAX_UPLOAD_BYTES) {
            throw IllegalStateException("Selected image is too large to upload.")
        }
        return PreparedImage(
            bytes = rawBytes,
            contentType = resolveImageContentType(context, uri)
        )
    }

    private fun rejectOversizedSource(context: Context, uri: Uri) {
        val size = resolveSourceSizeBytes(context, uri) ?: return
        if (size > MAX_SOURCE_IMAGE_BYTES) {
            throw IllegalStateException("Selected image is too large to process.")
        }
    }

    private fun resolveSourceSizeBytes(context: Context, uri: Uri): Long? {
        val metadataSize = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && cursor.moveToFirst()) cursor.getLong(index) else null
                }
        }.getOrNull()
        if (metadataSize != null && metadataSize > 0L) return metadataSize

        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it > 0L }
            }
        }.getOrNull()
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
            contentType = "image/jpeg"
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

        while (output.size() > 900 * 1024 && quality > 42) {
            output.reset()
            quality -= 8
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
        }

        if (output.size() > MAX_UPLOAD_BYTES) {
            throw IllegalStateException("Compressed image is still too large to upload.")
        }

        return output.toByteArray()
    }

    private fun resolveImageContentType(context: Context, uri: Uri): String {
        return context.contentResolver.getType(uri)
            ?.takeIf { it.startsWith("image/") }
            ?: "image/jpeg"
    }

    private fun String.safeStorageSegment(fallback: String): String {
        return replace("[^a-zA-Z0-9_-]+".toRegex(), "_")
            .trim('_')
            .take(96)
            .ifBlank { fallback }
    }
}
