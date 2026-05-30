package isim.ia2y.myapplication

import com.google.firebase.firestore.AggregateField
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import android.util.Log
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.concurrent.TimeUnit

object AdminService {
    data class OrderStatusSummary(
        val total: Int = 0,
        val pending: Int = 0,
        val delivered: Int = 0
    )

    data class SellerProductsSummary(
        val totalProducts: Int = 0,
        val activeProducts: Int = 0,
        val lowStockProducts: Int = 0
    )

    data class SellerOrderRow(
        val uid: String,
        val order: AppOrder,
        val sellerItems: List<OrderItem>
    ) {
        val sellerTotal: Double get() = sellerItems.sumOf { it.priceAtPurchase * it.quantity }
        val itemCount: Int get() = sellerItems.sumOf { it.quantity }
    }

    data class ClientProfileDetails(
        val uid: String = "",
        val name: String = "",
        val email: String = "",
        val phone: String = "",
        val role: String = UserRoles.CLIENT,
        val status: String = "active",
        val avatarUrl: String = "",
        val createdAt: Long = 0L,
        val orderCount: Int = 0
    )

    data class SellerOrderStats(
        val totalOrders: Int = 0,
        val ordersToTrack: Int = 0,
        val deliveredOrders: Int = 0,
        val uniqueClients: Int = 0,
        val totalRevenue: Double = 0.0,
        val totalItems: Int = 0
    )

    data class SellerWorkspace(
        val orders: List<AppOrder> = emptyList(),
        val products: SellerProductsSummary = SellerProductsSummary()
    )

    data class AnalyticsDayPoint(
        val dayLabel: String,
        val orderCount: Int,
        val revenue: Double
    )

    data class AnalyticsStatusCount(
        val status: String,
        val count: Int
    )

    data class AnalyticsRankRow(
        val id: String,
        val name: String,
        val count: Int,
        val revenue: Double,
        val imageUrl: String = ""
    )

    data class AnalyticsSnapshot(
        val totalRevenue: Double,
        val totalOrders: Int,
        val averageOrderValue: Double,
        val deliveredRate: Double,
        val revenuePoints: List<AnalyticsDayPoint>,
        val funnel: List<AnalyticsStatusCount>,
        val topProducts: List<AnalyticsRankRow>,
        val topVendors: List<AnalyticsRankRow>
    )

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val productsRef = db.collection("products")
    private const val TAG = "AdminService"
    private const val ADMIN_CACHE_TTL_MS = 5 * 60_000L
    private const val RECENT_PREVIEW_LIMIT = 30L
    private const val MAX_RECENT_LIMIT = 50L
    private const val MAX_PAGE_SIZE = 50
    private const val MAX_SELLER_ORDER_LIMIT = 300L
    private const val MAX_ANALYTICS_ORDER_LIMIT = 500L
    private var cachedStats: Pair<Long, FirestoreService.AdminStats>? = null
    private var cachedRecentOrders: Pair<Long, List<Pair<String, AppOrder>>>? = null
    private var cachedRecentClients: Pair<Long, List<FirestoreService.ClientInfo>>? = null
    private var cachedAnalyticsSnapshot: Pair<Long, AnalyticsSnapshot>? = null
    private var statsInFlight: Deferred<FirestoreService.AdminStats>? = null
    private var recentOrdersInFlight: Deferred<List<Pair<String, AppOrder>>>? = null
    private var recentClientsInFlight: Deferred<List<FirestoreService.ClientInfo>>? = null

    fun clearAllCaches() {
        cachedStats = null
        cachedRecentOrders = null
        cachedRecentClients = null
        cachedAnalyticsSnapshot = null
        statsInFlight = null
        recentOrdersInFlight = null
        recentClientsInFlight = null
    }

