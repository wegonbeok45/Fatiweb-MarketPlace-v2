package isim.ia2y.myapplication

import com.google.firebase.firestore.AggregateField
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * Legacy central Firestore access object, now delegating to specialized services.
 */
object FirestoreService {
    // Data Classes
    data class UserProfile(
        val uid: String = "",
        val name: String = "",
        val email: String = "",
        val role: String = "client",
        val avatarUrl: String? = null,
        val createdAt: Long = 0L,
        val updatedAt: Long = 0L
    )

    data class ClientInfo(
        val uid: String = "",
        val name: String = "",
        val email: String = "",
        val orderCount: Int = 0,
        val createdAt: Long = 0L
    )

    data class AdminStats(
        val totalOrders: Int = 0,
        val totalRevenue: Double = 0.0,
        val totalClients: Int = 0,
        val totalProducts: Int = 0,
        val activeProducts: Int = 0,
        val lowStockProducts: Int = 0
    )

    data class CommerceConfig(
        val standardShippingFee: Double = CartStore.LIVRAISON_FEE,
        val expressShippingFee: Double = 12.5,
        val updatedAt: Long = 0L
    )

    data class InAppNotification(
        val id: String = "",
        val title: String = "",
        val message: String = "",
        val createdAt: Long = 0L,
        val createdBy: String = "",
        val audience: String = "all"
    ) {
        fun toMap(): Map<String, Any> = mapOf(
            "id" to id,
            "title" to title,
            "message" to message,
            "createdAt" to createdAt,
            "createdBy" to createdBy,
            "audience" to audience
        )
    }

    // Delegation to specialized services
    suspend fun fetchProducts() = ProductService.fetchProducts()
    suspend fun saveProduct(product: Product) = ProductService.saveProduct(product)
    suspend fun deleteProduct(productId: String) = ProductService.deleteProduct(productId)

    suspend fun saveOrder(uid: String, order: AppOrder) = OrderService.saveOrder(uid, order)
    suspend fun fetchOrders(uid: String) = OrderService.fetchOrders(uid)
    suspend fun fetchOrder(uid: String, orderId: String) = OrderService.fetchOrder(uid, orderId)
    suspend fun fetchAllOrders() = AdminService.fetchAllOrders()
    suspend fun fetchAllClients() = AdminService.fetchAllClients()
    suspend fun updateOrderStatus(uid: String, orderId: String, newStatus: String) = OrderService.updateOrderStatus(uid, orderId, newStatus)

    suspend fun saveUserProfile(uid: String, name: String, email: String, avatarUrl: String? = null) = 
        UserService.saveUserProfile(uid, name, email, avatarUrl)
    suspend fun updateUserProfileName(uid: String, name: String) = UserService.updateUserProfileName(uid, name)
    suspend fun fetchUserProfile(uid: String) = UserService.fetchUserProfile(uid)
    suspend fun fetchUserRole(uid: String) = UserService.fetchUserRole(uid)
    suspend fun isCurrentUserAdmin() = UserService.isCurrentUserAdmin()
    fun clearCache() = UserService.clearCache()
    suspend fun fetchAddresses(uid: String) = UserService.fetchAddresses(uid)
    suspend fun replaceAddresses(uid: String, addresses: List<DeliveryAddress>) =
        UserService.replaceAddresses(uid, addresses)
    suspend fun fetchFavorites(uid: String) = UserService.fetchFavorites(uid)
    suspend fun replaceFavorites(uid: String, favorites: Set<String>) =
        UserService.replaceFavorites(uid, favorites)

    suspend fun fetchCart(uid: String) = CartFirestoreService.fetchCart(uid)
    suspend fun replaceCart(uid: String, cart: Map<String, Int>) = CartFirestoreService.replaceCart(uid, cart)

    suspend fun fetchAdminStats() = AdminService.fetchAdminStats()
    suspend fun fetchCommerceConfig() = ConfigService.fetchCommerceConfig()
    suspend fun saveCommerceConfig(config: CommerceConfig) = ConfigService.saveCommerceConfig(config)

    suspend fun fetchNotifications() = NotificationService.fetchNotifications()
    suspend fun fetchInAppNotifications() = NotificationService.fetchInAppNotifications()
    suspend fun createInAppNotification(title: String, message: String, createdBy: String) =
        NotificationService.createInAppNotification(title, message, createdBy)
    suspend fun fetchNotificationReadIds(uid: String) = NotificationService.fetchNotificationReadIds(uid)
    suspend fun replaceNotificationReadIds(uid: String, readIds: Set<String>) =
        NotificationService.replaceNotificationReadIds(uid, readIds)
    suspend fun markNotificationRead(uid: String, notificationId: String) = NotificationService.markNotificationRead(uid, notificationId)
    suspend fun isNotificationRead(uid: String, notificationId: String) = NotificationService.isNotificationRead(uid, notificationId)
}
