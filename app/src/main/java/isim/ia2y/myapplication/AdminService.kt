package isim.ia2y.myapplication

import com.google.firebase.firestore.AggregateField
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object AdminService {
    data class OrderStatusSummary(
        val total: Int = 0,
        val pending: Int = 0,
        val delivered: Int = 0
    )

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val productsRef = db.collection("products")

    suspend fun fetchAdminStats(): FirestoreService.AdminStats {
        return runCatching { fetchAdminStatsAggregated() }
            .getOrElse { fetchAdminStatsFromDocuments() }
    }

    private suspend fun fetchAdminStatsAggregated(): FirestoreService.AdminStats {
        val ordersAgg = db.collection(FirestoreCollections.ORDERS)
            .aggregate(AggregateField.count(), AggregateField.sum("total"))
            .get(AggregateSource.SERVER)
            .await()

        val totalOrders = ordersAgg.count.toInt()
        val totalRevenue = (ordersAgg.get(AggregateField.sum("total")) as? Number)?.toDouble() ?: 0.0

        val clientsAgg = db.collection("users")
            .whereEqualTo("role", "client")
            .aggregate(AggregateField.count())
            .get(AggregateSource.SERVER)
            .await()
        val totalClients = clientsAgg.count.toInt()

        val productsAgg = productsRef
            .aggregate(AggregateField.count())
            .get(AggregateSource.SERVER)
            .await()
        val totalProducts = productsAgg.count.toInt()

        val activeProductsAgg = productsRef.whereEqualTo("isActive", true)
            .aggregate(AggregateField.count())
            .get(AggregateSource.SERVER)
            .await()
        val activeProducts = activeProductsAgg.count.toInt()

        val lowStockProductsAgg = productsRef.whereLessThan("stock", 5)
            .aggregate(AggregateField.count())
            .get(AggregateSource.SERVER)
            .await()
        val lowStockProducts = lowStockProductsAgg.count.toInt()

        return FirestoreService.AdminStats(
            totalOrders = totalOrders,
            totalRevenue = totalRevenue,
            totalClients = totalClients,
            totalProducts = totalProducts,
            activeProducts = activeProducts,
            lowStockProducts = lowStockProducts
        )
    }

    private suspend fun fetchAdminStatsFromDocuments(): FirestoreService.AdminStats {
        val ordersSnapshot = db.collection(FirestoreCollections.ORDERS)
            .get()
            .await()
        val orders = ordersSnapshot.documents.mapNotNull { doc ->
            doc.data?.let(AppOrder::fromMap)
        }
        val usersSnapshot = db.collection(FirestoreCollections.USERS)
            .get()
            .await()
        val productsSnapshot = productsRef
            .get()
            .await()

        val totalClients = usersSnapshot.documents.count { doc ->
            (doc.getString("role") ?: "client") == "client"
        }
        val totalProducts = productsSnapshot.size()
        val activeProducts = productsSnapshot.documents.count { doc ->
            doc.getBoolean("isActive") != false
        }
        val lowStockProducts = productsSnapshot.documents.count { doc ->
            val stock = (doc.getLong("stock")
                ?: (doc.getDouble("stock")?.toLong())
                ?: 0L)
            stock < 5L
        }

        return FirestoreService.AdminStats(
            totalOrders = orders.size,
            totalRevenue = orders.sumOf { it.total },
            totalClients = totalClients,
            totalProducts = totalProducts,
            activeProducts = activeProducts,
            lowStockProducts = lowStockProducts
        )
    }

    suspend fun fetchAllOrders(): List<Pair<String, AppOrder>> {
        val ordersSnapshot = db.collection(FirestoreCollections.ORDERS)
            .limit(200)
            .get()
            .await()
        return ordersSnapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            val uid = data["uid"] as? String ?: return@mapNotNull null
            uid to AppOrder.fromMap(data)
        }.sortedByDescending { it.second.createdAtMillis }
    }

    suspend fun fetchAllClients(): List<FirestoreService.ClientInfo> {
        val usersSnapshot = db.collection("users")
            .limit(100)
            .get()
            .await()
        val ordersSnapshot = db.collection(FirestoreCollections.ORDERS)
            .limit(500)
            .get()
            .await()

        val orderCounts = mutableMapOf<String, Int>()
        ordersSnapshot.documents.forEach { doc ->
            val uid = doc.getString("uid") ?: return@forEach
            orderCounts[uid] = (orderCounts[uid] ?: 0) + 1
        }

        return usersSnapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            if ((data["role"] as? String) == "admin") return@mapNotNull null
            val uid = doc.id
            FirestoreService.ClientInfo(
                uid = uid,
                name = data["name"] as? String ?: "Inconnu",
                email = data["email"] as? String ?: "",
                orderCount = orderCounts[uid] ?: 0,
                createdAt = (data["createdAt"] as? Number)?.toLong() ?: 0L
            )
        }.sortedByDescending { it.createdAt }
    }

    suspend fun fetchOrdersPage(
        pageSize: Int,
        lastDoc: com.google.firebase.firestore.DocumentSnapshot? = null
    ): com.google.firebase.firestore.QuerySnapshot {
        val query = db.collection(FirestoreCollections.ORDERS)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(pageSize.toLong())
        
        return if (lastDoc != null) {
            query.startAfter(lastDoc).get().await()!!
        } else {
            query.get().await()!!
        }
    }

    suspend fun fetchOrderStatusSummary(): OrderStatusSummary {
        return runCatching {
            val totalAgg = db.collection(FirestoreCollections.ORDERS)
                .aggregate(AggregateField.count())
                .get(AggregateSource.SERVER)
                .await()
            val pendingAgg = db.collection(FirestoreCollections.ORDERS)
                .whereEqualTo("status", "pending")
                .aggregate(AggregateField.count())
                .get(AggregateSource.SERVER)
                .await()
            val deliveredAgg = db.collection(FirestoreCollections.ORDERS)
                .whereEqualTo("status", "delivered")
                .aggregate(AggregateField.count())
                .get(AggregateSource.SERVER)
                .await()

            OrderStatusSummary(
                total = totalAgg.count.toInt(),
                pending = pendingAgg.count.toInt(),
                delivered = deliveredAgg.count.toInt()
            )
        }.getOrElse {
            val snapshot = db.collection(FirestoreCollections.ORDERS)
                .get()
                .await()
            OrderStatusSummary(
                total = snapshot.size(),
                pending = snapshot.documents.count { it.getString("status") == "pending" },
                delivered = snapshot.documents.count { it.getString("status") == "delivered" }
            )
        }
    }

    suspend fun fetchClientsPage(
        pageSize: Int,
        lastDoc: com.google.firebase.firestore.DocumentSnapshot? = null
    ): com.google.firebase.firestore.QuerySnapshot {
        val query = db.collection(FirestoreCollections.USERS)
            .whereEqualTo("role", "client")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(pageSize.toLong())
            
        return if (lastDoc != null) {
            query.startAfter(lastDoc).get().await()!!
        } else {
            query.get().await()!!
        }
    }
}
