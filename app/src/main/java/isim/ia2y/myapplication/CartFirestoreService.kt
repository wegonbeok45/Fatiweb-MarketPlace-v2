package isim.ia2y.myapplication

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

object CartFirestoreService {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private fun cartRef(uid: String) = db.collection("users").document(uid).collection("cart").document("active")

    @Suppress("UNCHECKED_CAST")
    suspend fun fetchCart(uid: String): Map<String, Int> {
        val doc = cartRef(uid).get().await()
        FirebaseCostTracker.read("CartFirestoreService.fetchCart", "users/$uid/cart/active", if (doc.exists()) 1 else 0)
        if (!doc.exists()) {
            // Fallback to legacy path for migration
            val legacyDoc = db.collection("carts").document(uid).get().await()
            FirebaseCostTracker.read("CartFirestoreService.fetchCart", "carts/$uid", if (legacyDoc.exists()) 1 else 0)
            val legacyItems = legacyDoc.data?.get("items") as? Map<String, Long>
            return legacyItems?.mapValues { it.value.toInt() } ?: emptyMap()
        }
        val itemsList = doc.data?.get("items") as? List<Map<String, Any>>
        return itemsList?.associate { item ->
            val productId = item["productId"] as String
            val variantId = (item["variantId"] as? String)?.takeIf { it.isNotBlank() }
            CartKey.of(productId, variantId) to (item["quantity"] as Number).toInt()
        } ?: emptyMap()
    }

    suspend fun replaceCart(uid: String, cart: Map<String, Int>) {
        val itemsToSave = cart.filter { it.value > 0 }.map { (key, qty) ->
            val productId = CartKey.productId(key)
            val variantId = CartKey.variantId(key)
            val product = ProductCatalog.byId(productId)
            val variant = product?.variantById(variantId)
            val unitPrice = product?.unitPriceForVariant(variant) ?: 0.0
            mapOf(
                "productId" to productId,
                "variantId" to (variantId ?: ""),
                "selectedColor" to (variant?.colorName ?: ""),
                "selectedSize" to (variant?.size ?: ""),
                "nameSnapshot" to (product?.title ?: ""),
                "thumbnailUrl" to (product?.previewImageUrl() ?: ""),
                "priceSnapshot" to unitPrice,
                "priceSnapshotMinor" to toMinorUnits(unitPrice),
                "quantity" to qty,
                "addedAt" to com.google.firebase.Timestamp.now()
            )
        }
        cartRef(uid).set(mapOf(
            "items" to itemsToSave,
            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )).await()
        FirebaseCostTracker.write("CartFirestoreService.replaceCart", "users/$uid/cart/active")
    }
}
