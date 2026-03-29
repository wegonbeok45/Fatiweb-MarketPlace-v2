package isim.ia2y.myapplication

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

object CartFirestoreService {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private fun cartRef(uid: String) = db.collection(FirestoreCollections.USERS).document(uid).collection(FirestoreCollections.CART)

    suspend fun fetchCart(uid: String): Map<String, Int> {
        val snapshot = cartRef(uid).get().await()
        return snapshot.documents.associate { doc ->
            doc.id to (doc.getLong("quantity")?.toInt() ?: 0)
        }
    }

    suspend fun replaceCart(uid: String, cart: Map<String, Int>) {
        val existing = cartRef(uid).get().await()
        val batch = db.batch()
        existing.documents.forEach { batch.delete(it.reference) }
        cart.forEach { (id, qty) ->
            if (qty > 0) {
                batch.set(cartRef(uid).document(id), mapOf("quantity" to qty))
            }
        }
        batch.commit().await()
    }
}
