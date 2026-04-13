package isim.ia2y.myapplication

import android.util.Log
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale

object OrderService {
    private const val TAG = "OrderService"
    private const val SCHEMA_VERSION = 2
    private val allowedOrderStatuses = setOf("pending", "preparing", "shipped", "delivered")

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val ordersRef = db.collection(FirestoreCollections.ORDERS)
    private val usersRef get() = db.collection(FirestoreCollections.USERS)
    private val productsRef get() = db.collection(FirestoreCollections.PRODUCTS)

    private fun userOrdersRef(uid: String) = usersRef.document(uid).collection(FirestoreCollections.ORDERS)
    private fun userInboxRef(uid: String) = usersRef.document(uid).collection(FirestoreCollections.INBOX)
    private fun userCartRef(uid: String) = usersRef.document(uid).collection(FirestoreCollections.CART).document("active")
    private fun legacyCartRef(uid: String) = db.collection("carts").document(uid)

    suspend fun saveOrder(uid: String, order: AppOrder, deliveryType: String = "standard"): AppOrder {
        check(uid == order.uid) { "Order owner mismatch." }
        return runCatching {
            BackendFunctionsService.createOrder(order, deliveryType)
        }.recoverCatching { error ->
            if (TrustedMutationFallbackPolicy.allowDirectWriteFallback(error) && canCreateOrdersDirectly(uid)) {
                Log.w(TAG, "Falling back to direct order create", error)
                directCreateOrder(uid, order, deliveryType)
            } else {
                throw error
            }
        }.getOrThrow()
    }

