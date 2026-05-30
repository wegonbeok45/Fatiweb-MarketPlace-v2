package isim.ia2y.myapplication

import java.text.Normalizer
import java.util.Locale

/** A selectable color option for a product (e.g. "Blue" / "#0000FF"). */
data class ProductColor(
    val name: String,
    val hex: String = ""
) {
    fun toMap(): Map<String, Any?> = mapOf(
        "name" to name,
        "hex" to hex
    )

    companion object {
        fun fromMap(map: Map<*, *>): ProductColor? {
            val name = (map["name"] as? String)?.trim().orEmpty()
            if (name.isEmpty()) return null
            return ProductColor(
                name = name,
                hex = (map["hex"] as? String)?.trim().orEmpty()
            )
        }
    }
}

/**
 * A concrete purchasable combination of a product (e.g. Blue / M with its own stock).
 * Products without variants leave [Product.variants] empty and use the flat [Product.stock].
 */
data class ProductVariant(
    val variantId: String,
    val colorName: String = "",
    val colorHex: String = "",
    val size: String = "",
    val stock: Int = 0,
    val priceOverrideMinor: Long? = null,
    val imageUrl: String? = null,
    val sku: String = "",
    val active: Boolean = true
) {
    val isAvailable: Boolean get() = active && stock > 0

    /** Human-readable label such as "Blue / M", "Blue", or "M". */
    val label: String
        get() = listOf(colorName, size)
            .filter { it.isNotBlank() }
            .joinToString(" / ")

    fun toMap(): Map<String, Any?> = buildMap {
        put("variantId", variantId)
        put("colorName", colorName)
        put("colorHex", colorHex)
        put("size", size)
        put("stock", stock)
        put("priceOverrideMinor", priceOverrideMinor)
        put("imageUrl", imageUrl)
        put("sku", sku)
        put("active", active)
    }

    companion object {
        fun fromMap(map: Map<*, *>): ProductVariant? {
            val colorName = (map["colorName"] as? String)?.trim().orEmpty()
            val size = (map["size"] as? String)?.trim().orEmpty()
            val rawId = (map["variantId"] as? String)?.trim().orEmpty()
            val variantId = rawId.ifEmpty { buildVariantId(colorName, size) }
            if (variantId.isEmpty()) return null
            return ProductVariant(
                variantId = variantId,
                colorName = colorName,
                colorHex = (map["colorHex"] as? String)?.trim().orEmpty(),
                size = size,
                stock = (map["stock"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0,
                priceOverrideMinor = (map["priceOverrideMinor"] as? Number)?.toLong()?.takeIf { it >= 0 },
                imageUrl = (map["imageUrl"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                sku = (map["sku"] as? String)?.trim().orEmpty(),
                active = map["active"] as? Boolean ?: true
            )
        }

        /** Deterministic slug from color + size, e.g. "blue_m" or "eu_42". */
        fun buildVariantId(colorName: String, size: String): String {
            val parts = listOf(colorName, size)
                .map { slug(it) }
                .filter { it.isNotEmpty() }
            return parts.joinToString("_")
        }

        private fun slug(value: String): String {
            val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replace("\\p{Mn}+".toRegex(), "")
                .lowercase(Locale.US)
            return normalized.replace("[^a-z0-9]+".toRegex(), "_").trim('_')
        }
    }
}
