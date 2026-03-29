package isim.ia2y.myapplication

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

object OrderService {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private fun ordersRef(uid: String) = db.collection(FirestoreCollections.USERS).document(uid).collection(FirestoreCollections.ORDERS)
    private val productsRef = db.collection(FirestoreCollections.PRODUCTS)

    suspend fun saveOrder(uid: String, order: AppOrder): AppOrder {
        val now = System.currentTimeMillis()
        val orderRef = ordersRef(uid).document()
        val orderWithId = order.copy(
            id = orderRef.id,
            createdAt = if (order.createdAt > 0L) order.createdAt else now,
            updatedAt = now,
            statusTimeline = order.statusTimeline.ifEmpty { listOf(OrderStatusEntry(order.status, now)) }
        )

        db.runTransaction { transaction ->
            val productRefs = order.items.keys.map { productsRef.document(it) }
            val productSnapshots = productRefs.associateWith { transaction.get(it) }

            for ((productId, quantity) in order.items) {
                val ref = productsRef.document(productId)
                val snapshot = productSnapshots[ref]
                val currentStock = snapshot?.getLong("stock")?.toInt() ?: 0
                val title = snapshot?.getString("title") ?: productId

                if (quantity > currentStock) {
                    throw FirebaseFirestoreException(
                        "Stock insuffisant pour $title ($currentStock disponible).",
                        FirebaseFirestoreException.Code.ABORTED
                    )
                }
                transaction.update(ref, "stock", currentStock - quantity)
            }
            transaction.set(orderRef, orderWithId.toMap())
        }.await()

        return orderWithId
    }

    suspend fun fetchOrders(uid: String): List<AppOrder> {
        val snapshot = ordersRef(uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .await()
        return snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            AppOrder.fromMap(data)
        }
    }

    suspend fun fetchOrder(uid: String, orderId: String): AppOrder? {
        val doc = ordersRef(uid).document(orderId).get().await()
        val data = doc.data ?: return null
        return AppOrder.fromMap(data)
    }

    suspend fun updateOrderStatus(uid: String, orderId: String, newStatus: String) {
        val ref = ordersRef(uid).document(orderId)
        val current = ref.get().await().data?.let { AppOrder.fromMap(it) }
            ?: throw IllegalStateException("Commande introuvable.")
        val updated = current.withStatus(newStatus)
        ref.set(
            mapOf(
                "status" to updated.status,
                "updatedAt" to updated.updatedAt,
                "statusTimeline" to updated.statusTimeline.map { it.toMap() }
            ),
            SetOptions.merge()
        ).await()
    }
}
