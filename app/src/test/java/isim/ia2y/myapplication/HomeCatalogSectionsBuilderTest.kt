package isim.ia2y.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeCatalogSectionsBuilderTest {

    @Test
    fun build_prioritizes_latest_then_excludes_them_from_discover() {
        val products = listOf(
            fakeProduct("older", updatedAt = 100L, rating = 4.0, reviewsCount = 4),
            fakeProduct("latestA", updatedAt = 500L, rating = 4.1, reviewsCount = 3),
            fakeProduct("latestB", updatedAt = 400L, rating = 4.9, reviewsCount = 9),
            fakeProduct("discoverTop", updatedAt = 300L, rating = 5.0, reviewsCount = 20),
            fakeProduct("inactive", updatedAt = 600L, isActive = false)
        )

        val sections = HomeCatalogSectionsBuilder.build(
            products,
            featuredLimit = 1,
            latestLimit = 2,
            discoverLimit = 3
        )

        assertEquals(listOf("discoverTop"), sections.featured.map { it.id })
        assertEquals(listOf("latestA", "latestB"), sections.latest.map { it.id })
        assertEquals(listOf("older"), sections.discover.map { it.id })
        assertFalse(sections.latest.any { latest -> sections.featured.any { it.id == latest.id } })
        assertFalse(sections.discover.any { discover -> sections.latest.any { it.id == discover.id } })
        assertFalse(sections.discover.any { discover -> sections.featured.any { it.id == discover.id } })
    }

    private fun fakeProduct(
        id: String,
        updatedAt: Long,
        rating: Double = 4.5,
        reviewsCount: Int = 10,
        isActive: Boolean = true
    ): Product {
        return Product(
            id = id,
            title = id,
            subtitle = "subtitle",
            price = 10.0,
            rating = rating,
            reviewsCount = reviewsCount,
            tags = listOf("TUNISIA"),
            description = "desc",
            bullets = listOf("bullet"),
            imageRes = 1,
            category = "craft",
            origin = "tunisia",
            stock = 8,
            isBio = false,
            isActive = isActive,
            updatedAt = updatedAt
        )
    }
}
