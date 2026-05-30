package isim.ia2y.myapplication

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

object OrderService {
    private const val ORDER_HISTORY_LIMIT = 30L
    private val allowedOrderStatuses = OrderStatuses.supported.toSet()

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val ordersRef get() = db.collection(FirestoreCollections.ORDERS)
    private val usersRef get() = db.collection(FirestoreCollections.USERS)

    private fun userOrdersRef(uid: String) = usersRef.document(uid).collection(FirestoreCollections.ORDERS)

    suspend fun saveOrder(uid: String, order: AppOrder, deliveryType: String = "standard"): AppOrder {
        check(uid == order.uid) { "Order owner mismatch." }
        return BackendFunctionsService.createOrder(order, deliveryType)
    }

    suspend fun fetchOrders(
        uid: String,
        limit: Long = ORDER_HISTORY_LIMIT,
        source: Source = Source.DEFAULT
    ): List<AppOrder> {
        val safeLimit = limit.coerceIn(1L, 50L)
        val snapshot = ordersRef
            .whereEqualTo("uid", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(safeLimit)
            .get(source)
            .await()
        FirebaseCostTracker.read("OrderService.fetchOrders", "orders", snapshot.size(), source.name)

        val newOrders = snapshot.documents.mapNotNull { doc ->
            doc.data?.let { AppOrder.fromMap(it).copy(id = doc.id) }
        }

        if (newOrders.isNotEmpty()) return newOrders

        val legacySnapshot = userOrdersRef(uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(safeLimit)
            .get(source)
            .await()
        FirebaseCostTracker.read("OrderService.fetchOrders", "users/$uid/orders", legacySnapshot.size(), source.name)

        return legacySnapshot.documents.mapNotNull { doc ->
            doc.data?.let { AppOrder.fromMap(it).copy(id = doc.id) }
        }
    }

    suspend fun fetchOrder(uid: String, orderId: String): AppOrder? {
        val cachedDoc = runCatching { ordersRef.document(orderId).get(Source.CACHE).await() }.getOrNull()
        if (cachedDoc?.exists() == true) {
            FirebaseCostTracker.read("OrderService.fetchOrder", "orders/$orderId", 1, Source.CACHE.name)
            return cachedDoc.data?.let { AppOrder.fromMap(it).copy(id = cachedDoc.id) }
        }
        val doc = ordersRef.document(orderId).get(Source.SERVER).await()
        FirebaseCostTracker.read("OrderService.fetchOrder", "orders/$orderId", if (doc.exists()) 1 else 0, Source.SERVER.name)
        if (doc.exists()) return doc.data?.let { AppOrder.fromMap(it).copy(id = doc.id) }

        if (uid.isBlank()) return null
        val legacyDoc = runCatching {
            userOrdersRef(uid).document(orderId).get(Source.SERVER).await()
        }.getOrNull() ?: return null
        FirebaseCostTracker.read("OrderService.fetchOrder", "users/$uid/orders/$orderId", if (legacyDoc.exists()) 1 else 0, Source.SERVER.name)
        return legacyDoc.data?.let { AppOrder.fromMap(it).copy(id = legacyDoc.id) }
    }

    suspend fun updateOrderStatus(uid: String, orderId: String, newStatus: String): AppOrder {
        check(uid.isNotBlank()) { "uid is required." }
        val normalizedStatus = requireKnownOrderStatus(newStatus)
        return BackendFunctionsService.updateOrderStatus(orderId, normalizedStatus)
    }

    private fun requireKnownOrderStatus(status: String): String {
        val normalized = OrderStatuses.normalize(status)
        check(normalized in allowedOrderStatuses) { "Unsupported order status." }
        return normalized
    }

}
