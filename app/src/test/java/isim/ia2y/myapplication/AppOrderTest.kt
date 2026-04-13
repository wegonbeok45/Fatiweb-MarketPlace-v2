package isim.ia2y.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOrderTest {

    @Test
    fun withStatus_updatesStatusTimestampAndTrackingEvents() {
        val createdAt = 1_710_000_000_000L
        val order = AppOrder(
            id = "order-1",
            items = listOf(OrderItem(productId = "chechia", quantity = 1)),
            status = "pending",
            createdAt = createdAt,
            updatedAt = createdAt,
            trackingEvents = listOf(OrderStatusEntry("pending", createdAt))
        )

        val changedAt = createdAt + 3_600_000L
        val updated = order.withStatus("shipped", changedAt)

        assertEquals("shipped", updated.status)
        assertEquals(changedAt, updated.updatedAt)
        assertEquals(2, updated.trackingEvents.size)
        assertTrue(updated.trackingEvents.any { it.status == "pending" && it.changedAt == createdAt })
        assertTrue(updated.trackingEvents.any { it.status == "shipped" && it.changedAt == changedAt })
    }

    @Test
    fun fromMap_restoresShippingAddressAndLegacyTimeline() {
        val createdAt = 1_710_000_000_000L
        val map = mapOf(
            "id" to "order-2",
            "uid" to "user-1",
            "items" to listOf(
                mapOf(
                    "productId" to "balgha",
                    "name" to "Balgha cuir",
                    "priceAtPurchase" to 65.0,
                    "quantity" to 2,
                    "thumbnailUrl" to "https://example.com/balgha.jpg"
                )
            ),
            "subtotal" to 130.0,
            "shippingFee" to 7.0,
            "total" to 137.0,
            "paymentMethod" to "cash",
            "status" to "preparing",
            "createdAt" to createdAt,
            "updatedAt" to createdAt + 1_000L,
            "deliveryAddressSnapshot" to mapOf(
                "label" to "Maison",
                "recipientName" to "Ahmed Ben Salem",
                "phone" to "+21612345678",
                "governorate" to "Tunis",
                "city" to "La Marsa",
                "addressLine1" to "10 Rue du Lac",
                "addressLine2" to "Appartement 4",
                "postalCode" to "2070",
                "deliveryNotes" to "Sonner"
            ),
            "statusTimeline" to listOf(
                mapOf("status" to "pending", "changedAt" to createdAt),
                mapOf("status" to "preparing", "changedAt" to createdAt + 1_000L)
            )
        )

        val order = AppOrder.fromMap(map)

        assertEquals("order-2", order.id)
        assertEquals("user-1", order.uid)
        assertEquals("Ahmed Ben Salem", order.shippingAddress?.recipientName)
        assertEquals(1, order.items.size)
        assertEquals("balgha", order.items.first().productId)
        assertEquals(2, order.trackingEvents.size)
        assertEquals("preparing", order.status)
    }
}