    suspend fun fetchOrders(uid: String): List<AppOrder> {
        val snapshot = ordersRef
            .whereEqualTo("uid", uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .await()

        val newOrders = snapshot.documents.mapNotNull { doc ->
            doc.data?.let { AppOrder.fromMap(it) }
        }

        if (newOrders.isNotEmpty()) return newOrders

        val legacySnapshot = userOrdersRef(uid)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .await()

        return legacySnapshot.documents.mapNotNull { doc ->
            doc.data?.let { AppOrder.fromMap(it) }
        }
    }

    suspend fun fetchOrder(uid: String, orderId: String): AppOrder? {
        val doc = ordersRef.document(orderId).get().await()
        if (doc.exists()) return doc.data?.let { AppOrder.fromMap(it) }

        val legacyDoc = userOrdersRef(uid).document(orderId).get().await()
        return legacyDoc.data?.let { AppOrder.fromMap(it) }
    }

    suspend fun updateOrderStatus(uid: String, orderId: String, newStatus: String) {
        check(uid.isNotBlank()) { "uid is required." }
        runCatching {
            BackendFunctionsService.updateOrderStatus(orderId, newStatus)
        }.recoverCatching { error ->
            if (TrustedMutationFallbackPolicy.allowDirectWriteFallback(error) && canUpdateOrdersDirectly()) {
                Log.w(TAG, "Falling back to direct order status update", error)
                directUpdateOrderStatus(uid, orderId, newStatus)
            } else {
                throw error
            }
        }.getOrThrow()
    }

    private suspend fun canCreateOrdersDirectly(uid: String): Boolean {
        return FirebaseAuthManager.currentUser?.uid == uid
    }

    private suspend fun canUpdateOrdersDirectly(): Boolean {
        val uid = FirebaseAuthManager.currentUser?.uid ?: return false
        return AdminSession.isVerified(uid) || UserService.fetchUserRole(uid) == "admin"
    }

    private suspend fun directCreateOrder(
        uid: String,
        pendingOrder: AppOrder,
        deliveryType: String
    ): AppOrder {
        val shippingAddress = pendingOrder.shippingAddress
            ?: throw IllegalStateException("A complete shipping address is required.")
        val requestedItems = normalizeRequestedItems(pendingOrder.items)
        if (requestedItems.isEmpty()) {
            throw IllegalStateException("Your cart is empty.")
        }

        val normalizedDeliveryType = normalizeDeliveryType(deliveryType)
        val config = runCatching { FirestoreService.fetchCommerceConfig() }.getOrNull()
        val shippingFee = if (normalizedDeliveryType == "express") {
            config?.expressShippingFee ?: 12.5
        } else {
            config?.standardShippingFee ?: CartStore.LIVRAISON_FEE
        }

        val createdAt = System.currentTimeMillis()
        val orderRef = ordersRef.document()
        val orderId = orderRef.id
        val legacyOrderRef = userOrdersRef(uid).document(orderId)
        val cartRef = userCartRef(uid)
        val legacyCart = legacyCartRef(uid)
        Log.d(
            TAG,
            "Direct order create start: orderId=$orderId uid=$uid authUid=${FirebaseAuthManager.currentUser?.uid}"
        )

        val orderItems = mutableListOf<OrderItem>()
        var subtotal = 0.0

        requestedItems.forEach { requestedItem ->
            val productRef = productsRef.document(requestedItem.productId)
            Log.d(TAG, "Validating product ${productRef.path} for quantity=${requestedItem.quantity}")
            val productDoc = productRef.get().await()
            if (!productDoc.exists()) {
                throw IllegalStateException("Product ${requestedItem.productId} was not found.")
            }

            val productData = productDoc.data ?: emptyMap<String, Any>()
            val title = productData["title"] as? String
                ?: requestedItem.name.ifBlank { requestedItem.productId }
            val stock = ((productData["stock"] as? Number)?.toInt() ?: 0).coerceAtLeast(0)
            val isActive = productData["isActive"] as? Boolean ?: true
            val status = (productData["status"] as? String ?: "published")
                .trim()
                .lowercase(Locale.US)

            if (!isActive || status == "archived" || status == "draft") {
                throw IllegalStateException("Product $title is not available.")
            }
            if (requestedItem.quantity > stock) {
                throw IllegalStateException("Insufficient stock for $title.")
            }

            val price = (productData["price"] as? Number)?.toDouble()
                ?: requestedItem.priceAtPurchase
            val imageUrls = (productData["imageUrls"] as? List<*>)?.mapNotNull { it as? String }
                ?: emptyList()
            val thumbnailUrl = imageUrls.firstOrNull()
                ?: (productData["imageUrl"] as? String)
                ?: requestedItem.thumbnailUrl

            subtotal += price * requestedItem.quantity
            orderItems += requestedItem.copy(
                name = title,
                priceAtPurchase = price,
                thumbnailUrl = thumbnailUrl
            )
        }

        val trackingEvents = listOf(OrderStatusEntry(status = "pending", changedAt = createdAt))
        val total = subtotal + shippingFee
        val itemPayload = orderItems.map { it.toMap() }
        val timelinePayload = trackingEvents.map { it.toMap() }
        val orderPayload = mapOf(
            "id" to orderId,
            "uid" to uid,
            "userId" to uid,
            "status" to "pending",
            "paymentMethod" to "COD",
            "deliveryType" to normalizedDeliveryType,
            "subtotal" to subtotal,
            "deliveryFee" to shippingFee,
            "shippingFee" to shippingFee,
            "total" to total,
            "shippingAddress" to shippingAddress.toMap(),
            "items" to itemPayload,
            "trackingEvents" to timelinePayload,
            "statusTimeline" to timelinePayload,
            "serverVerified" to false,
            "schemaVersion" to SCHEMA_VERSION,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        val modernCartPayload = mapOf(
            "items" to emptyList<Map<String, Any?>>(),
            "schemaVersion" to SCHEMA_VERSION,
            "updatedAt" to FieldValue.serverTimestamp()
        )
        val legacyCartPayload = mapOf(
            "items" to emptyMap<String, Int>(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        suspend fun writeOrThrow(path: String, block: suspend () -> Unit) {
            try {
                Log.d(TAG, "Writing $path")
                block()
                Log.d(TAG, "Wrote $path")
            } catch (error: Exception) {
                Log.e(TAG, "Failed writing $path", error)
                throw error
            }
        }

        writeOrThrow(orderRef.path) { orderRef.set(orderPayload).await() }
        writeOrThrow(legacyOrderRef.path) { legacyOrderRef.set(orderPayload).await() }
        writeOrThrow(cartRef.path) { cartRef.set(modernCartPayload).await() }
        writeOrThrow(legacyCart.path) { legacyCart.set(legacyCartPayload).await() }

        return AppOrder(
            id = orderId,
            uid = uid,
            status = "pending",
            paymentMethod = "COD",
            subtotal = subtotal,
            deliveryFee = shippingFee,
            total = total,
            shippingAddress = shippingAddress,
            items = orderItems,
            trackingEvents = trackingEvents,
            createdAt = createdAt,
            updatedAt = createdAt
        )
    }

    private suspend fun directUpdateOrderStatus(
        uid: String,
        orderId: String,
        newStatus: String
    ): AppOrder {
        val normalizedStatus = requireKnownOrderStatus(newStatus)
        val orderRef = ordersRef.document(orderId)
        val orderDoc = orderRef.get().await()
        if (!orderDoc.exists()) {
            throw IllegalStateException("Order not found.")
        }

        val currentOrder = orderDoc.data?.let(AppOrder::fromMap)
            ?: throw IllegalStateException("Order not found.")
        if (currentOrder.uid.isBlank()) {
            throw IllegalStateException("Order owner is missing.")
        }
        if (uid.isNotBlank() && currentOrder.uid != uid) {
            throw IllegalStateException("Order owner mismatch.")
        }
        if (!canTransitionOrderStatus(currentOrder.status, normalizedStatus)) {
            throw IllegalStateException(
                "Cannot change order status from ${currentOrder.status} to $normalizedStatus."
            )
        }

        val updatedOrder = currentOrder.withStatus(normalizedStatus, System.currentTimeMillis())
        val timelinePayload = updatedOrder.trackingEvents.map { it.toMap() }
        val updatePayload = mapOf(
            "status" to normalizedStatus,
            "trackingEvents" to timelinePayload,
            "statusTimeline" to timelinePayload,
            "updatedAt" to FieldValue.serverTimestamp(),
            "schemaVersion" to SCHEMA_VERSION
        )

        orderRef.set(updatePayload, SetOptions.merge()).await()
        userOrdersRef(currentOrder.uid).document(orderId).set(updatePayload, SetOptions.merge()).await()
        userInboxRef(currentOrder.uid).document().set(
            mapOf(
                "type" to "order_status_changed",
                "title" to "Commande mise a jour",
                "body" to "Le statut de votre commande $orderId est maintenant $normalizedStatus.",
                "route" to "order_details",
                "entityRef" to orderId,
                "orderId" to orderId,
                "readAt" to null,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
                "schemaVersion" to SCHEMA_VERSION
            )
        ).await()

        return updatedOrder
    }

    private fun normalizeRequestedItems(items: List<OrderItem>): List<OrderItem> {
        return items
            .filter { it.productId.isNotBlank() && it.quantity > 0 }
            .groupBy { it.productId }
            .map { (_, grouped) ->
                val first = grouped.first()
                first.copy(quantity = grouped.sumOf { it.quantity })
            }
    }

    private fun normalizeDeliveryType(deliveryType: String): String {
        return if (deliveryType.trim().equals("express", ignoreCase = true)) {
            "express"
        } else {
            "standard"
        }
    }

    private fun requireKnownOrderStatus(status: String): String {
        val normalized = status.trim().lowercase(Locale.US)
        check(normalized in allowedOrderStatuses) { "Unsupported order status." }
        return normalized
    }

    private fun normalizeExistingOrderStatus(status: String): String {
        val normalized = status.trim().lowercase(Locale.US)
        return normalized.takeIf { it in allowedOrderStatuses } ?: "pending"
    }

    private fun canTransitionOrderStatus(currentStatus: String, nextStatus: String): Boolean {
        return when (normalizeExistingOrderStatus(currentStatus)) {
            "pending" -> nextStatus in setOf("pending", "preparing", "shipped", "delivered")
            "preparing" -> nextStatus in setOf("preparing", "shipped", "delivered")
            "shipped" -> nextStatus in setOf("shipped", "delivered")
            else -> nextStatus == "delivered"
        }
    }
}