    private suspend fun <T> timed(label: String, block: suspend () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            Log.d(TAG, "$label took ${System.currentTimeMillis() - start}ms")
        }
    }

    private fun <T> Pair<Long, T>?.freshValue(): T? {
        val cached = this ?: return null
        return cached.second.takeIf { System.currentTimeMillis() - cached.first < ADMIN_CACHE_TTL_MS }
    }

    suspend fun fetchAdminStats(): FirestoreService.AdminStats {
        cachedStats.freshValue()?.let { return it }
        statsInFlight?.takeIf { it.isActive }?.let { return it.await() }
        return coroutineScope {
            val deferred = async {
                timed("fetchAdminStats") {
                    fetchAdminStatsAggregated().also { cachedStats = System.currentTimeMillis() to it }
                }
            }
            statsInFlight = deferred
            try {
                deferred.await()
            } finally {
                if (statsInFlight === deferred) statsInFlight = null
            }
        }
    }

    private suspend fun fetchAdminStatsAggregated(): FirestoreService.AdminStats = coroutineScope {
        val revenueField = AggregateField.sum("total")
        val ordersDeferred = async {
            db.collection(FirestoreCollections.ORDERS)
                .aggregate(AggregateField.count(), revenueField)
                .get(AggregateSource.SERVER)
                .await()
        }
        val clientsDeferred = async {
            db.collection("users")
                .whereEqualTo("role", "client")
                .aggregate(AggregateField.count())
                .get(AggregateSource.SERVER)
                .await()
        }
        val productsDeferred = async {
            productsRef
                .aggregate(AggregateField.count())
                .get(AggregateSource.SERVER)
                .await()
        }
        val activeProductsDeferred = async {
            productsRef.whereEqualTo("isActive", true)
                .aggregate(AggregateField.count())
                .get(AggregateSource.SERVER)
                .await()
        }
        val lowStockProductsDeferred = async {
            productsRef.whereLessThan("stock", 5)
                .aggregate(AggregateField.count())
                .get(AggregateSource.SERVER)
                .await()
        }

        val ordersAgg = ordersDeferred.await()
        FirebaseCostTracker.read("AdminService.fetchAdminStats", "orders/products/users aggregate queries", 5, "aggregate")
        val totalOrders = ordersAgg.count.toInt()
        val totalRevenue = (ordersAgg.get(revenueField) as? Number)?.toDouble() ?: 0.0

        FirestoreService.AdminStats(
            totalOrders = totalOrders,
            totalRevenue = totalRevenue,
            totalClients = clientsDeferred.await().count.toInt(),
            totalProducts = productsDeferred.await().count.toInt(),
            activeProducts = activeProductsDeferred.await().count.toInt(),
            lowStockProducts = lowStockProductsDeferred.await().count.toInt()
        )
    }

    suspend fun fetchAllOrders(): List<Pair<String, AppOrder>> {
        Log.w(TAG, "fetchAllOrders is capped to avoid large client-side reads.")
        return fetchRecentOrders(limit = RECENT_PREVIEW_LIMIT)
    }

    suspend fun fetchRecentOrders(limit: Long = 30): List<Pair<String, AppOrder>> {
        val safeLimit = limit.coerceIn(1L, MAX_RECENT_LIMIT)
        if (safeLimit <= RECENT_PREVIEW_LIMIT) {
            cachedRecentOrders.freshValue()?.let { return it }
            recentOrdersInFlight?.takeIf { it.isActive }?.let { return it.await() }
        }
        return coroutineScope {
            val deferred = async {
                timed("fetchRecentOrders($safeLimit)") {
                    fetchRecentOrdersFromFirestore(safeLimit)
                        .also {
                            if (safeLimit <= RECENT_PREVIEW_LIMIT) {
                                cachedRecentOrders = System.currentTimeMillis() to it
                            }
                        }
                }
            }
            if (safeLimit <= RECENT_PREVIEW_LIMIT) recentOrdersInFlight = deferred
            try {
                deferred.await()
            } finally {
                if (recentOrdersInFlight === deferred) recentOrdersInFlight = null
            }
        }
    }

    suspend fun fetchAllClients(): List<FirestoreService.ClientInfo> {
        Log.w(TAG, "fetchAllClients is capped to avoid large client-side reads.")
        return fetchRecentClients(limit = RECENT_PREVIEW_LIMIT)
    }

    suspend fun fetchRecentClients(limit: Long = 30): List<FirestoreService.ClientInfo> {
        val safeLimit = limit.coerceIn(1L, MAX_RECENT_LIMIT)
        if (safeLimit <= RECENT_PREVIEW_LIMIT) {
            cachedRecentClients.freshValue()?.let { return it }
            recentClientsInFlight?.takeIf { it.isActive }?.let { return it.await() }
        }
        return coroutineScope {
            val deferred = async {
                timed("fetchRecentClients($safeLimit)") {
                    fetchRecentClientsFromFirestore(safeLimit)
                        .also {
                            if (safeLimit <= RECENT_PREVIEW_LIMIT) {
                                cachedRecentClients = System.currentTimeMillis() to it
                            }
                        }
                }
            }
            if (safeLimit <= RECENT_PREVIEW_LIMIT) recentClientsInFlight = deferred
            try {
                deferred.await()
            } finally {
                if (recentClientsInFlight === deferred) recentClientsInFlight = null
            }
        }
    }

    private suspend fun fetchRecentOrdersFromFirestore(
        safeLimit: Long,
        source: Source = Source.DEFAULT
    ): List<Pair<String, AppOrder>> {
        val ordersSnapshot = db.collection(FirestoreCollections.ORDERS)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(safeLimit)
            .get(source)
            .await()
        FirebaseCostTracker.read("AdminService.fetchRecentOrders", "orders", ordersSnapshot.size(), source.name)
        return ordersSnapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            val uid = data["uid"] as? String ?: return@mapNotNull null
            uid to AppOrder.fromMap(data).copy(id = doc.id)
        }.sortedByDescending { it.second.createdAtMillis }
    }

    private suspend fun fetchRecentClientsFromFirestore(
        safeLimit: Long,
        source: Source = Source.DEFAULT
    ): List<FirestoreService.ClientInfo> {
        val usersSnapshot = db.collection("users")
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(safeLimit)
            .get(source)
            .await()
        FirebaseCostTracker.read("AdminService.fetchRecentClients", "users", usersSnapshot.size(), source.name)

        return usersSnapshot.documents.mapNotNull(::clientInfoFromDocument)
            .sortedByDescending { it.createdAt }
    }

    suspend fun promoteUserToVendeur(userId: String) {
        runCatching { BackendFunctionsService.promoteUserToVendeur(userId) }
            .recoverCatching { error ->
                if (TrustedMutationFallbackPolicy.allowDirectWriteFallback(error) && canManageUserRolesDirectly()) {
                    promoteUserToVendeurDirectly(userId)
                } else {
                    throw error
                }
            }
            .getOrThrow()

        verifyPromotedUserRole(userId)
        UserService.cacheRole(userId, UserRoles.VENDEUR)
        if (FirebaseAuthManager.currentUser?.uid == userId) {
            FirebaseAuthManager.currentUser?.getIdToken(true)?.await()
        }
        clearAllCaches()
    }

    private suspend fun promoteUserToVendeurDirectly(userId: String) {
        val adminUid = FirebaseAuthManager.currentUser?.uid
            ?: throw IllegalStateException("Authentication is required.")
        db.collection(FirestoreCollections.USERS)
            .document(userId)
            .set(
                mapOf(
                    "role" to UserRoles.VENDEUR,
                    VendorFields.STATUS to VendorStatus.APPROVED.wireValue,
                    VendorFields.APPROVED_AT to FieldValue.serverTimestamp(),
                    VendorFields.SUSPENDED_REASON to "",
                    "sellerAccessGrantedAt" to FieldValue.serverTimestamp(),
                    "sellerAccessGrantedBy" to adminUid,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            .await()
    }

    suspend fun revokeVendeurRole(userId: String) {
        runCatching { BackendFunctionsService.revokeVendeurAccess(userId) }
            .recoverCatching { error ->
                if (TrustedMutationFallbackPolicy.allowDirectWriteFallback(error) && canManageUserRolesDirectly()) {
                    revokeVendeurRoleDirectly(userId)
                } else {
                    throw error
                }
            }
            .getOrThrow()

        verifyRevokedUserRole(userId)
        UserService.cacheRole(userId, UserRoles.CLIENT)
        if (FirebaseAuthManager.currentUser?.uid == userId) {
            FirebaseAuthManager.currentUser?.getIdToken(true)?.await()
        }
        clearAllCaches()
    }

    private suspend fun revokeVendeurRoleDirectly(userId: String) {
        val adminUid = FirebaseAuthManager.currentUser?.uid
            ?: throw IllegalStateException("Authentication is required.")
        db.collection(FirestoreCollections.USERS)
            .document(userId)
            .set(
                mapOf(
                    "role" to UserRoles.CLIENT,
                    VendorFields.STATUS to VendorStatus.REJECTED.wireValue,
                    "sellerAccessRevokedAt" to FieldValue.serverTimestamp(),
                    "sellerAccessRevokedBy" to adminUid,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                com.google.firebase.firestore.SetOptions.merge()
            )
            .await()
    }

    private suspend fun verifyPromotedUserRole(userId: String) {
        val role = db.collection(FirestoreCollections.USERS)
            .document(userId)
            .get(Source.SERVER)
            .await()
            .getString("role")
            ?.trim()
            ?.lowercase()
        if (role != UserRoles.VENDEUR) {
            throw IllegalStateException("Role update did not persist. Expected vendeur but found ${role ?: "missing"}.")
        }
    }

    private suspend fun verifyRevokedUserRole(userId: String) {
        val role = db.collection(FirestoreCollections.USERS)
            .document(userId)
            .get(Source.SERVER)
            .await()
            .getString("role")
            ?.trim()
            ?.lowercase()
        if (role != UserRoles.CLIENT) {
            throw IllegalStateException("Role update did not persist. Expected client but found ${role ?: "missing"}.")
        }
    }

    private suspend fun canManageUserRolesDirectly(): Boolean {
        val uid = FirebaseAuthManager.currentUser?.uid ?: return false
        return AdminSession.isVerified(uid) || UserService.fetchUserRole(uid, forceRefresh = true) == UserRoles.ADMIN
    }

    suspend fun fetchOrdersPage(
        pageSize: Int,
        lastDoc: com.google.firebase.firestore.DocumentSnapshot? = null
    ): com.google.firebase.firestore.QuerySnapshot {
        val safePageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val query = db.collection(FirestoreCollections.ORDERS)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(safePageSize.toLong())
        
        return if (lastDoc != null) {
            query.startAfter(lastDoc).get().await()!!
        } else {
            query.get().await()!!
        }.also { FirebaseCostTracker.read("AdminService.fetchOrdersPage", "orders", it.size(), "default") }
    }

    suspend fun fetchOrderStatusSummary(): OrderStatusSummary {
        val (totalAgg, pendingAgg, deliveredAgg) = coroutineScope {
            val total = async {
                db.collection(FirestoreCollections.ORDERS)
                    .aggregate(AggregateField.count())
                    .get(AggregateSource.SERVER)
                    .await()
            }
            val pending = async {
                db.collection(FirestoreCollections.ORDERS)
                    .whereEqualTo("status", "pending")
                    .aggregate(AggregateField.count())
                    .get(AggregateSource.SERVER)
                    .await()
            }
            val delivered = async {
                db.collection(FirestoreCollections.ORDERS)
                    .whereEqualTo("status", "delivered")
                    .aggregate(AggregateField.count())
                    .get(AggregateSource.SERVER)
                    .await()
            }
            Triple(total.await(), pending.await(), delivered.await())
        }

        return OrderStatusSummary(
            total = totalAgg.count.toInt(),
            pending = pendingAgg.count.toInt(),
            delivered = deliveredAgg.count.toInt()
        )
    }

    suspend fun fetchSellerProductsSummary(sellerId: String): SellerProductsSummary {
        return fetchSellerWorkspace(sellerId).products
    }

    suspend fun fetchSellerOrders(sellerId: String, limit: Long = MAX_SELLER_ORDER_LIMIT): List<SellerOrderRow> {
        return fetchSellerWorkspace(sellerId, limit).orders.toSellerRows(sellerId)
    }

    suspend fun fetchSellerWorkspace(
        sellerId: String,
        limit: Long = MAX_SELLER_ORDER_LIMIT
    ): SellerWorkspace {
        if (sellerId.isBlank()) return SellerWorkspace()
        val safeLimit = limit.coerceIn(1L, MAX_SELLER_ORDER_LIMIT)
        val remote = runCatching {
            BackendFunctionsService.fetchSellerWorkspace(safeLimit)
        }.onFailure { error ->
            Log.w(TAG, "sellerFetchWorkspace failed; falling back to direct Firestore reads", error)
        }.getOrNull()

        if (remote != null) return remote

        val orders = runCatching {
            fetchSellerOrderModelsDirect(sellerId, safeLimit)
        }.onFailure { error ->
            Log.w(TAG, "Direct seller order query failed for sellerId=${sellerId.shortForLog()}", error)
        }.getOrDefault(emptyList())

        val products = runCatching {
            fetchSellerProductsSummaryDirect(sellerId)
        }.onFailure { error ->
            Log.w(TAG, "Direct seller products summary failed for sellerId=${sellerId.shortForLog()}", error)
        }.getOrDefault(SellerProductsSummary())

        return SellerWorkspace(orders, products)
    }

    private suspend fun fetchSellerProductsSummaryDirect(sellerId: String): SellerProductsSummary {
        if (sellerId.isBlank()) return SellerProductsSummary()
        val products = productsRef
            .whereEqualTo("sellerId", sellerId)
            .limit(200)
            .get()
            .await()
            .documents
            .mapNotNull { it.data }
        FirebaseCostTracker.read("AdminService.fetchSellerProductsSummaryDirect", "products", products.size, "default")

        return SellerProductsSummary(
            totalProducts = products.size,
            activeProducts = products.count { it["isActive"] as? Boolean != false },
            lowStockProducts = products.count {
                val stock = (it["stock"] as? Number)?.toInt() ?: 0
                it["isActive"] as? Boolean != false && stock in 1..5
            }
        )
    }

    private suspend fun fetchSellerOrderModelsDirect(
        sellerId: String,
        limit: Long = MAX_SELLER_ORDER_LIMIT
    ): List<AppOrder> {
        if (sellerId.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1L, MAX_SELLER_ORDER_LIMIT)
        val snapshot = db.collection(FirestoreCollections.ORDERS)
            .whereArrayContains("sellerIds", sellerId)
            .limit(safeLimit)
            .get()
            .await()
        FirebaseCostTracker.read("AdminService.fetchSellerOrderModelsDirect", "orders", snapshot.size(), "default")

        return snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            val order = AppOrder.fromMap(data).copy(id = doc.id)
            if (order.items.none { it.sellerId == sellerId }) return@mapNotNull null
            order
        }.sortedByDescending { it.createdAtMillis }
    }

    private fun List<AppOrder>.toSellerRows(sellerId: String): List<SellerOrderRow> {
        return mapNotNull { order ->
            val sellerItems = order.items.filter { it.sellerId == sellerId }
            if (sellerItems.isEmpty()) return@mapNotNull null
            SellerOrderRow(order.uid, order, sellerItems)
        }.sortedByDescending { it.order.createdAtMillis }
    }

    private fun String.shortForLog(): String = takeLast(8).ifBlank { "-" }

    fun sellerOrderStats(rows: List<SellerOrderRow>): SellerOrderStats {
        return SellerOrderStats(
            totalOrders = rows.size,
            ordersToTrack = rows.count { OrderStatuses.normalize(it.order.status) != OrderStatuses.DELIVERED },
            deliveredOrders = rows.count { OrderStatuses.normalize(it.order.status) == OrderStatuses.DELIVERED },
            uniqueClients = rows.map { it.uid }.filter { it.isNotBlank() }.distinct().size,
            totalRevenue = rows.sumOf { it.sellerTotal },
            totalItems = rows.sumOf { it.itemCount }
        )
    }

    suspend fun fetchAnalyticsSnapshot(days: Int = 7): AnalyticsSnapshot {
        cachedAnalyticsSnapshot.freshValue()?.let { return it }
        val safeDays = days.coerceIn(7, 30)
        return coroutineScope {
            val statsDeferred = async { fetchAdminStats() }
            val funnelDeferred = async { fetchAnalyticsStatusCounts() }
            val ordersDeferred = async { fetchAnalyticsOrders(MAX_ANALYTICS_ORDER_LIMIT) }

            val stats = statsDeferred.await()
            val funnel = funnelDeferred.await()
            val orders = ordersDeferred.await()
            val funnelTotal = funnel.sumOf { it.count }.coerceAtLeast(1)
            val deliveredRate = funnel
                .firstOrNull { it.status == OrderStatuses.DELIVERED }
                ?.count
                ?.toDouble()
                ?.div(funnelTotal)
                ?: 0.0

            AnalyticsSnapshot(
                totalRevenue = stats.totalRevenue,
                totalOrders = stats.totalOrders,
                averageOrderValue = if (stats.totalOrders > 0) stats.totalRevenue / stats.totalOrders else 0.0,
                deliveredRate = deliveredRate,
                revenuePoints = buildAnalyticsDayPoints(orders, safeDays),
                funnel = funnel,
                topProducts = buildAnalyticsTopProducts(orders),
                topVendors = buildAnalyticsTopVendors(orders)
            ).also { cachedAnalyticsSnapshot = System.currentTimeMillis() to it }
        }
    }

    private suspend fun fetchAnalyticsStatusCounts(): List<AnalyticsStatusCount> = coroutineScope {
        val statuses = listOf(
            OrderStatuses.PENDING,
            OrderStatuses.CONFIRMED,
            OrderStatuses.PREPARING,
            OrderStatuses.SHIPPED,
            OrderStatuses.DELIVERED
        )
        statuses.map { status ->
            async {
                val count = db.collection(FirestoreCollections.ORDERS)
                    .whereEqualTo("status", status)
                    .aggregate(AggregateField.count())
                    .get(AggregateSource.SERVER)
                    .await()
                    .count
                    .toInt()
                AnalyticsStatusCount(status, count)
            }
        }.map { it.await() }
    }

    private suspend fun fetchAnalyticsOrders(limit: Long): List<AppOrder> {
        val safeLimit = limit.coerceIn(1L, MAX_ANALYTICS_ORDER_LIMIT)
        val snapshot = db.collection(FirestoreCollections.ORDERS)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(safeLimit)
            .get()
            .await()
        FirebaseCostTracker.read("AdminService.fetchAnalyticsOrders", "orders", snapshot.size(), "default")

        return snapshot.documents.mapNotNull { doc ->
            doc.data?.let { AppOrder.fromMap(it).copy(id = doc.id) }
        }.sortedByDescending { it.createdAtMillis }
    }

    private fun buildAnalyticsDayPoints(orders: List<AppOrder>, days: Int): List<AnalyticsDayPoint> {
        val start = startOfDay(System.currentTimeMillis() - TimeUnit.DAYS.toMillis((days - 1).toLong()))
        val windowOrders = orders.filter { it.createdAtMillis >= start }
        val labels = arrayOf("Dim", "Lun", "Mar", "Mer", "Jeu", "Ven", "Sam")
        val cal = Calendar.getInstance().apply {
            timeInMillis = start
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return (0 until days).map {
            val dayStart = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val dayEnd = cal.timeInMillis
            val dayOrders = windowOrders.filter { order -> order.createdAtMillis in dayStart until dayEnd }
            val label = labels[Calendar.getInstance().apply { timeInMillis = dayStart }.get(Calendar.DAY_OF_WEEK) - 1]
            AnalyticsDayPoint(
                dayLabel = label,
                orderCount = dayOrders.size,
                revenue = dayOrders.sumOf { it.total }
            )
        }
    }

    private fun buildAnalyticsTopProducts(orders: List<AppOrder>): List<AnalyticsRankRow> {
        data class Acc(
            var count: Int,
            var revenue: Double,
            val name: String,
            val imageUrl: String
        )
        val acc = linkedMapOf<String, Acc>()
        orders.forEach { order ->
            order.items.forEach { item ->
                val key = item.productId.ifBlank { item.name }
                if (key.isBlank()) return@forEach
                val row = acc.getOrPut(key) {
                    Acc(0, 0.0, item.name.ifBlank { key }, item.thumbnailUrl)
                }
                row.count += item.quantity
                row.revenue += item.priceAtPurchase * item.quantity
            }
        }
        return acc.entries
            .sortedWith(compareByDescending<Map.Entry<String, Acc>> { it.value.count }.thenByDescending { it.value.revenue })
            .take(5)
            .map { (id, row) ->
                AnalyticsRankRow(id, row.name, row.count, row.revenue, row.imageUrl)
            }
    }

    private fun buildAnalyticsTopVendors(orders: List<AppOrder>): List<AnalyticsRankRow> {
        data class Acc(
            val orderIds: MutableSet<String>,
            var revenue: Double,
            val name: String,
            val imageUrl: String
        )
        val acc = linkedMapOf<String, Acc>()
        orders.forEach { order ->
            order.items.forEach { item ->
                val key = item.sellerId
                if (key.isBlank()) return@forEach
                val row = acc.getOrPut(key) {
                    Acc(mutableSetOf(), 0.0, item.sellerName.ifBlank { key.takeLast(8) }, item.sellerAvatarUrl)
                }
                row.orderIds += order.id.ifBlank { order.displayId }
                row.revenue += item.priceAtPurchase * item.quantity
            }
        }
        return acc.entries
            .sortedByDescending { it.value.revenue }
            .take(5)
            .map { (id, row) ->
                AnalyticsRankRow(id, row.name, row.orderIds.size, row.revenue, row.imageUrl)
            }
    }

    private fun startOfDay(millis: Long): Long {
        return Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    suspend fun fetchClientsPage(
        pageSize: Int,
        lastDoc: com.google.firebase.firestore.DocumentSnapshot? = null
    ): com.google.firebase.firestore.QuerySnapshot {
        val safePageSize = pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val query = db.collection(FirestoreCollections.USERS)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(safePageSize.toLong())
            
        return if (lastDoc != null) {
            query.startAfter(lastDoc).get().await()!!
        } else {
            query.get().await()!!
        }.also { FirebaseCostTracker.read("AdminService.fetchClientsPage", "users", it.size(), "default") }
    }

    suspend fun fetchClientProfileDetails(userId: String): ClientProfileDetails? {
        val doc = db.collection(FirestoreCollections.USERS).document(userId).get().await()
        val data = doc.data ?: return null
        val info = clientInfoFromDocument(doc)
        return ClientProfileDetails(
            uid = doc.id,
            name = info?.name ?: data["name"] as? String ?: data["displayName"] as? String ?: "",
            email = info?.email ?: data["email"] as? String ?: "",
            phone = info?.phone ?: listOf(
                data["phone"] as? String,
                data["phoneNumber"] as? String,
                data["telephone"] as? String
            ).firstOrNull { !it.isNullOrBlank() }.orEmpty(),
            role = info?.role ?: data["role"] as? String ?: UserRoles.CLIENT,
            status = data["status"] as? String ?: "active",
            avatarUrl = info?.avatarUrl ?: listOf(
                data["avatarUrl"] as? String,
                data["avatar"] as? String,
                data["photoUrl"] as? String
            ).firstOrNull { !it.isNullOrBlank() }.orEmpty(),
            createdAt = info?.createdAt ?: data["createdAt"].toMillis(),
            orderCount = info?.orderCount ?: (data["orderCount"] as? Number)?.toInt() ?: 0
        )
    }

    suspend fun fetchClientOrders(userId: String, limit: Long = 100L): List<AppOrder> {
        val safeLimit = limit.coerceIn(1L, 150L)
        val snapshot = db.collection(FirestoreCollections.ORDERS)
            .whereEqualTo("uid", userId)
            .orderBy("createdAt", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(safeLimit)
            .get()
            .await()

        return snapshot.documents.mapNotNull { doc ->
            doc.data?.let { AppOrder.fromMap(it).copy(id = doc.id) }
        }.sortedByDescending { it.createdAtMillis }
    }

    fun clientInfoFromDocument(doc: com.google.firebase.firestore.DocumentSnapshot): FirestoreService.ClientInfo? {
        val data = doc.data ?: return null
        if ((data["role"] as? String) == "admin") return null
        return FirestoreService.ClientInfo(
            uid = doc.id,
            name = data["name"] as? String ?: "Inconnu",
            email = data["email"] as? String ?: "",
            phone = listOf(
                data["phone"] as? String,
                data["phoneNumber"] as? String,
                data["telephone"] as? String
            ).firstOrNull { !it.isNullOrBlank() }.orEmpty(),
            role = data["role"] as? String ?: UserRoles.CLIENT,
            avatarUrl = listOf(
                data["avatarUrl"] as? String,
                data["avatar"] as? String,
                data["photoUrl"] as? String
            ).firstOrNull { !it.isNullOrBlank() }.orEmpty(),
            orderCount = (data["orderCount"] as? Number)?.toInt() ?: 0,
            createdAt = data["createdAt"].toMillis()
        )
    }
}

private fun Any?.toMillis(): Long = when (this) {
    is Number -> toLong()
    is com.google.firebase.Timestamp -> toDate().time
    is Map<*, *> -> (this["seconds"] as? Number)?.toLong()?.times(1000L) ?: 0L
    else -> 0L
}
