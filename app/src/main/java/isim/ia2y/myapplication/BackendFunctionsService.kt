package isim.ia2y.myapplication

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await

object BackendFunctionsService {
    private const val FUNCTIONS_REGION = "europe-west1"

    private val functions: FirebaseFunctions by lazy {
        FirebaseFunctions.getInstance(FUNCTIONS_REGION)
    }

    suspend fun createOrder(order: AppOrder, deliveryType: String): AppOrder {
        val response = call(
            "createOrder",
            mapOf(
                "clientRequestId" to buildClientRequestId(order),
                "deliveryType" to deliveryType,
                "order" to mapOf(
                    "paymentMethod" to order.paymentMethod,
                    "deliveryType" to deliveryType,
                    "shippingAddress" to (order.shippingAddress?.toMap() ?: emptyMap<String, Any>()),
                    "items" to order.items.map { item ->
                        mapOf(
                            "productId" to item.productId,
                            "quantity" to item.quantity
                        )
                    }
                )
            )
        )

        val orderMap = response["order"].asStringKeyedMap()
            ?: throw IllegalStateException("Trusted checkout returned no order.")
        @Suppress("UNCHECKED_CAST")
        return AppOrder.fromMap(orderMap as Map<String, Any>)
    }

    suspend fun updateOrderStatus(orderId: String, newStatus: String): AppOrder {
        val response = call(
            "updateOrderStatus",
            mapOf(
                "orderId" to orderId,
                "status" to newStatus
            )
        )

        val orderMap = response["order"].asStringKeyedMap()
            ?: throw IllegalStateException("Order status update returned no order.")
        @Suppress("UNCHECKED_CAST")
        return AppOrder.fromMap(orderMap as Map<String, Any>)
    }

    suspend fun upsertProduct(product: Product) {
        call("adminUpsertProduct", mapOf("product" to product.toFunctionPayload()))
    }

    suspend fun deleteProduct(productId: String) {
        call("adminDeleteProduct", mapOf("productId" to productId))
    }

    suspend fun sendAnnouncement(
        title: String,
        message: String,
        audience: String = "all"
    ): FirestoreService.InAppNotification {
        val response = call(
            "adminSendAnnouncement",
            mapOf(
                "title" to title,
                "message" to message,
                "audience" to audience
            )
        )

        val notificationMap = response["notification"].asStringKeyedMap()
            ?: throw IllegalStateException("Announcement creation returned no notification.")
        return FirestoreService.InAppNotification(
            id = notificationMap["id"] as? String ?: "",
            title = notificationMap["title"] as? String ?: title,
            message = notificationMap["message"] as? String ?: message,
            createdAt = notificationMap["createdAt"].toEpochMillis(),
            createdBy = notificationMap["createdBy"] as? String ?: "",
            audience = notificationMap["audience"] as? String ?: audience
        )
    }

    suspend fun submitReview(productId: String, review: ProductReview): ProductReview {
        val response = call(
            "submitReview",
            mapOf(
                "productId" to productId,
                "review" to mapOf(
                    "rating" to review.rating,
                    "comment" to review.comment
                )
            )
        )

        val reviewMap = response["review"].asStringKeyedMap()
            ?: throw IllegalStateException("Review submission returned no review.")
        @Suppress("UNCHECKED_CAST")
        return ProductReview.fromMap(
            reviewMap["reviewId"] as? String ?: "",
            reviewMap as Map<String, Any>
        )
    }

    suspend fun assistantSendMessage(
        history: List<ChatMessage>,
        userId: String?
    ): String {
        val response = call(
            "assistantSendMessage",
            mapOf(
                "userId" to userId,
                "history" to history.map { message ->
                    mapOf(
                        "role" to message.role.name,
                        "text" to message.text,
                        "timestamp" to message.timestamp
                    )
                }
            )
        )
        return response["reply"] as? String
            ?: throw IllegalStateException("Assistant returned an empty reply.")
    }

    private suspend fun call(
        functionName: String,
        payload: Map<String, Any?>
    ): Map<String, Any?> {
        return try {
            val result = functions
                .getHttpsCallable(functionName)
                .call(payload)
                .await()
            result.getData().asStringKeyedMap()
                ?: throw IllegalStateException("Function $functionName returned no structured payload.")
        } catch (error: Exception) {
            throw error.toDomainError()
        }
    }

    private fun buildClientRequestId(order: AppOrder): String {
        val base = if (order.id.isNotBlank()) order.id else "android_${System.currentTimeMillis()}"
        return base.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
    }
}

private fun Product.toFunctionPayload(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "subtitle" to subtitle,
    "price" to price,
    "rating" to rating,
    "reviewsCount" to reviewsCount,
    "tags" to tags,
    "description" to description,
    "bullets" to bullets,
    "imageUrl" to imageUrl,
    "imageUrls" to imageUrls,
    "category" to category,
    "categoryIds" to categoryIds,
    "origin" to origin,
    "stock" to stock,
    "isBio" to isBio,
    "isActive" to isActive,
    "status" to status,
    "searchKeywords" to searchKeywords
)

private fun Any?.asStringKeyedMap(): Map<String, Any?>? {
    val map = this as? Map<*, *> ?: return null
    return map.entries
        .filter { it.key is String }
        .associate { (key, value) ->
            key as String to value.normalizeFunctionValue()
        }
}

private fun Any?.normalizeFunctionValue(): Any? = when (this) {
    is Map<*, *> -> this.entries
        .filter { it.key is String }
        .associate { (key, value) -> key as String to value.normalizeFunctionValue() }
    is List<*> -> this.map { it.normalizeFunctionValue() }
    else -> this
}

private fun Any?.toEpochMillis(): Long = when (this) {
    is Number -> this.toLong()
    is com.google.firebase.Timestamp -> this.toDate().time
    is Map<*, *> -> (this["seconds"] as? Number)?.toLong()?.times(1000L) ?: 0L
    else -> 0L
}

private fun Exception.toDomainError(): Throwable {
    val functionError = this as? FirebaseFunctionsException ?: return this
    val message = functionError.message ?: "Backend request failed."
    return IllegalStateException(message, functionError)
}
