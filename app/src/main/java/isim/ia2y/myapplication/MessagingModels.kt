package isim.ia2y.myapplication

import com.google.firebase.Timestamp

data class ProductChatContext(
    val productId: String = "",
    val title: String = "",
    val thumbnailUrl: String = "",
    val price: Double = 0.0,
    val priceMinor: Long = 0L
)

data class ParticipantSnapshot(
    val displayName: String = "",
    val avatarUrl: String = ""
)

data class Conversation(
    val id: String,
    val participantIds: List<String>,
    val buyerId: String,
    val sellerId: String,
    val productId: String,
    val product: ProductChatContext,
    val buyer: ParticipantSnapshot,
    val seller: ParticipantSnapshot,
    val lastMessageText: String,
    val lastMessageType: String,
    val lastMessageAt: Long,
    val lastMessageSenderId: String,
    val unreadCounts: Map<String, Int>,
    val lastReadAt: Map<String, Long>,
    val hiddenFor: List<String>,
    val blockedBy: List<String>,
    val status: String
) {
    fun otherParticipantName(currentUid: String): String =
        if (currentUid == sellerId) buyer.displayName.ifBlank { "Client" } else seller.displayName.ifBlank { "Seller" }

    fun otherParticipantAvatar(currentUid: String): String =
        if (currentUid == sellerId) buyer.avatarUrl else seller.avatarUrl

    fun otherParticipantId(currentUid: String): String =
        if (currentUid == sellerId) buyerId else sellerId

    companion object {
        fun fromMap(id: String, map: Map<String, Any?>): Conversation {
            val product = map["productSnapshot"].asMap()
            val buyer = map["buyerSnapshot"].asMap()
            val seller = map["sellerSnapshot"].asMap()
            return Conversation(
                id = id,
                participantIds = map["participantIds"].asStringList(),
                buyerId = map["buyerId"] as? String ?: "",
                sellerId = map["sellerId"] as? String ?: "",
                productId = map["productId"] as? String ?: "",
                product = ProductChatContext(
                    productId = map["productId"] as? String ?: "",
                    title = product["title"] as? String ?: "",
                    thumbnailUrl = product["thumbnailUrl"] as? String ?: "",
                    price = (product["price"] as? Number)?.toDouble() ?: 0.0,
                    priceMinor = (product["priceMinor"] as? Number)?.toLong() ?: 0L
                ),
                buyer = ParticipantSnapshot(
                    displayName = buyer["displayName"] as? String ?: "",
                    avatarUrl = buyer["avatarUrl"] as? String ?: ""
                ),
                seller = ParticipantSnapshot(
                    displayName = seller["displayName"] as? String ?: "",
                    avatarUrl = seller["avatarUrl"] as? String ?: ""
                ),
                lastMessageText = map["lastMessageText"] as? String ?: "",
                lastMessageType = map["lastMessageType"] as? String ?: "system",
                lastMessageAt = map["lastMessageAt"].toMillis(),
                lastMessageSenderId = map["lastMessageSenderId"] as? String ?: "",
                unreadCounts = map["unreadCounts"].asIntMap(),
                lastReadAt = map["lastReadAt"].asMillisMap(),
                hiddenFor = map["hiddenFor"].asStringList(),
                blockedBy = map["blockedBy"].asStringList(),
                status = map["status"] as? String ?: "active"
            )
        }
    }
}

data class ConversationMessage(
    val id: String,
    val conversationId: String,
    val senderId: String,
    val receiverId: String,
    val type: String,
    val text: String,
    val imageUrl: String,
    val thumbnailUrl: String,
    val storagePath: String,
    val createdAt: Long,
    val readBy: Map<String, Long>,
    val reactions: Map<String, String>,
    val deliveryStatus: String,
    val clientMessageId: String,
    val isLocalPending: Boolean = false,
    val localProgress: Int = 0,
    val localError: String? = null
) {
    companion object {
        fun fromMap(id: String, map: Map<String, Any?>): ConversationMessage =
            ConversationMessage(
                id = id,
                conversationId = map["conversationId"] as? String ?: "",
                senderId = map["senderId"] as? String ?: "",
                receiverId = map["receiverId"] as? String ?: "",
                type = map["type"] as? String ?: "text",
                text = map["text"] as? String ?: "",
                imageUrl = map["imageUrl"] as? String ?: "",
                thumbnailUrl = map["thumbnailUrl"] as? String ?: "",
                storagePath = map["storagePath"] as? String ?: "",
                createdAt = map["createdAt"].toMillis(),
                readBy = map["readBy"].asMillisMap(),
                reactions = map["reactions"].asStringMap(),
                deliveryStatus = map["deliveryStatus"] as? String ?: "sent",
                clientMessageId = map["clientMessageId"] as? String ?: id
            )
    }
}

@Suppress("UNCHECKED_CAST")
private fun Any?.asMap(): Map<String, Any?> = this as? Map<String, Any?> ?: emptyMap()

private fun Any?.asStringList(): List<String> =
    (this as? List<*>).orEmpty().mapNotNull { it as? String }

private fun Any?.asIntMap(): Map<String, Int> {
    val raw = this as? Map<*, *> ?: return emptyMap()
    return raw.mapNotNull { (key, value) ->
        val uid = key as? String ?: return@mapNotNull null
        val count = (value as? Number)?.toInt() ?: return@mapNotNull null
        uid to count
    }.toMap()
}

private fun Any?.asMillisMap(): Map<String, Long> {
    val raw = this as? Map<*, *> ?: return emptyMap()
    return raw.mapNotNull { (key, value) ->
        val uid = key as? String ?: return@mapNotNull null
        uid to value.toMillis()
    }.toMap()
}

private fun Any?.asStringMap(): Map<String, String> {
    val raw = this as? Map<*, *> ?: return emptyMap()
    return raw.mapNotNull { (key, value) ->
        val uid = key as? String ?: return@mapNotNull null
        val reaction = value as? String ?: return@mapNotNull null
        uid to reaction
    }.toMap()
}

private fun Any?.toMillis(): Long = when (this) {
    is Timestamp -> toDate().time
    is Number -> toLong()
    is Map<*, *> -> (this["seconds"] as? Number)?.toLong()?.times(1000L) ?: 0L
    else -> 0L
}
