package isim.ia2y.myapplication

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

/**
 * Admin-side product management.
 *
 * Unlike [ProductService.fetchProductsPaginated], this service queries the
 * `products` collection without the public-visibility filters (isActive/status),
 * so admins can see ALL products regardless of their lifecycle state.
 *
 * Writes use [SetOptions.merge] to stamp only the moderation fields.
 */
object AdminProductService {

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private fun productsRef() = db.collection(FirestoreCollections.PRODUCTS)

    private const val PAGE_SIZE = 30L

    // ===== Reads =====

    /**
     * Fetch a page of products for admin review.
     *
     * @param approvalFilter  if non-null, restricts to that wire value.
     * @param lastDoc         pagination cursor from the previous page.
     */
    suspend fun fetchAdminProductsPage(
        approvalFilter: ProductApprovalStatus? = null,
        lastDoc: DocumentSnapshot? = null,
        source: Source = Source.DEFAULT,
    ): Pair<List<Product>, DocumentSnapshot?> {
        var query: Query = productsRef()

        if (approvalFilter != null) {
            query = query.whereEqualTo(ProductFields.APPROVAL_STATUS, approvalFilter.wireValue)
        }

        query = query
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(PAGE_SIZE)

        if (lastDoc != null) {
            query = query.startAfter(lastDoc)
        }

        val snapshot = query.get(source).await()
        FirebaseCostTracker.read("AdminProductService.fetchAdminProductsPage", "products", snapshot.size(), source.name)
        val products = snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            productFromMap(doc.id, data)
        }
        val nextCursor = snapshot.documents.lastOrNull()
            ?.takeIf { snapshot.documents.size.toLong() == PAGE_SIZE }

        return Pair(products, nextCursor)
    }

    /**
     * Count products by approval status — used by the admin home pending widget.
     */
    suspend fun countByApprovalStatus(status: ProductApprovalStatus): Int =
        runCatching {
            productsRef()
                .whereEqualTo(ProductFields.APPROVAL_STATUS, status.wireValue)
                .count()
                .get(com.google.firebase.firestore.AggregateSource.SERVER)
                .await()
                .also {
                    FirebaseCostTracker.read("AdminProductService.countByApprovalStatus", "products aggregate", 1, "aggregate")
                }
                .count.toInt()
        }.getOrDefault(0)

    // ===== Moderation writes =====

    suspend fun approve(productId: String) {
        val adminUid = FirebaseAuthManager.currentUser?.uid ?: ""
        productsRef().document(productId).set(
            mapOf(
                ProductFields.APPROVAL_STATUS to ProductApprovalStatus.APPROVED.wireValue,
                ProductFields.APPROVAL_REVIEWED_AT to FieldValue.serverTimestamp(),
                ProductFields.APPROVAL_REVIEWED_BY to adminUid,
                ProductFields.APPROVAL_REJECTION_REASON to "",
                "isActive" to true,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
        FirebaseCostTracker.write("AdminProductService.approve", "products/$productId")
    }

    suspend fun reject(productId: String, reason: String = "") {
        val adminUid = FirebaseAuthManager.currentUser?.uid ?: ""
        productsRef().document(productId).set(
            mapOf(
                ProductFields.APPROVAL_STATUS to ProductApprovalStatus.REJECTED.wireValue,
                ProductFields.APPROVAL_REVIEWED_AT to FieldValue.serverTimestamp(),
                ProductFields.APPROVAL_REVIEWED_BY to adminUid,
                ProductFields.APPROVAL_REJECTION_REASON to reason,
                "isActive" to false,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
        FirebaseCostTracker.write("AdminProductService.reject", "products/$productId")
    }

    suspend fun archive(productId: String) {
        productsRef().document(productId).set(
            mapOf(
                ProductFields.APPROVAL_STATUS to ProductApprovalStatus.ARCHIVED.wireValue,
                "isActive" to false,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
        FirebaseCostTracker.write("AdminProductService.archive", "products/$productId")
    }

    // ===== Mapping helper =====

    @Suppress("UNCHECKED_CAST")
    private fun productFromMap(id: String, data: Map<String, Any?>): Product? =
        runCatching {
            ProductService.productFromMap(id, data as Map<String, Any>)
        }.getOrNull()
}
