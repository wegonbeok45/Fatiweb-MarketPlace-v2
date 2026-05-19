package isim.ia2y.myapplication

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

data class ProductReviewPage(
    val reviews: List<ProductReview>,
    val nextCursor: DocumentSnapshot?,
    val reachedEnd: Boolean
)

object ReviewService {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    private fun reviewsRef(productId: String) = 
        db.collection(FirestoreCollections.PRODUCTS)
            .document(productId)
            .collection("reviews")

    suspend fun fetchReviews(productId: String): List<ProductReview> {
        return fetchReviewsPage(productId, pageSize = 20).reviews
    }

    suspend fun fetchReviewsPage(
        productId: String,
        pageSize: Long = 10,
        cursor: DocumentSnapshot? = null,
        source: Source = Source.DEFAULT
    ): ProductReviewPage {
        val snapshot = reviewsRef(productId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(pageSize.coerceIn(1, 50))
            .let { if (cursor == null) it else it.startAfter(cursor) }
            .get(source)
            .await()
        val reviews = snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            ProductReview.fromMap(doc.id, data)
        }
        return ProductReviewPage(
            reviews = reviews,
            nextCursor = snapshot.documents.lastOrNull(),
            reachedEnd = snapshot.size().toLong() < pageSize
        )
    }

    suspend fun addReview(productId: String, review: ProductReview): ProductReview {
        return BackendFunctionsService.submitReview(productId, review)
    }
}
