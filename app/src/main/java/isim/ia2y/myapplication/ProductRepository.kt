package isim.ia2y.myapplication

import androidx.annotation.DrawableRes
import java.text.Normalizer
import java.util.Locale

data class Product(
    val id: String,
    val title: String,
    val subtitle: String,
    val price: Double,
    val priceMinor: Long = toMinorUnits(price),
    val rating: Double,
    val reviewsCount: Int,
    val tags: List<String>,
    val description: String,
    val bullets: List<String>,
    @DrawableRes val imageRes: Int,
    val imageUrl: String? = null,
    val imageUrls: List<String> = emptyList(),
    val thumbnailUrl: String? = null,
    val thumbnailUrls: List<String> = emptyList(),
    val category: String = "electronics",
    val categoryIds: List<String> = emptyList(),
    val categoryLeafId: String = categoryIds.lastOrNull() ?: category,
    val origin: String = "tunisia",
    val stock: Int = 0,
    val isBio: Boolean = false,
    val isActive: Boolean = true,
    val status: String = "published",
    val discountPercent: Int = 0,
    val searchKeywords: List<String> = emptyList(),
    val sellerId: String = "",
    val sellerName: String = "",
    val sellerAvatarUrl: String = "",
    val sellerVerifiedAt: Any? = null,
    val sellerMemberSince: Any? = null,
    val sellerTotalSold: Int = 0,
    val sellerRating: Double = 0.0,
    val sellerRatingCount: Int = 0,
    val createdAt: Any? = null,
    val updatedAt: Any? = null
) {
    val updatedAtMillis: Long get() = when (val res = updatedAt) {
        is Long -> res
        is com.google.firebase.Timestamp -> res.toDate().time
        is Map<*, *> -> (res["seconds"] as? Number)?.toLong()?.let { it * 1000 } ?: 0L // handle potential map from local cache
        else -> 0L
    }

    val createdAtMillis: Long get() = when (val res = createdAt) {
        is Long -> res
        is com.google.firebase.Timestamp -> res.toDate().time
        is Map<*, *> -> (res["seconds"] as? Number)?.toLong()?.let { it * 1000 } ?: 0L
        else -> 0L
    }
    val effectivePrice: Double get() = fromMinorUnits(priceMinor)
    val discountPercentClamped: Int get() = discountPercent.coerceIn(0, 90)
    val hasDiscount: Boolean get() = discountPercentClamped > 0
    val discountedPrice: Double
        get() = if (hasDiscount) effectivePrice * (1.0 - discountPercentClamped / 100.0) else effectivePrice
    val unitPrice: Double get() = discountedPrice
    val tag: String get() = tags.firstOrNull().orEmpty()
    val isDisplayReady: Boolean
        get() = title.trim().length >= 3 &&
            subtitle.trim().length >= 3 &&
            description.trim().length >= 12

    val searchableText: String by lazy {
        buildString {
            append(title).append(' ')
            append(subtitle).append(' ')
            append(description).append(' ')
            append(tags.joinToString(" ")).append(' ')
            append(category).append(' ')
            append(origin).append(' ')
            append(sellerName).append(' ')
            append(if (isBio) "bio naturel " else "")
        }.lowercase(Locale.getDefault())
    }

    val sellerDisplayName: String
        get() = sellerName.trim().ifBlank { "Fati" }
}

@DrawableRes
fun Product.catalogFallbackImageRes(): Int =
    imageRes.takeIf { it != 0 } ?: ProductCatalog.imageForCategory(category)

fun Product.mainImageUrl(): String? =
    imageUrls.firstOrNull { it.isNotBlank() } ?: imageUrl?.takeIf { it.isNotBlank() }

fun Product.previewImageUrl(): String? =
    thumbnailUrls.firstOrNull { it.isNotBlank() }
        ?: thumbnailUrl?.takeIf { it.isNotBlank() }
        ?: mainImageUrl()

object ProductCatalog {

