package isim.ia2y.myapplication

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object OrderStatuses {
    const val PENDING = "pending"
    const val CONFIRMED = "confirmed"
    const val PREPARING = "preparing"
    const val SHIPPED = "shipped"
    const val DELIVERED = "delivered"
    const val CANCELLED = "cancelled"

    @Deprecated("Use PREPARING. Kept only to normalize legacy stored orders.")
    const val PROCESSING = PREPARING

    val supported = listOf(
        PENDING,
        CONFIRMED,
        PREPARING,
        SHIPPED,
        DELIVERED,
        CANCELLED
    )

    fun normalize(value: String?): String {
        val normalized = value?.trim()?.lowercase(Locale.US).orEmpty()
        return when (normalized) {
            "processing" -> PREPARING
            "failed", "returned", "refunded" -> CANCELLED
            in supported -> normalized
            else -> PENDING
        }
    }
}

data class OrderItem(
    val productId: String = "",
    val name: String = "",
    val priceAtPurchase: Double = 0.0,
    val priceAtPurchaseMinor: Long = toMinorUnits(priceAtPurchase),
    val quantity: Int = 0,
    val thumbnailUrl: String = "",
    val sellerId: String = "",
    val sellerName: String = "",
    val sellerAvatarUrl: String = ""
) {
    fun toMap() = mapOf(
        "productId" to productId,
        "name" to name,
        "priceAtPurchase" to priceAtPurchase,
        "priceAtPurchaseMinor" to priceAtPurchaseMinor,
        "quantity" to quantity,
        "thumbnailUrl" to thumbnailUrl,
        "sellerId" to sellerId,
        "sellerName" to sellerName,
        "sellerAvatarUrl" to sellerAvatarUrl
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): OrderItem = OrderItem(
            productId = map["productId"] as? String ?: "",
            name = map["name"] as? String ?: "",
            priceAtPurchase = (map["priceAtPurchase"] as? Number)?.toDouble() ?: 0.0,
            priceAtPurchaseMinor = (map["priceAtPurchaseMinor"] as? Number)?.toLong()
                ?: toMinorUnits((map["priceAtPurchase"] as? Number)?.toDouble() ?: 0.0),
            quantity = (map["quantity"] as? Number)?.toInt() ?: 0,
            thumbnailUrl = map["thumbnailUrl"] as? String ?: "",
            sellerId = map["sellerId"] as? String ?: "",
            sellerName = map["sellerName"] as? String ?: "",
            sellerAvatarUrl = map["sellerAvatarUrl"] as? String ?: ""
        )
    }
}

