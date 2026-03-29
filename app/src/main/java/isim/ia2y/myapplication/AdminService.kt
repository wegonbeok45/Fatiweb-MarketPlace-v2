package isim.ia2y.myapplication

import com.google.firebase.firestore.AggregateField
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

object AdminService {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val productsRef = db.collection("products")

    suspend fun fetchAdminStats(): FirestoreService.AdminStats {
        return runCatching { fetchAdminStatsAggregated() }
            .getOrElse { fetchAdminStatsFromDocuments() }
    }

    private suspend fun fetchAdminStatsAggregated(): FirestoreService.AdminStats {
        val ordersAgg = db.collectionGroup("orders")
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
        val ordersSnapshot = db.collectionGroup(FirestoreCollections.ORDERS)
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
        val ordersSnapshot = db.collectionGroup("orders")
            .limit(200)
            .get()
            .await()
        return ordersSnapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            val uid = doc.reference.parent.parent?.id ?: return@mapNotNull null
            uid to AppOrder.fromMap(data)
        }.sortedByDescending { it.second.createdAt }
    }

    suspend fun fetchAllClients(): List<FirestoreService.ClientInfo> {
        val usersSnapshot = db.collection("users")
            .limit(100)
            .get()
            .await()
        val ordersSnapshot = db.collectionGroup("orders")
            .limit(500)
            .get()
            .await()

        val orderCounts = mutableMapOf<String, Int>()
        ordersSnapshot.documents.forEach { doc ->
            val uid = doc.reference.parent.parent?.id ?: return@forEach
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
}
