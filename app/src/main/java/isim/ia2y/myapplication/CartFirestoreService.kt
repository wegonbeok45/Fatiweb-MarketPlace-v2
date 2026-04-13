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
        if (!doc.exists()) {
            // Fallback to legacy path for migration
            val legacyDoc = db.collection("carts").document(uid).get().await()
            val legacyItems = legacyDoc.data?.get("items") as? Map<String, Long>
            return legacyItems?.mapValues { it.value.toInt() } ?: emptyMap()
        }
        val itemsList = doc.data?.get("items") as? List<Map<String, Any>>
        return itemsList?.associate { 
            (it["productId"] as String) to (it["quantity"] as Number).toInt()
        } ?: emptyMap()
    }

    suspend fun replaceCart(uid: String, cart: Map<String, Int>) {
        val itemsToSave = cart.filter { it.value > 0 }.map { (productId, qty) ->
            val product = ProductCatalog.byId(productId)
            mapOf(
                "productId" to productId,
                "nameSnapshot" to (product?.title ?: ""),
                "thumbnailUrl" to (product?.imageUrls?.firstOrNull() ?: product?.imageUrl ?: ""),
                "priceSnapshot" to (product?.price ?: 0.0),
                "quantity" to qty,
                "addedAt" to com.google.firebase.Timestamp.now()
            )
        }
        cartRef(uid).set(mapOf(
            "items" to itemsToSave,
            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
        )).await()
    }
}
