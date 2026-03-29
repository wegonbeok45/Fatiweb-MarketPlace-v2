package isim.ia2y.myapplication

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppOrder(
    val id: String = "",
    val items: Map<String, Int> = emptyMap(),
    val subtotal: Double = 0.0,
    val shippingFee: Double = 0.0,
    val total: Double = 0.0,
    val deliveryType: String = "standard",
    val paymentMethod: String = "cash",
    val status: String = "pending",
    val deliveryAddressSnapshot: DeliveryAddressSnapshot? = null,
    val customerPhone: String = "",
    val estimatedDeliveryDate: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val statusTimeline: List<OrderStatusEntry> = listOf(OrderStatusEntry(status, createdAt))
) {
    val formattedDate: String
        get() = SimpleDateFormat("dd MMM yyyy", Locale.FRANCE).format(Date(createdAt))

    val estimatedDeliveryLabel: String
        get() = if (estimatedDeliveryDate <= 0L) {
            ""
        } else {
            SimpleDateFormat("dd MMM yyyy", Locale.FRANCE).format(Date(estimatedDeliveryDate))
        }

    val displayId: String
        get() = if (id.isNotEmpty()) "#FW-${id.takeLast(6).uppercase()}" else "#FW-??????"

    fun toMap(): Map<String, Any> = buildMap {
        put("id", id)
        put("items", items)
        put("subtotal", subtotal)
        put("shippingFee", shippingFee)
        put("total", total)
        put("deliveryType", deliveryType)
        put("paymentMethod", paymentMethod)
        put("status", status)
        put("customerPhone", customerPhone)
        put("estimatedDeliveryDate", estimatedDeliveryDate)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("statusTimeline", statusTimeline.map { it.toMap() })
        deliveryAddressSnapshot?.let { put("deliveryAddressSnapshot", it.toMap()) }
    }

    fun withStatus(newStatus: String, changedAt: Long = System.currentTimeMillis()): AppOrder {
        val nextTimeline = statusTimeline
            .filterNot { it.status == newStatus }
            .plus(OrderStatusEntry(newStatus, changedAt))
            .sortedBy { it.changedAt }
        return copy(
            status = newStatus,
            updatedAt = changedAt,
            statusTimeline = nextTimeline
        )
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any>): AppOrder {
            val createdAt = (map["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
            val status = map["status"] as? String ?: "pending"
            val timeline = (map["statusTimeline"] as? List<*>)
                ?.mapNotNull { OrderStatusEntry.fromAny(it) }
                ?.ifEmpty { listOf(OrderStatusEntry(status, createdAt)) }
                ?: listOf(OrderStatusEntry(status, createdAt))

            return AppOrder(
                id = map["id"] as? String ?: "",
                items = (map["items"] as? Map<String, Any>)?.mapValues {
                    (it.value as? Number)?.toInt() ?: 1
                } ?: emptyMap(),
                subtotal = (map["subtotal"] as? Number)?.toDouble() ?: 0.0,
                shippingFee = (map["shippingFee"] as? Number)?.toDouble() ?: 0.0,
                total = (map["total"] as? Number)?.toDouble() ?: 0.0,
                deliveryType = map["deliveryType"] as? String ?: "standard",
                paymentMethod = map["paymentMethod"] as? String ?: "cash",
                status = status,
                deliveryAddressSnapshot = DeliveryAddressSnapshot.fromAny(map["deliveryAddressSnapshot"]),
                customerPhone = map["customerPhone"] as? String ?: "",
                estimatedDeliveryDate = (map["estimatedDeliveryDate"] as? Number)?.toLong() ?: 0L,
                createdAt = createdAt,
                updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: createdAt,
                statusTimeline = timeline
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