data class AppOrder(
    val id: String = "",
    val uid: String = "",
    val status: String = "pending",
    val paymentMethod: String = "COD",
    val subtotal: Double = 0.0,
    val subtotalMinor: Long = toMinorUnits(subtotal),
    val deliveryFee: Double = 0.0,
    val deliveryFeeMinor: Long = toMinorUnits(deliveryFee),
    val total: Double = 0.0,
    val totalMinor: Long = toMinorUnits(total),
    val shippingAddress: DeliveryAddressSnapshot? = null,
    val items: List<OrderItem> = emptyList(),
    val sellerIds: List<String> = emptyList(),
    val trackingEvents: List<OrderStatusEntry> = emptyList(),
    val createdAt: Any? = null,
    val updatedAt: Any? = null
) {
    val formattedDate: String
        get() = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(createdAtMillis))

    val createdAtMillis: Long get() = timestampMillis(createdAt) ?: System.currentTimeMillis()

    val displayId: String
        get() = if (id.isNotEmpty()) "#FW-${id.takeLast(6).uppercase()}" else "#FW-??????"

    fun toMap(): Map<String, Any?> = buildMap {
        put("id", id)
        put("uid", uid)
        put("status", OrderStatuses.normalize(status))
        put("paymentMethod", paymentMethod)
        put("subtotal", subtotal)
        put("subtotalMinor", subtotalMinor)
        put("deliveryFee", deliveryFee)
        put("deliveryFeeMinor", deliveryFeeMinor)
        put("total", total)
        put("totalMinor", totalMinor)
        put("items", items.map { it.toMap() })
        put("sellerIds", sellerIds.ifEmpty { items.mapNotNull { it.sellerId.takeIf(String::isNotBlank) }.distinct() })
        put("trackingEvents", trackingEvents.map { it.toMap() })
        put("createdAt", createdAt ?: com.google.firebase.firestore.FieldValue.serverTimestamp())
        put("updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp())
        shippingAddress?.let { put("shippingAddress", it.toMap()) }
    }

    fun withStatus(newStatus: String, changedAt: Any = com.google.firebase.Timestamp.now()): AppOrder {
        val normalizedStatus = OrderStatuses.normalize(newStatus)
        val nextTimeline = trackingEvents
            .filterNot { it.status == normalizedStatus }
            .plus(OrderStatusEntry(normalizedStatus, when(changedAt) {
                is Long -> changedAt
                is com.google.firebase.Timestamp -> changedAt.toDate().time
                else -> System.currentTimeMillis()
            }))
            .sortedBy { it.changedAt }
        return copy(
            status = normalizedStatus,
            updatedAt = changedAt,
            trackingEvents = nextTimeline
        )
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any>): AppOrder {
            val status = OrderStatuses.normalize(map["status"] as? String)
            val createdAt = map["createdAt"]
            val timeline = (map["trackingEvents"] as? List<*>)
                ?.mapNotNull { OrderStatusEntry.fromAny(it) }
                ?: (map["statusTimeline"] as? List<*>)?.mapNotNull { OrderStatusEntry.fromAny(it) } // Compat
                ?: emptyList()

            val items = (map["items"] as? List<*>)
                ?.mapNotNull { (it as? Map<*, *>)?.toStringKeyMap()?.let(OrderItem::fromMap) }
                ?: emptyList()
            val sellerIds = (map["sellerIds"] as? List<*>)
                ?.mapNotNull { it as? String }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?: items.mapNotNull { it.sellerId.takeIf(String::isNotBlank) }.distinct()

            return AppOrder(
                id = map["id"] as? String ?: "",
                uid = map["uid"] as? String ?: map["userId"] as? String ?: "",
                status = status,
                paymentMethod = map["paymentMethod"] as? String ?: "COD",
                subtotal = (map["subtotal"] as? Number)?.toDouble() ?: 0.0,
                subtotalMinor = (map["subtotalMinor"] as? Number)?.toLong()
                    ?: toMinorUnits((map["subtotal"] as? Number)?.toDouble() ?: 0.0),
                deliveryFee = (map["deliveryFee"] as? Number)?.toDouble() ?: (map["shippingFee"] as? Number)?.toDouble() ?: 0.0,
                deliveryFeeMinor = (map["deliveryFeeMinor"] as? Number)?.toLong()
                    ?: toMinorUnits((map["deliveryFee"] as? Number)?.toDouble() ?: (map["shippingFee"] as? Number)?.toDouble() ?: 0.0),
                total = (map["total"] as? Number)?.toDouble() ?: 0.0,
                totalMinor = (map["totalMinor"] as? Number)?.toLong()
                    ?: toMinorUnits((map["total"] as? Number)?.toDouble() ?: 0.0),
                shippingAddress = DeliveryAddressSnapshot.fromAny(map["shippingAddress"] ?: map["deliveryAddressSnapshot"]),
                items = items,
                sellerIds = sellerIds,
                trackingEvents = timeline,
                createdAt = createdAt,
                updatedAt = map["updatedAt"] ?: createdAt
            )
        }

        private fun timestampMillis(value: Any?): Long? = when (value) {
            is Long -> value
            is Int -> value.toLong()
            is Double -> value.toLong()
            is Float -> value.toLong()
            is Date -> value.time
            is com.google.firebase.Timestamp -> value.toDate().time
            is Map<*, *> -> {
                val seconds = (value["seconds"] ?: value["_seconds"]) as? Number
                val nanos = (value["nanoseconds"] ?: value["_nanoseconds"]) as? Number
                seconds?.toLong()?.let { it * 1000L + ((nanos?.toLong() ?: 0L) / 1_000_000L) }
            }
            else -> null
        }

        private fun Map<*, *>.toStringKeyMap(): Map<String, Any?> {
            return entries.associate { (key, value) -> key.toString() to value }
        }
    }
}

fun AppOrder.statusLabel(context: Context): String = orderStatusLabel(context, status)

fun orderStatusLabel(context: Context, status: String): String = when (OrderStatuses.normalize(status)) {
    OrderStatuses.PENDING -> context.getString(R.string.order_status_pending)
    OrderStatuses.CONFIRMED -> context.getString(R.string.order_status_confirmed)
    OrderStatuses.PREPARING -> context.getString(R.string.order_status_preparing)
    OrderStatuses.SHIPPED -> context.getString(R.string.order_status_shipped)
    OrderStatuses.DELIVERED -> context.getString(R.string.order_status_delivered)
    OrderStatuses.CANCELLED -> context.getString(R.string.order_status_cancelled)
    else -> status
}
