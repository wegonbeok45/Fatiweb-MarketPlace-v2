package isim.ia2y.myapplication

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

object ProductService {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val productsRef = db.collection(FirestoreCollections.PRODUCTS)



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
        productsRef.document(normalized.id)
            .set(productToMap(normalized), SetOptions.merge())
            .await()
        ProductCatalog.upsert(normalized)
        return normalized
    }

    suspend fun deleteProduct(productId: String) {
        productsRef.document(productId).delete().await()
        ProductCatalog.remove(productId)
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
        "imageRes" to product.imageRes,
        "imageUrl" to product.imageUrl,
        "category" to product.category,
        "origin" to product.origin,
        "stock" to product.stock,
        "isBio" to product.isBio,
        "isActive" to product.isActive,
        "updatedAt" to product.updatedAt
    )

    private fun productFromMap(id: String, map: Map<String, Any>): Product = Product(
        id = id,
        title = map["title"] as? String ?: "",
        subtitle = map["subtitle"] as? String ?: "",
        price = (map["price"] as? Number)?.toDouble() ?: 0.0,
        rating = (map["rating"] as? Number)?.toDouble() ?: 0.0,
        reviewsCount = (map["reviewsCount"] as? Number)?.toInt() ?: 0,
        tags = (map["tags"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
        description = map["description"] as? String ?: "",
        bullets = (map["bullets"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
        imageRes = (map["imageRes"] as? Number)?.toInt() ?: 0,
        imageUrl = map["imageUrl"] as? String,
        category = map["category"] as? String ?: "craft",
        origin = map["origin"] as? String ?: "tunisia",
        stock = (map["stock"] as? Number)?.toInt() ?: 0,
        isBio = map["isBio"] as? Boolean ?: false,
        isActive = map["isActive"] as? Boolean ?: true,
        updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L
    )
}
