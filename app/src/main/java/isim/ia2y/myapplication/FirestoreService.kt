package isim.ia2y.myapplication

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

/**
 * Central Firestore access object.
 * All functions are suspend functions — call them from a coroutine (lifecycleScope, viewModelScope, etc.)
 *
 * Firestore schema:
 *   products/{productId}           — product catalog
 *   users/{uid}                    — user profile
 *   users/{uid}/orders/{orderId}   — order history per user
 */
object FirestoreService {

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    // ─── Collection references ────────────────────────────────────────────────

    private val productsRef get() = db.collection("products")
    private fun ordersRef(uid: String) = db.collection("users").document(uid).collection("orders")
    private fun userRef(uid: String) = db.collection("users").document(uid)

    // ─── Products ─────────────────────────────────────────────────────────────

    /**
     * Fetch all products from Firestore.
     * Falls back to the local [ProductCatalog] if Firestore fails or returns no results.
     */
    suspend fun fetchProducts(): List<Product> {
        return try {
            val snapshot = productsRef.get().await()
            val firestoreProducts = snapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                productFromMap(doc.id, data)
            }
            firestoreProducts.ifEmpty { ProductCatalog.all() }
        } catch (e: Exception) {
            // Graceful fallback to local data
            ProductCatalog.all()
        }
    }

    /**
     * Seed the local product catalog to Firestore (one-time migration).
     * Safe to call multiple times — uses set() which overwrites.
     */
    suspend fun seedProducts() {
        try {
            val batch = db.batch()
            ProductCatalog.all().forEach { product ->
                val ref = productsRef.document(product.id)
                batch.set(ref, productToMap(product))
            }
            batch.commit().await()
        } catch (_: Exception) { /* silent fail — not critical */ }
    }

    // ─── Orders ───────────────────────────────────────────────────────────────

    /**
     * Save an order to Firestore under users/{uid}/orders/{orderId}.
     * @return the saved [AppOrder] with its assigned Firestore document ID.
     */
    suspend fun saveOrder(uid: String, order: AppOrder): AppOrder {
        val ref = ordersRef(uid).document()
        val withId = order.copy(id = ref.id)
        ref.set(withId.toMap()).await()
        return withId
    }

    /**
     * Fetch the order history for a given user, sorted newest-first.
     */
    suspend fun fetchOrders(uid: String): List<AppOrder> {
        return try {
            val snapshot = ordersRef(uid)
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .get()
                .await()
            snapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                AppOrder.fromMap(data)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Update the status of an existing order (admin use).
     */
    suspend fun updateOrderStatus(uid: String, orderId: String, newStatus: String) {
        try {
            ordersRef(uid).document(orderId).update("status", newStatus).await()
        } catch (_: Exception) {}
    }

    // ─── Users ────────────────────────────────────────────────────────────────

    /**
     * Save or overwrite a user profile document in Firestore.
     */
    suspend fun saveUserProfile(uid: String, name: String, email: String) {
        try {
            val data = mapOf(
                "uid"       to uid,
                "name"      to name,
                "email"     to email,
                "createdAt" to System.currentTimeMillis()
            )
            userRef(uid).set(data).await()
        } catch (_: Exception) {}
    }

    /**
     * Fetch a user's display name from Firestore.
     */
    suspend fun fetchUserName(uid: String): String? {
        return try {
            val doc = userRef(uid).get().await()
            doc.getString("name")
        } catch (e: Exception) {
            null
        }
    }

    // ─── Admin Stats ──────────────────────────────────────────────────────────

    data class AdminStats(
        val totalOrders: Int = 0,
        val totalRevenue: Double = 0.0,
        val totalClients: Int = 0,
        val totalProducts: Int = 0
    )

    /**
     * Fetch aggregated stats for the admin dashboard.
     * Note: this does a shallow count — suitable for small-to-medium catalogs.
     */
    suspend fun fetchAdminStats(): AdminStats {
        return try {
            val usersSnapshot = db.collection("users").get().await()
            val productsSnapshot = productsRef.get().await()
            var totalOrders = 0
            var totalRevenue = 0.0
            for (userDoc in usersSnapshot.documents) {
                val ordersSnapshot = ordersRef(userDoc.id).get().await()
                totalOrders += ordersSnapshot.size()
                ordersSnapshot.documents.forEach { orderDoc ->
                    totalRevenue += (orderDoc.getDouble("total") ?: 0.0)
                }
            }
            AdminStats(
                totalOrders   = totalOrders,
                totalRevenue  = totalRevenue,
                totalClients  = usersSnapshot.size(),
                totalProducts = productsSnapshot.size()
            )
        } catch (e: Exception) {
            AdminStats()
        }
    }

    // ─── Serialization helpers ────────────────────────────────────────────────

    private fun productToMap(p: Product): Map<String, Any> = mapOf(
        "id"           to p.id,
        "title"        to p.title,
        "subtitle"     to p.subtitle,
        "price"        to p.price,
        "rating"       to p.rating,
        "reviewsCount" to p.reviewsCount,
        "tags"         to p.tags,
        "description"  to p.description,
        "bullets"      to p.bullets
        // imageRes is intentionally omitted — local drawable references only
    )

    @Suppress("UNCHECKED_CAST")
    private fun productFromMap(id: String, data: Map<String, Any>): Product? {
        return try {
            // Look up the local product by id to get the correct imageRes
            val local = ProductCatalog.byId(id)
            Product(
                id           = id,
                title        = data["title"] as? String ?: return null,
                subtitle     = data["subtitle"] as? String ?: "",
                price        = (data["price"] as? Number)?.toDouble() ?: 0.0,
                rating       = (data["rating"] as? Number)?.toDouble() ?: 0.0,
                reviewsCount = (data["reviewsCount"] as? Number)?.toInt() ?: 0,
                tags         = (data["tags"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                description  = data["description"] as? String ?: "",
                bullets      = (data["bullets"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                imageRes     = local?.imageRes ?: R.drawable.product1example
            )
        } catch (e: Exception) {
            null
        }
    }
}