    private val seedProducts = emptyList<Product>()

    private val localVisualMap = mapOf(
        "chechia" to Product(id = "chechia", title = "", subtitle = "", price = 0.0, rating = 0.0, reviewsCount = 0, tags = emptyList(), description = "", bullets = emptyList(), imageRes = R.drawable.product1example),
        "bijoux" to Product(id = "bijoux", title = "", subtitle = "", price = 0.0, rating = 0.0, reviewsCount = 0, tags = emptyList(), description = "", bullets = emptyList(), imageRes = R.drawable.product2example),
        "marqoum" to Product(id = "marqoum", title = "", subtitle = "", price = 0.0, rating = 0.0, reviewsCount = 0, tags = emptyList(), description = "", bullets = emptyList(), imageRes = R.drawable.product3example),
        "balgha" to Product(id = "balgha", title = "", subtitle = "", price = 0.0, rating = 0.0, reviewsCount = 0, tags = emptyList(), description = "", bullets = emptyList(), imageRes = R.drawable.product4example)
    )

    @Volatile
    private var cachedProducts: List<Product> = emptyList()

    fun all(includeInactive: Boolean = false): List<Product> {
        val snapshot = cachedProducts
        return if (includeInactive) snapshot else snapshot.filter { it.isActive }
    }

    fun byId(id: String): Product? {
        return cachedProducts.firstOrNull { it.id == id }
    }

    fun orderedFavorites(ids: Set<String>): List<Product> {
        val snapshot = all(includeInactive = true).associateBy { it.id }
        return ids.mapNotNull { snapshot[it] }
    }

    fun featured(ids: List<String>): List<Product> = ids.mapNotNull { byId(it) }.filter { it.isActive }

    @Synchronized
    fun replaceAll(products: List<Product>) {
        val mergedById = linkedMapOf<String, Product>()
        products.forEach { product ->
            mergedById[product.id] = mergeLocalVisuals(product)
        }
        cachedProducts = mergedById.values.toList()
    }

    @Synchronized
    fun upsert(product: Product) {
        val normalized = mergeLocalVisuals(product)
        val next = cachedProducts.toMutableList()
        val index = next.indexOfFirst { it.id == normalized.id }
        if (index >= 0) {
            next[index] = normalized
        } else {
            next.add(0, normalized)
        }
        cachedProducts = next.sortedBy { it.title.lowercase(Locale.getDefault()) }
    }

    @Synchronized
    fun remove(productId: String) {
        cachedProducts = cachedProducts.filterNot { it.id == productId }
    }

    fun createIdFromTitle(title: String): String {
        val normalized = Normalizer.normalize(title.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .replace("[^a-z0-9]+".toRegex(), "_")
            .trim('_')
        return normalized.ifBlank { "product_${System.currentTimeMillis()}" }
    }

    fun imageForCategory(category: String): Int = MarketplaceCategories.imageResFor(category)

    fun legacyImageRes(productId: String): Int? = localVisualMap[productId]?.imageRes

    private fun mergeLocalVisuals(product: Product): Product {
        val finalImageUrl = product.mainImageUrl()
        val finalImageUrls = product.imageUrls
            .filter { it.isNotBlank() }
            .ifEmpty { finalImageUrl?.let(::listOf).orEmpty() }
        val finalThumbnailUrl = product.thumbnailUrl?.takeIf { it.isNotBlank() }
            ?: product.thumbnailUrls.firstOrNull { it.isNotBlank() }
        val finalThumbnailUrls = product.thumbnailUrls
            .filter { it.isNotBlank() }
            .ifEmpty { finalThumbnailUrl?.let(::listOf).orEmpty() }

        return product.copy(
            imageRes = product.imageRes.takeIf { it != 0 } ?: 0,
            imageUrl = finalImageUrl,
            imageUrls = finalImageUrls,
            thumbnailUrl = finalThumbnailUrl,
            thumbnailUrls = finalThumbnailUrls
        )
    }
}
