package isim.ia2y.myapplication

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Represents a customer order stored in Firestore under users/{uid}/orders/{orderId}.
 */
// Cette classe organise cette partie de l'app.
data class AppOrder(
    val id: String = "",
    /** Map of productId -> quantity */
    val items: Map<String, Int> = emptyMap(),
    val subtotal: Double = 0.0,
    val shippingFee: Double = 0.0,
    val total: Double = 0.0,
    val deliveryType: String = "standard",   // "standard" | "express"
    val paymentMethod: String = "cash",       // "card" | "edinar" | "cash"
    /** pending → preparing → shipped → delivered */
    val status: String = "pending",
    val createdAt: Long = System.currentTimeMillis()
) {
    /** Human-readable date string, e.g. "09 Mar 2026" */
    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("dd MMM yyyy", Locale.FRANCE)
            return sdf.format(Date(createdAt))
        }

    /** Formatted short order ID for display, e.g. "#FW-2026-001" */
    val displayId: String
        get() = if (id.isNotEmpty()) "#FW-${id.takeLast(6).uppercase()}" else "#FW-??????"

    /** Friendly status label in French */
    val statusLabel: String
        get() = when (status) {
            "pending"    -> "En attente"
            "preparing"  -> "En préparation"
            "shipped"    -> "En livraison"
            "delivered"  -> "Livré"
            else         -> status
        }

    /** Convert to a Firestore-friendly map */
    // Cette fonction fait une action de cette partie de l'app.
    fun toMap(): Map<String, Any> = mapOf(
        "id"            to id,
        "items"         to items,
        "subtotal"      to subtotal,
        "shippingFee"   to shippingFee,
        "total"         to total,
        "deliveryType"  to deliveryType,
        "paymentMethod" to paymentMethod,
        "status"        to status,
        "createdAt"     to createdAt
    )

    companion object {
        /** Build an AppOrder from a Firestore document map */
        @Suppress("UNCHECKED_CAST")
        // Cette fonction fait une action de cette partie de l'app.
        fun fromMap(map: Map<String, Any>): AppOrder = AppOrder(
            id            = map["id"] as? String ?: "",
            items         = (map["items"] as? Map<String, Any>)?.mapValues { (it.value as? Long)?.toInt() ?: 1 } ?: emptyMap(),
            subtotal      = (map["subtotal"] as? Number)?.toDouble() ?: 0.0,
            shippingFee   = (map["shippingFee"] as? Number)?.toDouble() ?: 0.0,
            total         = (map["total"] as? Number)?.toDouble() ?: 0.0,
            deliveryType  = map["deliveryType"] as? String ?: "standard",
            paymentMethod = map["paymentMethod"] as? String ?: "cash",
            status        = map["status"] as? String ?: "pending",
            createdAt     = (map["createdAt"] as? Long) ?: System.currentTimeMillis()
        )
    }
}
