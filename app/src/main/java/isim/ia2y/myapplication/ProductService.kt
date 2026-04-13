package isim.ia2y.myapplication

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

object ProductService {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val productsRef get() = db.collection(FirestoreCollections.PRODUCTS)



    suspend fun fetchProduct(id: String): Product? {
        val doc = productsRef.document(id).get().await()
        val data = doc.data ?: return null
        return productFromMap(doc.id, data)
    }

    suspend fun fetchProducts(): List<Product> {
        val snapshot = productsRef
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .get()
            .await()
        return snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            productFromMap(doc.id, data)
        }
    }

    fun listenToProducts(
        onUpdate: (List<Product>) -> Unit,
        onError: (Throwable) -> Unit = {}
    ): com.google.firebase.firestore.ListenerRegistration {
        return productsRef
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error)
                    return@addSnapshotListener
                }
                if (snapshot == null) return@addSnapshotListener
                val products = snapshot.documents.mapNotNull { doc ->
                    productFromMap(doc.id, doc.data ?: return@mapNotNull null)
                }
                onUpdate(products)
            }
    }

    suspend fun fetchProductsPaginated(
        pageSize: Long,
        lastDoc: DocumentSnapshot? = null,
        categoryFilter: String? = null
    ): Pair<List<Product>, DocumentSnapshot?> {
        var query: Query = productsRef

        if (!categoryFilter.isNullOrBlank() && categoryFilter != "all") {
            query = query.whereEqualTo("category", categoryFilter)
        }
        
        query = query.orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(pageSize)

        if (lastDoc != null) {
            query = query.startAfter(lastDoc)
        }

        val snapshot = query.get().await()
        val products = snapshot.documents.mapNotNull { doc ->
            productFromMap(doc.id, doc.data ?: return@mapNotNull null)
        }
        val nextDoc = if (snapshot.size() > 0) snapshot.documents[snapshot.size() - 1] else null
        return Pair(products, nextDoc)
    }

    suspend fun saveProduct(product: Product): Product {
        val normalized = product.copy(updatedAt = System.currentTimeMillis())
        val existing = fetchProduct(normalized.id)
        runCatching { BackendFunctionsService.upsertProduct(normalized) }
            .recoverCatching { error ->
                if (TrustedMutationFallbackPolicy.allowDirectWriteFallback(error) && canWriteProductsDirectly()) {
                    directUpsertProduct(normalized, existing)
                } else {
                    throw error
                }
            }
            .getOrThrow()
        val refreshed = fetchProduct(normalized.id) ?: normalized
        ProductCatalog.upsert(refreshed)
        CatalogSyncManager.publishCachedSnapshot()
        return refreshed
    }

    suspend fun deleteProduct(productId: String) {
        runCatching { BackendFunctionsService.deleteProduct(productId) }
            .recoverCatching { error ->
                if (TrustedMutationFallbackPolicy.allowDirectWriteFallback(error) && canWriteProductsDirectly()) {
                    productsRef.document(productId).delete().await()
                } else {
                    throw error
                }
            }
            .getOrThrow()
        ProductCatalog.remove(productId)
        CatalogSyncManager.publishCachedSnapshot()
    }

    private fun productToMap(product: Product): Map<String, Any?> = mapOf(
        "title" to product.title,
        "subtitle" to product.subtitle,
        "price" to product.price,
        "rating" to product.rating,
        "reviewsCount" to product.reviewsCount,
        "tags" to product.tags,
        "description" to product.description,
        "bullets" to product.bullets,
        "imageUrl" to product.imageUrl,
        "imageUrls" to (product.imageUrls.ifEmpty { if (product.imageUrl != null) listOf(product.imageUrl!!) else emptyList() }),
        "category" to product.category,
        "categoryIds" to product.categoryIds.ifEmpty { listOf(product.category) },
        "origin" to product.origin,
        "stock" to product.stock,
        "isBio" to product.isBio,
        "isActive" to product.isActive,
        "status" to product.status,
        "searchKeywords" to product.searchKeywords.ifEmpty { generateKeywords(product) },
        "createdAt" to product.createdAt,
        "updatedAt" to (product.updatedAt ?: com.google.firebase.Timestamp.now())
    )

    private fun generateKeywords(product: Product): List<String> {
        return product.title.lowercase(java.util.Locale.getDefault()).split(" ").filter { it.length > 2 }
            .plus(product.subtitle.lowercase(java.util.Locale.getDefault()).split(" "))
            .plus(product.category.lowercase(java.util.Locale.getDefault()))
            .distinct()
    }

    private suspend fun canWriteProductsDirectly(): Boolean {
        val uid = FirebaseAuthManager.currentUser?.uid ?: return false
        return AdminSession.isVerified(uid) || UserService.fetchUserRole(uid) == "admin"
    }

    private suspend fun directUpsertProduct(product: Product, existing: Product?) {
        val payload = productToMap(product).toMutableMap()
        payload["id"] = product.id
        payload["createdAt"] = existing?.createdAt ?: FieldValue.serverTimestamp()
        payload["updatedAt"] = FieldValue.serverTimestamp()
        productsRef.document(product.id).set(payload, com.google.firebase.firestore.SetOptions.merge()).await()
    }

    private fun productFromMap(id: String, map: Map<String, Any>): Product {
        val imageUrls = (map["imageUrls"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        val legacyImageUrl = map["imageUrl"] as? String

        return Product(
            id = id,
            title = map["title"] as? String ?: "",
            subtitle = map["subtitle"] as? String ?: "",
            price = (map["price"] as? Number)?.toDouble() ?: 0.0,
            rating = (map["rating"] as? Number)?.toDouble() ?: 0.0,
            reviewsCount = (map["reviewsCount"] as? Number)?.toInt() ?: 0,
            tags = (map["tags"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            description = map["description"] as? String ?: "",
            bullets = (map["bullets"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            imageRes = 0,
            imageUrl = legacyImageUrl,
            imageUrls = imageUrls.ifEmpty { if (legacyImageUrl != null) listOf(legacyImageUrl) else emptyList() },
            category = map["category"] as? String ?: "craft",
            categoryIds = (map["categoryIds"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            origin = map["origin"] as? String ?: "tunisia",
            stock = (map["stock"] as? Number)?.toInt() ?: 0,
            isBio = map["isBio"] as? Boolean ?: false,
            isActive = map["isActive"] as? Boolean ?: true,
            status = map["status"] as? String ?: "published",
            searchKeywords = (map["searchKeywords"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            createdAt = map["createdAt"],
            updatedAt = map["updatedAt"]
        )
    }
}
