package isim.ia2y.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppOrderTest {

    @Test
    fun withStatus_updatesStatusTimestampAndTimeline() {
        val createdAt = 1_710_000_000_000L
        val order = AppOrder(
            id = "order-1",
            items = mapOf("chechia" to 1),
            status = "pending",
            createdAt = createdAt,
            updatedAt = createdAt,
            statusTimeline = listOf(OrderStatusEntry("pending", createdAt))
        )

        val changedAt = createdAt + 3_600_000L
        val updated = order.withStatus("shipped", changedAt)

        assertEquals("shipped", updated.status)
        assertEquals(changedAt, updated.updatedAt)
        assertEquals(2, updated.statusTimeline.size)
        assertTrue(updated.statusTimeline.any { it.status == "pending" && it.changedAt == createdAt })
        assertTrue(updated.statusTimeline.any { it.status == "shipped" && it.changedAt == changedAt })
    }

    @Test
    fun fromMap_restoresAddressSnapshotAndTimeline() {
        val createdAt = 1_710_000_000_000L
        val map = mapOf(
            "id" to "order-2",
            "items" to mapOf("balgha" to 2),
            "subtotal" to 130.0,
            "shippingFee" to 7.0,
            "total" to 137.0,
            "deliveryType" to "standard",
            "paymentMethod" to "cash",
            "status" to "preparing",
            "customerPhone" to "+21612345678",
            "estimatedDeliveryDate" to createdAt + 86_400_000L,
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
        assertEquals("Ahmed Ben Salem", order.deliveryAddressSnapshot?.recipientName)
        assertEquals("+21612345678", order.customerPhone)
        assertEquals(2, order.statusTimeline.size)
        assertEquals("preparing", order.status)
    }
}
