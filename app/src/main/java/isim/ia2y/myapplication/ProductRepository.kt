package isim.ia2y.myapplication

import androidx.annotation.DrawableRes
import java.text.Normalizer
import java.util.Locale

data class Product(
    val id: String,
    val title: String,
    val subtitle: String,
    val price: Double,
    val rating: Double,
    val reviewsCount: Int,
    val tags: List<String>,
    val description: String,
    val bullets: List<String>,
    @DrawableRes val imageRes: Int,
    val imageUrl: String? = null,
    val category: String = "craft",
    val origin: String = "tunisia",
    val stock: Int = 0,
    val isBio: Boolean = false,
    val isActive: Boolean = true,
    val updatedAt: Long = 0L
) {
    val unitPrice: Double get() = price
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
            append(if (isBio) "bio naturel " else "")
        }.lowercase(Locale.getDefault())
    }
}

object ProductCatalog {

    private val seedProducts = emptyList<Product>()

    private val localVisualMap = mapOf(
        "chechia" to Product("chechia", "", "", 0.0, 0.0, 0, emptyList(), "", emptyList(), R.drawable.product1example),
        "bijoux" to Product("bijoux", "", "", 0.0, 0.0, 0, emptyList(), "", emptyList(), R.drawable.product2example),
        "marqoum" to Product("marqoum", "", "", 0.0, 0.0, 0, emptyList(), "", emptyList(), R.drawable.product3example),
        "balgha" to Product("balgha", "", "", 0.0, 0.0, 0, emptyList(), "", emptyList(), R.drawable.product4example)
    )

    @Volatile
    private var cachedProducts: List<Product> = emptyList()

    fun all(includeInactive: Boolean = false): List<Product> {
        val snapshot = cachedProducts
        return if (includeInactive) snapshot else snapshot.filter { it.isActive && it.isDisplayReady }
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

    fun imageForCategory(category: String): Int = when (category.lowercase(Locale.getDefault())) {
        "food" -> R.drawable.img_explore_epices
        "beauty" -> R.drawable.img_explore_cosmetiques
        "fashion" -> R.drawable.img_explore_vetements
        "decor" -> R.drawable.img_explore_decoration
        else -> R.drawable.img_explore_artisanat
    }

    fun legacyImageRes(productId: String): Int? = localVisualMap[productId]?.imageRes

    private fun mergeLocalVisuals(product: Product): Product {
        // Only use local fallback if there is no remote image URL
        if (!product.imageUrl.isNullOrBlank()) return product

        val key = localVisualMap.keys.firstOrNull {
            product.id.contains(it, ignoreCase = true) || product.title.contains(it, ignoreCase = true)
        }
        val localVisual = if (key != null) localVisualMap[key] else null
        
        return product.copy(
            imageRes = localVisual?.imageRes ?: if (product.imageRes != 0) product.imageRes else imageForCategory(product.category),
            imageUrl = localVisual?.imageUrl ?: product.imageUrl
        )
    }
}
