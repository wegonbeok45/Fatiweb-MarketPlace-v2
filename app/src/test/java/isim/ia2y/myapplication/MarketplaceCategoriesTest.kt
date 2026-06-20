package isim.ia2y.myapplication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceCategoriesTest {

    @Test
    fun normalizeKey_keepsTopLevelCategoriesStable() {
        assertEquals("electronics", MarketplaceCategories.normalizeKey("electronics"))
        assertEquals("baby-and-toys", MarketplaceCategories.normalizeKey("baby-and-toys"))
        assertEquals("fashion", MarketplaceCategories.normalizeKey("fashion"))
        assertEquals("electronics", MarketplaceCategories.categoryFor("electronics")?.id)
    }

    @Test
    fun queryKeysForCategory_includesDisplayNamesAliasesAndLeafCategories() {
        val electronicsKeys = MarketplaceCategories.queryKeysForCategory("electronics")
        val toyKeys = MarketplaceCategories.queryKeysForCategory("baby-and-toys")

        assertTrue("Electronics display name should be queried", "Electronics" in electronicsKeys)
        assertTrue("Electronics alias should be queried", "electronique" in electronicsKeys)
        assertTrue("Electronics leaf id should be queried", "electronics__phones-accessories" in electronicsKeys)
        assertTrue("Toy French search alias should be queried", "jeux" in toyKeys)
        assertTrue("Toy leaf id should be queried", "baby-and-toys__games-puzzles" in toyKeys)
    }

    @Test
    fun matches_acceptsTopLevelLabelsAndLocalizedAliases() {
        assertTrue(MarketplaceCategories.matches(fakeProduct(category = "Electronique"), "electronics"))
        assertTrue(MarketplaceCategories.matches(fakeProduct(category = "electronics"), "Electronique"))
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

    @Test
    fun browsingMatches_usesCategoryFallbackSearchWhenStoredCategoryIsGeneric() {
        val charger = fakeProduct(
            category = "misc",
            title = "Chargeur rapide smartphone",
            tags = listOf("mobile")
        )
        val pyjama = fakeProduct(
            category = "misc",
            title = "Pyjama coton confortable",
            tags = listOf("vetement")
        )
        val game = fakeProduct(
            category = "misc",
            title = "Ping Pong Travel Game",
            tags = listOf("jeux")
        )
        val showerGel = fakeProduct(
            category = "misc",
            title = "Gel douche naturel",
            tags = listOf("cosmetique")
        )

        assertTrue(MarketplaceCategories.browsingMatches(charger, "electronics"))
        assertTrue(MarketplaceCategories.browsingMatches(pyjama, "fashion"))
        assertTrue(MarketplaceCategories.browsingMatches(game, "baby-and-toys"))
        assertTrue(MarketplaceCategories.browsingMatches(showerGel, "beauty-and-health"))
        assertFalse(MarketplaceCategories.browsingMatches(showerGel, "baby-and-toys"))
    }

    @Test
    fun browsingMatches_doesNotLeakGenericChildWordsAcrossTopCategories() {
        val electronicsAccessory = fakeProduct(
            category = "electronics",
            title = "Bundle tapis de souris",
            searchKeywords = listOf("accessories", "mouse", "electronics")
        )
        val kidsBath = fakeProduct(
            category = "beauty-and-health",
            title = "ANIAN Gel de Bain Kids",
            searchKeywords = listOf("kids", "gel", "beauty")
        )

        assertTrue(MarketplaceCategories.browsingMatches(electronicsAccessory, "electronics"))
        assertTrue(MarketplaceCategories.browsingMatches(kidsBath, "beauty-and-health"))
        assertFalse(MarketplaceCategories.browsingMatches(electronicsAccessory, "fashion"))
        assertFalse(MarketplaceCategories.browsingMatches(kidsBath, "fashion"))
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
