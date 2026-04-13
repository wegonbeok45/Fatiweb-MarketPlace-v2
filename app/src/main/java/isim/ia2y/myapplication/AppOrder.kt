package isim.ia2y.myapplication

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class OrderItem(
    val productId: String = "",
    val name: String = "",
    val priceAtPurchase: Double = 0.0,
    val quantity: Int = 0,
    val thumbnailUrl: String = ""
) {
    fun toMap() = mapOf(
        "productId" to productId,
        "name" to name,
        "priceAtPurchase" to priceAtPurchase,
        "quantity" to quantity,
        "thumbnailUrl" to thumbnailUrl
    )

    companion object {
        fun fromMap(map: Map<String, Any?>): OrderItem = OrderItem(
            productId = map["productId"] as? String ?: "",
            name = map["name"] as? String ?: "",
            priceAtPurchase = (map["priceAtPurchase"] as? Number)?.toDouble() ?: 0.0,
            quantity = (map["quantity"] as? Number)?.toInt() ?: 0,
            thumbnailUrl = map["thumbnailUrl"] as? String ?: ""
        )
    }
}

data class AppOrder(
    val id: String = "",
    val uid: String = "",
    val status: String = "pending",
    val paymentMethod: String = "COD",
    val subtotal: Double = 0.0,
    val deliveryFee: Double = 0.0,
    val total: Double = 0.0,
    val shippingAddress: DeliveryAddressSnapshot? = null,
    val items: List<OrderItem> = emptyList(),
    val trackingEvents: List<OrderStatusEntry> = emptyList(),
    val createdAt: Any? = null,
    val updatedAt: Any? = null
) {
    val formattedDate: String
        get() = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(createdAtMillis))

    val createdAtMillis: Long get() = when (val res = createdAt) {
        is Long -> res
        is com.google.firebase.Timestamp -> res.toDate().time
        else -> System.currentTimeMillis()
    }

    val displayId: String
        get() = if (id.isNotEmpty()) "#FW-${id.takeLast(6).uppercase()}" else "#FW-??????"

    fun toMap(): Map<String, Any?> = buildMap {
        put("id", id)
        put("uid", uid)
        put("status", status)
        put("paymentMethod", paymentMethod)
        put("subtotal", subtotal)
        put("deliveryFee", deliveryFee)
        put("total", total)
        put("items", items.map { it.toMap() })
        put("trackingEvents", trackingEvents.map { it.toMap() })
        put("createdAt", createdAt ?: com.google.firebase.firestore.FieldValue.serverTimestamp())
        put("updatedAt", com.google.firebase.firestore.FieldValue.serverTimestamp())
        shippingAddress?.let { put("shippingAddress", it.toMap()) }
    }

    fun withStatus(newStatus: String, changedAt: Any = com.google.firebase.Timestamp.now()): AppOrder {
        val nextTimeline = trackingEvents
            .filterNot { it.status == newStatus }
            .plus(OrderStatusEntry(newStatus, when(changedAt) {
                is Long -> changedAt
                is com.google.firebase.Timestamp -> changedAt.toDate().time
                else -> System.currentTimeMillis()
            }))
            .sortedBy { it.changedAt }
        return copy(
            status = newStatus,
            updatedAt = changedAt,
            trackingEvents = nextTimeline
        )
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any>): AppOrder {
            val status = map["status"] as? String ?: "pending"
            val createdAt = map["createdAt"]
            val timeline = (map["trackingEvents"] as? List<*>)
                ?.mapNotNull { OrderStatusEntry.fromAny(it) }
                ?: (map["statusTimeline"] as? List<*>)?.mapNotNull { OrderStatusEntry.fromAny(it) } // Compat
                ?: emptyList()

            return AppOrder(
                id = map["id"] as? String ?: "",
                uid = map["uid"] as? String ?: map["userId"] as? String ?: "",
                status = status,
                paymentMethod = map["paymentMethod"] as? String ?: "COD",
                subtotal = (map["subtotal"] as? Number)?.toDouble() ?: 0.0,
                deliveryFee = (map["deliveryFee"] as? Number)?.toDouble() ?: (map["shippingFee"] as? Number)?.toDouble() ?: 0.0,
                total = (map["total"] as? Number)?.toDouble() ?: 0.0,
                shippingAddress = DeliveryAddressSnapshot.fromAny(map["shippingAddress"] ?: map["deliveryAddressSnapshot"]),
                items = (map["items"] as? List<Map<String, Any>>)?.map { OrderItem.fromMap(it) } ?: emptyList(),
                trackingEvents = timeline,
                createdAt = createdAt,
                updatedAt = map["updatedAt"] ?: createdAt
            )
        }
    }
}

fun AppOrder.statusLabel(context: Context): String = orderStatusLabel(context, status)

fun orderStatusLabel(context: Context, status: String): String = when (status) {
    "pending" -> context.getString(R.string.order_status_pending)
    "preparing" -> context.getString(R.string.order_status_preparing)
    "shipped" -> context.getString(R.string.order_status_shipped)
    "delivered" -> context.getString(R.string.order_status_delivered)
    else -> status
}
