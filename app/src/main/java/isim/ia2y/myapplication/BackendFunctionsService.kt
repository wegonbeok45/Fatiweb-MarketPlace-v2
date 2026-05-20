package isim.ia2y.myapplication

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

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

    suspend fun fetchSellerWorkspace(limit: Long): AdminService.SellerWorkspace {
        val response = call("sellerFetchWorkspace", mapOf("limit" to limit))
        val orders = (response["orders"] as? List<*>)
            .orEmpty()
            .mapNotNull { raw ->
                val orderMap = raw.asStringKeyedMap() ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                AppOrder.fromMap(orderMap.filterValues { it != null } as Map<String, Any>)
            }
        val products = response["productsSummary"].asStringKeyedMap()
        return AdminService.SellerWorkspace(
            orders = orders,
            products = AdminService.SellerProductsSummary(
                totalProducts = products.intValue("totalProducts"),
                activeProducts = products.intValue("activeProducts"),
                lowStockProducts = products.intValue("lowStockProducts")
            )
        )
    }

    suspend fun upsertProduct(product: Product) {
        call("adminUpsertProduct", mapOf("product" to product.toFunctionPayload()))
    }

    suspend fun deleteProduct(productId: String) {
        call("adminDeleteProduct", mapOf("productId" to productId))
    }

    suspend fun promoteUserToVendeur(userId: String) {
        call("adminPromoteUserToVendeur", mapOf("userId" to userId))
    }

    suspend fun revokeVendeurAccess(userId: String) {
        call("adminRevokeVendeurAccess", mapOf("userId" to userId))
    }

    suspend fun sendAnnouncement(
        title: String,
        message: String,
        audience: String = "all"
    ): FirestoreService.InAppNotification {
        return runCatching {
            sendAnnouncementViaBackend(title, message, audience)
        }.recoverCatching { error ->
            if (allowDirectAnnouncementFallback(error)) {
                val uid = FirebaseAuthManager.currentUser?.uid.orEmpty()
                NotificationService.createInAppNotification(title, message, uid, audience)
            } else {
                throw error
            }
        }.getOrThrow()
    }

    private suspend fun sendAnnouncementViaBackend(
        title: String,
        message: String,
        audience: String
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

    private fun allowDirectAnnouncementFallback(error: Throwable): Boolean {
        val backendError = error as? BackendFunctionException ?: return false
        return backendError.code in setOf(
            FirebaseFunctionsException.Code.NOT_FOUND,
            FirebaseFunctionsException.Code.UNAVAILABLE,
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
            FirebaseFunctionsException.Code.INTERNAL,
            FirebaseFunctionsException.Code.UNKNOWN
        )
    }

    /** Self-service account deletion. Caller must be authenticated. */
    suspend fun deleteUserAccount() {
        call("deleteUserAccount", emptyMap<String, Any>())
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

    suspend fun openOrCreateConversation(productId: String): String {
        val response = call("openOrCreateConversation", mapOf("productId" to productId))
        return response["conversationId"] as? String
            ?: throw IllegalStateException("Messaging returned no conversation.")
    }

    suspend fun sendConversationMessage(
        conversationId: String,
        clientMessageId: String,
        type: String,
        text: String = "",
        imageUrl: String = "",
        thumbnailUrl: String = "",
        storagePath: String = ""
    ): String {
        val response = call(
            "sendConversationMessage",
            mapOf(
                "conversationId" to conversationId,
                "clientMessageId" to clientMessageId,
                "type" to type,
                "text" to text,
                "imageUrl" to imageUrl,
                "thumbnailUrl" to thumbnailUrl,
                "storagePath" to storagePath
            )
        )
        return response["messageId"] as? String ?: clientMessageId
    }

    suspend fun markConversationRead(conversationId: String) {
        call("markConversationRead", mapOf("conversationId" to conversationId))
    }

    suspend fun toggleConversationMessageReaction(
        conversationId: String,
        messageId: String,
        reaction: String = "heart"
    ) {
        call(
            "toggleConversationMessageReaction",
            mapOf(
                "conversationId" to conversationId,
                "messageId" to messageId,
                "reaction" to reaction
            )
        )
    }

    suspend fun hideConversation(conversationId: String) {
        call("hideConversation", mapOf("conversationId" to conversationId))
    }

    suspend fun blockConversationUser(blockedUserId: String, reason: String = "") {
        call("blockConversationUser", mapOf("blockedUserId" to blockedUserId, "reason" to reason))
    }

    suspend fun reportConversationMessage(
        conversationId: String,
        messageId: String,
        reason: String
    ) {
        call(
            "reportConversationMessage",
            mapOf(
                "conversationId" to conversationId,
                "messageId" to messageId,
                "reason" to reason
            )
        )
    }

    suspend fun generateProductInfo(imageBase64: String, imageMimeType: String): GeneratedProductInfo {
        return runCatching {
            val response = call(
                "generateProductInfo",
                mapOf(
                    "imageBase64" to imageBase64,
                    "imageMimeType" to imageMimeType
                )
            )
            val draft = response["draft"].asStringKeyedMap()
                ?: throw IllegalStateException("Product generator returned no draft.")
            GeneratedProductInfo.fromMap(draft)
        }.getOrThrow()
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

data class GeneratedProductInfo(
    val title: String,
    val subtitle: String,
    val categoryKey: String,
    val origin: String,
    val tags: List<String>,
    val description: String,
    val bullets: List<String>,
    val bioFriendly: Boolean,
    val suggestedPrice: Double,
    val suggestedStock: Int
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): GeneratedProductInfo {
            return GeneratedProductInfo(
                title = map["title"] as? String ?: "",
                subtitle = map["subtitle"] as? String ?: "",
                categoryKey = map["categoryKey"] as? String ?: "electronics",
                origin = map["origin"] as? String ?: "Tunisie",
                tags = map["tags"].asStringList(),
                description = map["description"] as? String ?: "",
                bullets = map["bullets"].asStringList(),
                bioFriendly = map["bioFriendly"] as? Boolean ?: false,
                suggestedPrice = map["suggestedPrice"].asPositiveDouble(),
                suggestedStock = (map["suggestedStock"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 1
            )
        }

        fun fromJson(json: JSONObject): GeneratedProductInfo {
            return GeneratedProductInfo(
                title = json.optString("title"),
                subtitle = json.optString("subtitle"),
                categoryKey = json.optString("categoryKey", "electronics"),
                origin = json.optString("origin", "Tunisie"),
                tags = json.optJSONArray("tags").asStringList(),
                description = json.optString("description"),
                bullets = json.optJSONArray("bullets").asStringList(),
                bioFriendly = json.optBoolean("bioFriendly", false),
                suggestedPrice = json.opt("suggestedPrice").asPositiveDouble(),
                suggestedStock = json.optInt("suggestedStock", 1).coerceAtLeast(1)
            )
        }
    }
}

class BackendFunctionException(
    val code: FirebaseFunctionsException.Code,
    message: String,
    cause: Throwable,
    val backendMessage: String? = null
) : IllegalStateException(message, cause)

private fun Product.toFunctionPayload(): Map<String, Any?> = mapOf(
    "id" to id,
    "title" to title,
    "subtitle" to subtitle,
    "price" to price,
    "priceMinor" to priceMinor,
    "rating" to rating,
    "reviewsCount" to reviewsCount,
    "tags" to tags,
    "description" to description,
    "bullets" to bullets,
    "imageUrl" to imageUrl,
    "imageUrls" to imageUrls,
    "category" to category,
    "categoryIds" to categoryIds,
    "categoryLeafId" to categoryLeafId,
    "origin" to origin,
    "stock" to stock,
    "isBio" to isBio,
    "isActive" to isActive,
    "status" to status,
    "discountPercent" to discountPercentClamped,
    "searchKeywords" to searchKeywords,
    "sellerId" to sellerId,
    "sellerName" to sellerName,
    "sellerAvatarUrl" to sellerAvatarUrl,
    "sellerVerifiedAt" to sellerVerifiedAt,
    "sellerMemberSince" to sellerMemberSince,
    "sellerTotalSold" to sellerTotalSold,
    "sellerRating" to sellerRating,
    "sellerRatingCount" to sellerRatingCount
)

private fun Any?.asStringKeyedMap(): Map<String, Any?>? {
    val map = this as? Map<*, *> ?: return null
    return map.entries
        .filter { it.key is String }
        .associate { (key, value) ->
            key as String to value.normalizeFunctionValue()
        }
}

private fun Any?.asStringList(): List<String> {
    return (this as? List<*>)
        .orEmpty()
        .mapNotNull { it as? String }
        .map { it.trim() }
        .filter { it.isNotBlank() }
}

private fun Any?.asPositiveDouble(default: Double = 0.0): Double {
    val value = when (this) {
        is Number -> toDouble()
        is String -> trim().replace(',', '.').toDoubleOrNull()
        else -> null
    }
    return value?.takeIf { !it.isNaN() && !it.isInfinite() && it > 0.0 } ?: default
}

private fun Map<String, Any?>?.intValue(key: String): Int {
    return (this?.get(key) as? Number)?.toInt() ?: 0
}

private fun JSONArray?.asStringList(): List<String> {
    if (this == null) return emptyList()
    return (0 until length())
        .mapNotNull { index -> optString(index).trim().takeIf { it.isNotBlank() } }
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
    val backendMessage = functionError.details.extractBackendMessage()
    val message = backendMessage ?: functionError.message ?: "Backend request failed."
    return BackendFunctionException(functionError.code, message, functionError, backendMessage)
}

private fun Any?.extractBackendMessage(): String? {
    return when (this) {
        is String -> takeIf { it.isNotBlank() }
        is Map<*, *> -> {
            val direct = listOf("message", "detail", "details", "reason", "error")
                .firstNotNullOfOrNull { key -> this[key].extractBackendMessage() }
            direct ?: values.firstNotNullOfOrNull { it.extractBackendMessage() }
        }
        else -> null
    }?.trim()?.takeIf { it.isNotBlank() }
}
