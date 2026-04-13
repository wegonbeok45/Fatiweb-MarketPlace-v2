package isim.ia2y.myapplication

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await

object ReviewService {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    private fun reviewsRef(productId: String) = 
        db.collection(FirestoreCollections.PRODUCTS)
            .document(productId)
            .collection("reviews")

    suspend fun fetchReviews(productId: String): List<ProductReview> {
        val snapshot = reviewsRef(productId)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .await()
        return snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            ProductReview.fromMap(doc.id, data)
        }
    }

    suspend fun addReview(productId: String, review: ProductReview): ProductReview {
        return BackendFunctionsService.submitReview(productId, review)
    }
}
