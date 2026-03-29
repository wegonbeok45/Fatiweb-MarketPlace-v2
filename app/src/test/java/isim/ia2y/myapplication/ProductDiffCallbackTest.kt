package isim.ia2y.myapplication

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProductDiffCallbackTest {

    private lateinit var callback: ProductDiffCallback

    private fun product(
        id: String = "p1",
        title: String = "Olive Oil",
        subtitle: String = "Extra virgin",
        price: Double = 12.99,
        rating: Double = 4.5,
        reviewsCount: Int = 42,
        tags: List<String> = listOf("organic"),
        description: String = "Premium Tunisian olive oil",
        bullets: List<String> = listOf("Cold pressed"),
        imageRes: Int = 0
    ) = Product(
        id = id,
        title = title,
        subtitle = subtitle,
        price = price,
        rating = rating,
        reviewsCount = reviewsCount,
        tags = tags,
        description = description,
        bullets = bullets,
        imageRes = imageRes
    )

    @Before
    fun setup() {
        callback = ProductDiffCallback()
    }

    @Test
    fun areItemsTheSame_sameId_returnsTrue() {
        val old = product(id = "abc")
        val new = product(id = "abc", title = "Different Title")
        assertTrue(callback.areItemsTheSame(old, new))
    }

    @Test
    fun areItemsTheSame_differentId_returnsFalse() {
        val old = product(id = "abc")
        val new = product(id = "xyz")
        assertFalse(callback.areItemsTheSame(old, new))
    }

    @Test
    fun areContentsTheSame_identicalProducts_returnsTrue() {
        val old = product()
        val new = product()
        assertTrue(callback.areContentsTheSame(old, new))
    }

    @Test
    fun areContentsTheSame_differentPrice_returnsFalse() {
        val old = product(price = 10.0)
        val new = product(price = 20.0)
        assertFalse(callback.areContentsTheSame(old, new))
    }

    @Test
    fun areContentsTheSame_differentRating_returnsFalse() {
        val old = product(rating = 3.0)
        val new = product(rating = 5.0)
        assertFalse(callback.areContentsTheSame(old, new))
    }

    @Test
    fun areContentsTheSame_differentTags_returnsFalse() {
        val old = product(tags = listOf("organic"))
        val new = product(tags = listOf("bio"))
        assertFalse(callback.areContentsTheSame(old, new))
    }
}
