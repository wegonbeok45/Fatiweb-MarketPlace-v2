package isim.ia2y.myapplication

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceCategoriesTest {

    @Test
    fun matches_acceptsTopLevelLabelsAndLocalizedAliases() {
        assertTrue(MarketplaceCategories.matches(fakeProduct(category = "Electronique"), "electronics"))
        assertTrue(MarketplaceCategories.matches(fakeProduct(category = "Fashion"), "fashion"))
        assertTrue(MarketplaceCategories.matches(fakeProduct(category = "Food & Grocery"), "food-and-grocery"))
    }

    @Test
    fun matches_acceptsChildCategoryIdsForTheirTopLevelSections() {
        val game = fakeProduct(
            category = "baby-and-toys",
            categoryIds = listOf("baby-and-toys", "baby-and-toys__games-puzzles"),
            categoryLeafId = "baby-and-toys__games-puzzles"
        )
        val clothing = fakeProduct(
            category = "fashion",
            categoryIds = listOf("fashion", "fashion__womens-clothing"),
            categoryLeafId = "fashion__womens-clothing"
        )

        assertTrue(MarketplaceCategories.matches(game, "baby-and-toys"))
        assertTrue(MarketplaceCategories.matches(clothing, "fashion"))
    }

    @Test
    fun matches_usesProductTagsAndKeywordsForLegacyProducts() {
        val game = fakeProduct(
            category = "misc",
            title = "Ping Pong Travel Game",
            tags = listOf("jeux", "toy"),
            searchKeywords = listOf("game", "games", "jeux")
        )
        val showerGel = fakeProduct(
            category = "misc",
            title = "Gel douche naturel",
            tags = listOf("cosmetics", "skincare"),
            searchKeywords = listOf("gel", "douche", "beauty", "cosmetics")
        )

        assertTrue(MarketplaceCategories.matches(game, "baby-and-toys"))
        assertTrue(MarketplaceCategories.matches(showerGel, "beauty-and-health"))
        assertFalse(MarketplaceCategories.matches(showerGel, "baby-and-toys"))
    }

    private fun fakeProduct(
        category: String,
        title: String = "Product title",
        categoryIds: List<String> = emptyList(),
        categoryLeafId: String = categoryIds.lastOrNull() ?: category,
        tags: List<String> = emptyList(),
        searchKeywords: List<String> = emptyList()
    ): Product {
        return Product(
            id = title.lowercase().replace(" ", "-"),
            title = title,
            subtitle = "Seller item",
            price = 10.0,
            rating = 4.5,
            reviewsCount = 3,
            tags = tags,
            description = "A product used for category matching tests.",
            bullets = emptyList(),
            imageRes = 1,
            category = category,
            categoryIds = categoryIds,
            categoryLeafId = categoryLeafId,
            stock = 5,
            searchKeywords = searchKeywords
        )
    }
}
