package isim.ia2y.myapplication

import androidx.annotation.DrawableRes
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import java.text.Normalizer
import java.util.Locale

data class MarketplaceCategory(
    val id: String,
    val name: String,
    val slug: String,
    val icon: String,
    val imageUrl: String,
    val parentCategory: String?,
    val ancestorIds: List<String>,
    val level: Int,
    val featured: Boolean,
    val sortOrder: Int,
    val searchKeywords: List<String>,
    val listingTypes: List<String>,
    val isActive: Boolean = true
) {
    val key: String get() = id
    val topLevelId: String get() = ancestorIds.firstOrNull() ?: id
}

object MarketplaceCategories {
    private const val COLLECTION = "marketplaceCategories"
    private const val REFRESH_TTL_MS = 24L * 60 * 60 * 1000
    private val locale: Locale get() = Locale.getDefault()
    private val fallbackItems: List<MarketplaceCategory> by lazy { buildFallbackItems() }
    @Volatile private var remoteItems: List<MarketplaceCategory>? = null
    @Volatile private var cachedSnapshot: CategorySnapshot? = null
    @Volatile private var lastServerRefreshAt: Long = 0L

    val items: List<MarketplaceCategory>
        get() = snapshot().topLevel

    val featuredItems: List<MarketplaceCategory>
        get() = snapshot().featured

    suspend fun refreshFromFirestore() {
        if (remoteItems != null && System.currentTimeMillis() - lastServerRefreshAt < REFRESH_TTL_MS) return

        val db = FirebaseFirestore.getInstance()
        val cachedSnapshot = runCatching {
            db
                .collection(COLLECTION)
                .whereEqualTo("isActive", true)
                .get(Source.CACHE)
                .await()
        }.getOrNull()
        if (cachedSnapshot != null && !cachedSnapshot.isEmpty) {
            FirebaseCostTracker.read("MarketplaceCategories.refreshFromFirestore", COLLECTION, cachedSnapshot.size(), Source.CACHE.name)
            applyRemoteSnapshot(cachedSnapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                categoryFromMap(doc.id, data)
            })
            if (FirebaseCostSafeMode.enabled) return
        }

        if (FirebaseCostSafeMode.enabled) return

        val snapshot = db
            .collection(COLLECTION)
            .whereEqualTo("isActive", true)
            .get(Source.SERVER)
            .await()
        FirebaseCostTracker.read("MarketplaceCategories.refreshFromFirestore", COLLECTION, snapshot.size(), Source.SERVER.name)

