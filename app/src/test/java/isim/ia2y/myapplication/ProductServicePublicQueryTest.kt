package isim.ia2y.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductServicePublicQueryTest {
    @Test
    fun publicProductQueryShapeMatchesFirestoreRules() {
        assertEquals("isActive", PUBLIC_PRODUCT_QUERY_SHAPE.activeField)
        assertTrue(PUBLIC_PRODUCT_QUERY_SHAPE.activeValue)
        assertEquals("status", PUBLIC_PRODUCT_QUERY_SHAPE.statusField)
        assertEquals("published", PUBLIC_PRODUCT_QUERY_SHAPE.statusValue)
        assertEquals("updatedAt", PUBLIC_PRODUCT_QUERY_SHAPE.updatedAtField)
    }
}
