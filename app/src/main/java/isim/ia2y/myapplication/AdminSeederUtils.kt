package isim.ia2y.myapplication

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Locale

object AdminSeederUtils {
    private const val TAG = "AdminSeederUtils"

    suspend fun seedDatabase(context: Context, onProgress: (Int, Int) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val jsonString = context.assets.open("products_seed.json").bufferedReader().use { it.readText() }
                val jsonArray = JSONArray(jsonString)
                val total = jsonArray.length()
                Log.d(TAG, "Starting seeding of $total products in chunks of 25...")
                
                val db = FirebaseFirestore.getInstance()
                val productsRef = db.collection(FirestoreCollections.PRODUCTS)
                var currentBatch = db.batch()
                var batchCount = 0
                var totalSuccess = 0

                for (i in 0 until total) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.getString("id")
                    
                    // ... (keep middle data same) ...
                    val title = obj.getString("title")
                    val subtitle = obj.getString("subtitle")
                    val description = obj.getString("description")
                    val category = obj.getString("category")
                    val price = obj.getDouble("price")
                    val stock = obj.getInt("stock")
                    val origin = obj.getString("origin")
                    val sourceUrl = obj.getString("sourceImageUrl")
                    val isBio = obj.getBoolean("isBio")
                    
                    val tags = mutableListOf<String>()
                    if (obj.has("tags")) {
                        val tagsArray = obj.getJSONArray("tags")
                        for (j in 0 until tagsArray.length()) {
                            tags.add(tagsArray.getString(j))
                        }
                    }
                    
                    val bullets = mutableListOf<String>()
                    if (obj.has("bullets")) {
                        val bulletsArray = obj.getJSONArray("bullets")
                        for (j in 0 until bulletsArray.length()) {
                            bullets.add(bulletsArray.getString(j))
                        }
                    }

                    onProgress(i, total)

                    val tempFile = File(context.cacheDir, "seed_$id.jpg")
                    val uploadedUrl = try {
                        val input = URL(sourceUrl).openStream()
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                        Log.d(TAG, "[$id] Image download OK")

                        val uri = Uri.fromFile(tempFile)
                        val url = ProductImageStorage.uploadProductImage(context, id, uri)
                        Log.d(TAG, "[$id] Image upload OK -> $url")
                        url
                    } catch (e: Exception) {
                        Log.e(TAG, "[$id] Remote image import failed, using local fallback visuals: ${e.message}")
                        ""
                    } finally {
                        tempFile.delete()
                    }

                    val searchKeywords = title.lowercase(Locale.getDefault()).split(" ").filter { it.length > 2 }
                                            .plus(subtitle.lowercase(Locale.getDefault()).split(" "))
                                            .plus(category.lowercase(Locale.getDefault()))
                                            .distinct()

                    val productData = mapOf(
                        "id" to id,
                        "title" to title,
                        "subtitle" to subtitle,
                        "price" to price,
                        "rating" to 0.0,
                        "reviewsCount" to 0,
                        "tags" to tags,
                        "description" to description,
                        "bullets" to bullets,
                        "imageUrl" to uploadedUrl,
                        "imageUrls" to listOf(uploadedUrl).filter { it.isNotBlank() },
                        "category" to category,
                        "categoryIds" to listOf(category),
                        "origin" to origin,
                        "stock" to stock,
                        "isBio" to isBio,
                        "isActive" to true,
                        "status" to "published",
                        "searchKeywords" to searchKeywords,
                        "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                        "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    )

                    currentBatch.set(productsRef.document(id), productData, SetOptions.merge())
                    batchCount++
                    totalSuccess++

                    if (batchCount >= 25) {
                        Log.d(TAG, "Committing intermediate batch of $batchCount...")
                        currentBatch.commit().await()
                        currentBatch = db.batch()
                        batchCount = 0
                    }
                }

                if (batchCount > 0) {
                    Log.d(TAG, "Committing final batch of $batchCount...")
                    currentBatch.commit().await()
                }

                if (totalSuccess == 0) {
                    throw Exception("No products were written to Firestore.")
                }
                
                Log.d(TAG, "Seeding complete! Total products written: $totalSuccess")
                
                CatalogSyncManager.refreshAsync(force = true)
                onProgress(total, total)

            } catch (e: Exception) {
                Log.e(TAG, "Seeding failed: ${e.message}", e)
                throw e
            }
        }
    }
}