        applyRemoteSnapshot(snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            categoryFromMap(doc.id, data)
        })
        lastServerRefreshAt = System.currentTimeMillis()
    }

    private fun applyRemoteSnapshot(loaded: List<MarketplaceCategory>) {
        if (loaded.any { it.level == 0 }) {
            remoteItems = loaded.sortedWith(compareBy<MarketplaceCategory> { it.level }.thenBy { it.sortOrder })
            cachedSnapshot = null
        }
    }

    fun activeItems(): List<MarketplaceCategory> =
        snapshot().active

    fun childrenOf(parentId: String?): List<MarketplaceCategory> {
        val normalizedParent = parentId?.let(::normalizeKey)?.takeIf { it.isNotBlank() }
        return snapshot().childrenByParent[normalizedParent].orEmpty()
    }

    fun descendantsOf(parentId: String): List<MarketplaceCategory> {
        val normalized = normalizeKey(parentId)
        return snapshot().descendantsByParent[normalized].orEmpty()
    }

    fun categoryFor(key: String?): MarketplaceCategory? {
        val normalized = normalizeKey(key)
        if (normalized.isBlank()) return null
        return snapshot().byLookup[normalized]
    }

    fun displayNameFor(key: String?): String {
        val raw = key.orEmpty().trim()
        return categoryFor(raw)?.name ?: raw.ifBlank { "Other" }.humanizeCategory()
    }

    fun normalizeKey(key: String?): String {
        val normalized = slugify(key.orEmpty())
        if (normalized.isBlank()) return items.firstOrNull()?.id ?: "electronics"
        return snapshot().byLookup[normalized]?.id ?: normalized
    }

    fun matches(product: Product, key: String): Boolean {
        val normalized = normalizeKey(key)
        if (normalized == "all") return true

        val acceptedKeys = snapshot().acceptedProductKeys[normalized] ?: setOf(slugify(normalized))

        val productKeys = buildSet {
            add(slugify(product.category))
            product.categoryIds.forEach { add(slugify(it)) }
        }
        return productKeys.any { it in acceptedKeys }
    }

    @DrawableRes
    fun imageResFor(categoryKey: String?): Int {
        return when (categoryFor(categoryKey)?.topLevelId ?: normalizeKey(categoryKey)) {
            "automotive" -> R.drawable.category_automotive
            "baby-and-toys" -> R.drawable.category_baby_toys
            "beauty-and-health" -> R.drawable.category_beauty_health
            "business-and-industrial" -> R.drawable.category_business_industrial
            "digital-products" -> R.drawable.category_digital_products
            "electronics" -> R.drawable.category_electronics
            "fashion" -> R.drawable.category_fashion
            "food-and-grocery" -> R.drawable.category_food_grocery
            "home-and-furniture" -> R.drawable.category_home_furniture
            "jobs-and-services" -> R.drawable.category_jobs_services
            "pets" -> R.drawable.category_pets
            "real-estate" -> R.drawable.category_real_estate
            "sports-and-outdoors" -> R.drawable.category_sports_outdoors
            "books-and-media" -> R.drawable.category_books_media
            "collectibles-and-hobbies" -> R.drawable.category_collectibles_hobbies
            else -> R.drawable.img_explore_artisanat
        }
    }

    fun searchMatches(category: MarketplaceCategory, query: String): Boolean {
        val normalized = slugify(query)
        if (normalized.isBlank()) return true
        return slugify(category.name).contains(normalized) ||
            category.searchKeywords.any { slugify(it).contains(normalized) }
    }

    fun childCountOf(parentId: String): Int =
        snapshot().childrenByParent[normalizeKey(parentId)].orEmpty().size

    fun topLevelCategoryIdsForProduct(category: String, categoryIds: List<String>): List<String> {
        val ids = (listOf(category) + categoryIds).map(::normalizeKey).filter { it.isNotBlank() }
        return ids.mapNotNull { key -> categoryFor(key)?.topLevelId ?: categoryFor(ids.firstOrNull())?.topLevelId ?: key }
            .distinct()
    }

    private fun snapshot(): CategorySnapshot {
        val source = remoteItems ?: fallbackItems
        val cached = cachedSnapshot
        if (cached != null && cached.source === source) return cached

        val active = source.filter { it.isActive }.sortedWith(
            compareBy<MarketplaceCategory> { it.level }.thenBy { it.sortOrder }.thenBy { it.name }
        )
        val lookup = mutableMapOf<String, MarketplaceCategory>()
        active.forEach { category ->
            lookup[category.id] = category
            lookup[category.slug] = category
            lookup[slugify(category.name)] = category
        }
        val children = active.groupBy { it.parentCategory }
            .mapValues { (_, values) -> values.sortedBy { it.sortOrder } }
        val descendants = active
            .flatMap { category -> category.ancestorIds.map { parent -> parent to category } }
            .groupBy({ it.first }, { it.second })
        val accepted = active.associate { category ->
            val keys = (listOf(category) + descendants[category.id].orEmpty())
                .flatMap { listOf(it.id, it.slug) }
                .map(::slugify)
                .toSet()
            category.id to keys
        }
        return CategorySnapshot(
            source = source,
            active = active,
            topLevel = active.filter { it.level == 0 },
            featured = active.filter { it.level == 0 && it.featured }.sortedBy { it.sortOrder },
            byLookup = lookup,
            childrenByParent = children,
            descendantsByParent = descendants,
            acceptedProductKeys = accepted
        ).also { cachedSnapshot = it }
    }

    private fun categoryFromMap(id: String, data: Map<String, Any>): MarketplaceCategory {
        val parent = data["parentCategory"] as? String
        return MarketplaceCategory(
            id = (data["id"] as? String)?.ifBlank { id } ?: id,
            name = data["name"] as? String ?: id.humanizeCategory(),
            slug = data["slug"] as? String ?: id,
            icon = data["icon"] as? String ?: "category",
            imageUrl = data["imageUrl"] as? String ?: "",
            parentCategory = parent?.takeIf { it.isNotBlank() },
            ancestorIds = (data["ancestorIds"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            level = (data["level"] as? Number)?.toInt() ?: if (parent == null) 0 else 1,
            featured = data["featured"] as? Boolean ?: false,
            sortOrder = (data["sortOrder"] as? Number)?.toInt() ?: 999,
            searchKeywords = (data["searchKeywords"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            listingTypes = (data["listingTypes"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            isActive = data["isActive"] as? Boolean ?: true
        )
    }

    private fun buildFallbackItems(): List<MarketplaceCategory> {
        val topCategories = listOf(
            TopSeed("Electronics", "smartphone", true, listOf("product")),
            TopSeed("Fashion", "shirt", true, listOf("product")),
            TopSeed("Home & Furniture", "sofa", true, listOf("product", "real_estate")),
            TopSeed("Beauty & Health", "sparkles", true, listOf("product", "service")),
            TopSeed("Sports & Outdoors", "dumbbell", false, listOf("product")),
            TopSeed("Automotive", "car", true, listOf("vehicle", "product", "service")),
            TopSeed("Real Estate", "home", true, listOf("real_estate")),
            TopSeed("Jobs & Services", "briefcase", true, listOf("job", "service")),
            TopSeed("Baby & Toys", "baby", false, listOf("product")),
            TopSeed("Books & Media", "book-open", false, listOf("product", "digital")),
            TopSeed("Food & Grocery", "shopping-basket", true, listOf("product")),
            TopSeed("Pets", "paw-print", false, listOf("product", "service")),
            TopSeed("Business & Industrial", "factory", false, listOf("product", "service")),
            TopSeed("Digital Products", "download-cloud", false, listOf("digital", "service")),
            TopSeed("Collectibles & Hobbies", "gem", false, listOf("product"))
        )

        val categories = mutableListOf<MarketplaceCategory>()
        topCategories.forEachIndexed { index, seed ->
            val topId = slugify(seed.name)
            categories += MarketplaceCategory(
                id = topId,
                name = seed.name,
                slug = topId,
                icon = seed.icon,
                imageUrl = placeholderUrl(seed.name),
                parentCategory = null,
                ancestorIds = emptyList(),
                level = 0,
                featured = seed.featured,
                sortOrder = (index + 1) * 100,
                searchKeywords = keywordsFor(seed.name),
                listingTypes = seed.listingTypes
            )

            subcategoryNames.getValue(seed.name).forEachIndexed { subIndex, subName ->
                val subId = "${topId}__${slugify(subName)}"
                categories += MarketplaceCategory(
                    id = subId,
                    name = subName,
                    slug = slugify(subName),
                    icon = seed.icon,
                    imageUrl = placeholderUrl(subName),
                    parentCategory = topId,
                    ancestorIds = listOf(topId),
                    level = 1,
                    featured = false,
                    sortOrder = (index + 1) * 100 + subIndex + 1,
                    searchKeywords = keywordsFor(seed.name, subName),
                    listingTypes = seed.listingTypes
                )

                childNames[subId].orEmpty().forEachIndexed { childIndex, childName ->
                    categories += MarketplaceCategory(
                        id = "${subId}__${slugify(childName)}",
                        name = childName,
                        slug = slugify(childName),
                        icon = seed.icon,
                        imageUrl = placeholderUrl(childName),
                        parentCategory = subId,
                        ancestorIds = listOf(topId, subId),
                        level = 2,
                        featured = false,
                        sortOrder = (index + 1) * 100 + subIndex * 10 + childIndex + 1,
                        searchKeywords = keywordsFor(seed.name, subName, childName),
                        listingTypes = seed.listingTypes
                    )
                }
            }
        }
        return categories
    }

    private data class TopSeed(
        val name: String,
        val icon: String,
        val featured: Boolean,
        val listingTypes: List<String>
    )

    private data class CategorySnapshot(
        val source: List<MarketplaceCategory>,
        val active: List<MarketplaceCategory>,
        val topLevel: List<MarketplaceCategory>,
        val featured: List<MarketplaceCategory>,
        val byLookup: Map<String, MarketplaceCategory>,
        val childrenByParent: Map<String?, List<MarketplaceCategory>>,
        val descendantsByParent: Map<String, List<MarketplaceCategory>>,
        val acceptedProductKeys: Map<String, Set<String>>
    )

    private val subcategoryNames = mapOf(
        "Electronics" to listOf("Phones & Accessories", "Computers & Tablets", "TVs & Home Theater", "Cameras & Photography", "Audio & Headphones", "Gaming Consoles", "Wearables", "Smart Home", "Networking", "Drones", "Printers & Scanners", "Electronics Parts"),
        "Fashion" to listOf("Women's Clothing", "Men's Clothing", "Shoes", "Bags & Luggage", "Jewelry & Watches", "Accessories", "Traditional Wear", "Kids Clothing", "Activewear", "Formal Wear", "Maternity", "Vintage Fashion"),
        "Home & Furniture" to listOf("Sofas & Seating", "Beds & Mattresses", "Tables & Desks", "Storage & Shelving", "Kitchen & Dining", "Home Decor", "Lighting", "Garden & Patio", "Appliances", "Bedding & Bath", "Tools & DIY", "Office Furniture"),
        "Beauty & Health" to listOf("Skincare", "Haircare", "Makeup", "Fragrance", "Bath & Body", "Personal Care Devices", "Wellness Supplements", "Medical Supplies", "Fitness Recovery", "Men's Grooming", "Natural & Organic", "Beauty Services"),
        "Sports & Outdoors" to listOf("Exercise & Fitness", "Team Sports", "Camping & Hiking", "Cycling", "Fishing", "Water Sports", "Outdoor Recreation", "Sportswear", "Golf", "Running", "Yoga & Pilates", "Outdoor Gear"),
        "Automotive" to listOf("Cars", "Motorcycles", "Auto Parts", "Tires & Wheels", "Car Electronics", "Tools & Garage", "Oils & Fluids", "Car Care", "Commercial Vehicles", "Boats & Marine", "Vehicle Services", "Rentals"),
        "Real Estate" to listOf("Apartments for Rent", "Apartments for Sale", "Houses for Rent", "Houses for Sale", "Land", "Commercial Property", "Vacation Rentals", "Shared Rooms", "Garages & Parking", "Offices & Coworking", "Property Services", "New Developments"),
        "Jobs & Services" to listOf("Full-Time Jobs", "Part-Time Jobs", "Freelance", "Home Services", "Repair Services", "Delivery & Moving", "Education & Tutoring", "Events & Catering", "Design & Creative", "Tech Services", "Legal & Finance", "Cleaning", "Beauty Services"),
        "Baby & Toys" to listOf("Strollers", "Car Seats", "Baby Clothing", "Nursery Furniture", "Feeding", "Diapers & Care", "Toys", "Games & Puzzles", "Outdoor Toys", "School Supplies", "Maternity", "Baby Safety"),
        "Books & Media" to listOf("Books", "Textbooks", "Comics & Manga", "Magazines", "Movies", "Music", "Video Games", "Instruments", "Collectible Media", "Ebooks", "Audiobooks", "Board Games"),
        "Food & Grocery" to listOf("Fresh Produce", "Meat & Seafood", "Bakery", "Dairy & Eggs", "Pantry Staples", "Beverages", "Snacks", "Organic Food", "Prepared Meals", "Tunisian Specialties", "Spices & Condiments", "Wholesale Grocery"),
        "Pets" to listOf("Dogs", "Cats", "Birds", "Fish & Aquariums", "Small Pets", "Pet Food", "Pet Accessories", "Pet Grooming", "Pet Services", "Pet Adoption", "Veterinary Supplies", "Pet Housing"),
        "Business & Industrial" to listOf("Office Supplies", "Restaurant Equipment", "Industrial Tools", "Construction Materials", "Agriculture", "Medical Equipment", "Packaging & Shipping", "Retail Fixtures", "Safety Equipment", "Manufacturing Equipment", "Cleaning Supplies", "Wholesale Lots"),
        "Digital Products" to listOf("Software", "Templates", "Graphics & Design", "Ebooks & Courses", "Digital Art", "Music & Audio", "Stock Photos", "Website Themes", "Plugins & Extensions", "Licenses & Keys", "Online Services", "Game Assets"),
        "Collectibles & Hobbies" to listOf("Antiques", "Coins & Currency", "Stamps", "Trading Cards", "Art Collectibles", "Handmade Crafts", "Model Kits", "RC & Drones", "Sewing & Crafts", "Musical Hobbies", "Memorabilia", "Vintage Items", "Board & Tabletop Games")
    )

    private val childNames = mapOf(
        "electronics__phones-accessories" to listOf("Smartphones", "Cases", "Chargers", "Screen Protectors"),
        "fashion__shoes" to listOf("Sneakers", "Boots", "Sandals", "Formal Shoes"),
        "automotive__cars" to listOf("Sedans", "SUVs", "Trucks", "Electric & Hybrid"),
        "real-estate__apartments-for-rent" to listOf("Studio", "S+1", "S+2", "S+3+"),
        "jobs-services__home-services" to listOf("Plumbing", "Electrical", "Painting", "Cleaning"),
        "food-and-grocery__tunisian-specialties" to listOf("Harissa", "Olive Oil", "Dates", "Traditional Sweets"),
        "business-and-industrial__agriculture" to listOf("Seeds", "Irrigation", "Farm Equipment"),
        "digital-products__templates" to listOf("Resume Templates", "Social Media Templates", "Business Templates")
    )

    private fun placeholderUrl(name: String): String =
        "https://placehold.co/640x420/F7F9FA/74613F?text=${slugify(name).replace("-", "+")}"

    private fun keywordsFor(vararg values: String): List<String> {
        return values.flatMap { value ->
            value.lowercase(Locale.US)
                .replace("&", " ")
                .replace("+", " plus ")
                .split(" ", "-", "/", "_")
        }.map { it.trim('\'', ',', '.') }
            .filter { it.length >= 3 }
            .distinct()
    }

    private fun slugify(value: String): String {
        val normalized = Normalizer.normalize(value.trim().lowercase(Locale.US), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .replace("&", " and ")
            .replace("+", " plus ")
            .replace("[^a-z0-9]+".toRegex(), "-")
            .trim('-')
        return normalized
    }

    private fun String.humanizeCategory(): String =
        replace('_', ' ')
            .replace('-', ' ')
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase(locale) else it.toString()
                }
            }
}
