package isim.ia2y.myapplication

import org.junit.Assert.assertEquals
import org.junit.Test

class FirestoreCollectionsTest {

    @Test
    fun collectionNamesAreCorrect() {
        assertEquals("users", FirestoreCollections.USERS)
        assertEquals("products", FirestoreCollections.PRODUCTS)
        assertEquals("orders", FirestoreCollections.ORDERS)
        assertEquals("cart", FirestoreCollections.CART)
        assertEquals("in_app_notifications", FirestoreCollections.IN_APP_NOTIFICATIONS)
        assertEquals("notification_reads", FirestoreCollections.NOTIFICATION_READS)
        assertEquals("addresses", FirestoreCollections.ADDRESSES)
        assertEquals("favorites", FirestoreCollections.FAVORITES)
        assertEquals("app_config", FirestoreCollections.APP_CONFIG)
        assertEquals("commerce", FirestoreCollections.COMMERCE)
    }
}
