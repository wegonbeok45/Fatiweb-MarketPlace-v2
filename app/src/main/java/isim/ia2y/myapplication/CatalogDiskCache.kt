package isim.ia2y.myapplication

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object CatalogDiskCache {
    private const val PREFS = "catalog_disk_cache"
    private const val KEY_PRODUCTS = "products_json"
    private const val KEY_LAST_SYNC = "last_sync_ms"
    private const val KEY_LAST_REFRESH = "last_refresh_ms"

    fun load(context: Context): List<Product> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PRODUCTS, null)
            ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                productFromJson(array.optJSONObject(index))
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, products: List<Product>) {
        val array = JSONArray()
        products.forEach { product -> array.put(product.toJson()) }
        val lastSync = products.maxOfOrNull { it.updatedAtMillis } ?: System.currentTimeMillis()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PRODUCTS, array.toString())
            .putLong(KEY_LAST_SYNC, lastSync)
            .putLong(KEY_LAST_REFRESH, System.currentTimeMillis())
            .apply()
    }

    fun lastSyncMillis(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_SYNC, 0L)

    fun lastRefreshMillis(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_REFRESH, 0L)

    fun isFresh(context: Context, ttlMillis: Long): Boolean {
        val lastRefresh = lastRefreshMillis(context)
        return lastRefresh > 0L && System.currentTimeMillis() - lastRefresh < ttlMillis
    }

    private fun Product.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("subtitle", subtitle)
        .put("price", price)
        .put("priceMinor", priceMinor)
        .put("rating", rating)
        .put("reviewsCount", reviewsCount)
        .put("tags", JSONArray(tags))
        .put("description", description)
        .put("bullets", JSONArray(bullets))
        .put("imageUrl", imageUrl ?: "")
        .put("imageUrls", JSONArray(imageUrls))
        .put("thumbnailUrl", thumbnailUrl ?: "")
        .put("thumbnailUrls", JSONArray(thumbnailUrls))
        .put("category", category)
        .put("categoryIds", JSONArray(categoryIds))
        .put("categoryLeafId", categoryLeafId)
        .put("origin", origin)
        .put("stock", stock)
        .put("isBio", isBio)
        .put("isActive", isActive)
        .put("status", status)
        .put("searchKeywords", JSONArray(searchKeywords))
        .put("sellerId", sellerId)
        .put("sellerName", sellerName)
        .put("sellerAvatarUrl", sellerAvatarUrl)
        .put("sellerVerifiedAt", timestampToMillis(sellerVerifiedAt))
        .put("sellerMemberSince", timestampToMillis(sellerMemberSince))
        .put("sellerTotalSold", sellerTotalSold)
        .put("sellerRating", sellerRating)
        .put("sellerRatingCount", sellerRatingCount)
        .put("createdAt", createdAtMillis)
        .put("updatedAt", updatedAtMillis)

    private fun productFromJson(json: JSONObject?): Product? {
        if (json == null) return null
        val id = json.optString("id").trim()
        if (id.isBlank()) return null
        return Product(
            id = id,
            title = json.optString("title"),
            subtitle = json.optString("subtitle"),
            price = json.optDouble("price", 0.0),
            priceMinor = json.optLong("priceMinor", toMinorUnits(json.optDouble("price", 0.0))),
            rating = json.optDouble("rating", 0.0),
            reviewsCount = json.optInt("reviewsCount", 0),
            tags = json.optJSONArray("tags").toStringList(),
            description = json.optString("description"),
            bullets = json.optJSONArray("bullets").toStringList(),
            imageRes = 0,
            imageUrl = json.optString("imageUrl").takeIf { it.isNotBlank() },
            imageUrls = json.optJSONArray("imageUrls").toStringList(),
            thumbnailUrl = json.optString("thumbnailUrl").takeIf { it.isNotBlank() },
            thumbnailUrls = json.optJSONArray("thumbnailUrls").toStringList(),
            category = json.optString("category", "electronics"),
            categoryIds = json.optJSONArray("categoryIds").toStringList(),
            categoryLeafId = json.optString("categoryLeafId", "")
                .ifBlank { json.optJSONArray("categoryIds").toStringList().lastOrNull() ?: json.optString("category", "electronics") },
            origin = json.optString("origin", "tunisia"),
            stock = json.optInt("stock", 0),
            isBio = json.optBoolean("isBio", false),
            isActive = json.optBoolean("isActive", true),
            status = json.optString("status", "published"),
            searchKeywords = json.optJSONArray("searchKeywords").toStringList(),
            sellerId = json.optString("sellerId"),
            sellerName = json.optString("sellerName"),
            sellerAvatarUrl = json.optString("sellerAvatarUrl"),
            sellerVerifiedAt = json.optLong("sellerVerifiedAt", 0L).takeIf { it > 0L },
            sellerMemberSince = json.optLong("sellerMemberSince", 0L).takeIf { it > 0L },
            sellerTotalSold = json.optInt("sellerTotalSold", 0),
            sellerRating = json.optDouble("sellerRating", 0.0),
            sellerRatingCount = json.optInt("sellerRatingCount", 0),
            createdAt = json.optLong("createdAt", 0L),
            updatedAt = json.optLong("updatedAt", 0L)
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length())
            .mapNotNull { index -> optString(index).trim().takeIf { it.isNotBlank() } }
    }

    private fun timestampToMillis(value: Any?): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> value.toLong()
        is Float -> value.toLong()
        is com.google.firebase.Timestamp -> value.toDate().time
        is Map<*, *> -> {
            val seconds = (value["seconds"] ?: value["_seconds"]) as? Number
            val nanos = (value["nanoseconds"] ?: value["_nanoseconds"]) as? Number
            seconds?.toLong()?.let { it * 1000L + ((nanos?.toLong() ?: 0L) / 1_000_000L) } ?: 0L
        }
        else -> 0L
    }
}
