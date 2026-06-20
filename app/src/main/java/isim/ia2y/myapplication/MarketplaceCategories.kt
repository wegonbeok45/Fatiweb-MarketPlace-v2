package isim.ia2y.myapplication

import androidx.annotation.DrawableRes
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

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
    private val slugCache = ConcurrentHashMap<String, String>()
    private val combiningMarksRegex = "\\p{InCombiningDiacriticalMarks}+".toRegex()
    private val nonAlphaNumericRegex = "[^a-z0-9]+".toRegex()
    private val aliasLookup: Map<String, Set<String>> by lazy {
        buildMap {
            categoryAliases.forEach { (canonicalKey, aliases) ->
                val related = (aliases + canonicalKey).map(::slugify).filter { it.isNotBlank() }.toSet()
                related.forEach { alias ->
                    put(alias, get(alias).orEmpty() + related)
                }
            }
        }
    }
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
        return categoryFor(raw)?.let(::displayNameFor)
            ?: localizedCategoryName(raw)
            ?: raw.ifBlank { fallbackOtherName() }.humanizeCategory()
    }

    fun displayNameFor(category: MarketplaceCategory): String {
        return localizedCategoryName(category.id)
            ?: localizedCategoryName(category.slug)
            ?: localizedCategoryName(category.name)
            ?: category.name
    }

    fun displayNameForKnownKey(key: String): String {
        val raw = key.trim()
        return localizedCategoryName(raw)
            ?: raw.ifBlank { fallbackOtherName() }.humanizeCategory()
    }

    fun normalizeKey(key: String?): String {
        val normalized = slugify(key.orEmpty())
        if (normalized.isBlank()) return items.firstOrNull()?.id ?: "electronics"
        return snapshot().byLookup[normalized]?.id ?: normalized
    }

    fun matches(product: Product, key: String): Boolean {
        val categorySnapshot = snapshot()
        val normalized = normalizeKey(key)
        if (normalized == "all") return true

        val acceptedKeys = acceptedKeysFor(categorySnapshot, normalized)
        if (acceptedKeys.isEmpty()) return false

        val productKeys = productCategoryMatchKeys(product, categorySnapshot)
        return productKeys.any { it in acceptedKeys }
    }

    fun structuredMatches(product: Product, key: String): Boolean {
        val categorySnapshot = snapshot()
        val normalized = normalizeKey(key)
        if (normalized == "all") return true

        val acceptedKeys = acceptedKeysFor(categorySnapshot, normalized)
        if (acceptedKeys.isEmpty()) return false

        val productKeys = structuredProductCategoryMatchKeys(product, categorySnapshot)
        return productKeys.any { it in acceptedKeys }
    }

    fun browsingMatches(product: Product, key: String): Boolean {
        val normalized = normalizeKey(key)
        if (structuredMatches(product, normalized)) return true

        val categoryKeys = categorySignalKeys(categoryFallbackSearchText(normalized))
        if (categoryKeys.isEmpty()) return false
        val productKeys = categorySignalKeys(
            listOf(
                product.title,
                product.subtitle,
                product.description,
                product.productType,
                product.origin,
                product.tags.joinToString(" "),
                product.searchKeywords.joinToString(" "),
                product.bullets.joinToString(" ")
            ).joinToString(" ")
        )
        return productKeys.any { it in categoryKeys }
    }

    fun queryKeysForCategory(key: String): List<String> {
        val categorySnapshot = snapshot()
        val normalized = normalizeKey(key)
        if (normalized.isBlank() || normalized == "all") return emptyList()
        val selected = categorySnapshot.byLookup[slugify(normalized)] ?: categorySnapshot.byLookup[normalized]
        val topLevelId = selected?.topLevelId ?: normalized
        val categories = buildList {
            selected?.let(::add)
            categorySnapshot.byLookup[topLevelId]?.let { topLevel ->
                if (none { it.id == topLevel.id }) add(topLevel)
            }
            categorySnapshot.descendantsByParent[topLevelId].orEmpty().forEach { descendant ->
                if (none { it.id == descendant.id }) add(descendant)
            }
        }
        return buildList {
            fun addKey(value: String?) {
                fun addUnique(key: String) {
                    if (key.isNotBlank() && key !in this) add(key)
                }

                fun addAndCompatibility(key: String) {
                    addUnique(key.replace("-and-", "-"))
                    val leafSeparator = key.indexOf("__")
                    if (leafSeparator >= 0) {
                        val prefix = key.substring(0, leafSeparator + 2)
                        val leaf = key.substring(leafSeparator + 2)
                        addUnique(prefix + leaf.replace("-and-", "-"))
                    }
                }

                val raw = value.orEmpty().trim()
                if (raw.isBlank()) return
                addUnique(raw)
                addAndCompatibility(raw)
                val slug = slugify(raw)
                addUnique(slug)
                addAndCompatibility(slug)
            }

            addKey(normalized)
            addKey(topLevelId)
            categories.forEach { category ->
                addKey(category.id)
                addKey(category.slug)
            }
            categories.forEach { category ->
                addKey(category.name)
                addKey(displayNameFor(category))
                addKey(displayNameForKnownKey(category.id))
                category.searchKeywords.forEach(::addKey)
                category.aliasKeys().forEach(::addKey)
            }
        }
    }

    @DrawableRes
    fun imageResFor(categoryKey: String?): Int {
        val directKey = slugify(categoryKey.orEmpty())
        val imageKey = when (directKey) {
            "automotive",
            "baby-and-toys",
            "beauty-and-health",
            "business-and-industrial",
            "digital-products",
            "electronics",
            "fashion",
            "food-and-grocery",
            "home-and-furniture",
            "jobs-and-services",
            "pets",
            "real-estate",
            "sports-and-outdoors",
            "books-and-media",
            "collectibles-and-hobbies" -> directKey
            else -> categoryFor(categoryKey)?.topLevelId ?: normalizeKey(categoryKey)
        }
        return when (imageKey) {
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
            slugify(displayNameFor(category)).contains(normalized) ||
            category.aliasKeys().any { it.contains(normalized) } ||
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
            lookup.putIfAbsent(category.slug, category)
            lookup.putIfAbsent(slugify(category.name), category)
            lookup.putIfAbsent(slugify(displayNameFor(category)), category)
            category.searchKeywords.forEach { keyword ->
                lookup.putIfAbsent(slugify(keyword), category)
            }
            category.aliasKeys().forEach { alias ->
                lookup.putIfAbsent(slugify(alias), category)
            }
        }
        val children = active.groupBy { it.parentCategory }
            .mapValues { (_, values) -> values.sortedBy { it.sortOrder } }
        val descendants = active
            .flatMap { category -> category.ancestorIds.map { parent -> parent to category } }
            .groupBy({ it.first }, { it.second })
        val accepted = lazy(LazyThreadSafetyMode.PUBLICATION) {
            active.associate { category ->
                val keys = (listOf(category) + descendants[category.id].orEmpty())
                    .flatMap { item ->
                        listOf(item.id, item.slug, item.name, displayNameFor(item)) +
                            item.searchKeywords +
                            item.aliasKeys()
                    }
                    .flatMap(::categorySignalKeys)
                    .toSet()
                category.id to keys
            }
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
        val acceptedProductKeys: Lazy<Map<String, Set<String>>>
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

    private val frenchCategoryNames = mapOf(
        "all" to "Toutes",
        "other" to "Autre",
        "automotive" to "Auto et moto",
        "baby-and-toys" to "Bebe et jouets",
        "beauty-and-health" to "Beaute et sante",
        "books-and-media" to "Livres et medias",
        "business-and-industrial" to "Pro et industrie",
        "collectibles-and-hobbies" to "Collections et loisirs",
        "digital-products" to "Produits numeriques",
        "electronics" to "Electronique",
        "fashion" to "Mode",
        "food-and-grocery" to "Alimentation",
        "home-and-furniture" to "Maison et meubles",
        "jobs-and-services" to "Services et emplois",
        "pets" to "Animaux",
        "real-estate" to "Immobilier",
        "sports-and-outdoors" to "Sport et plein air",
        "phones-accessories" to "Telephones et accessoires",
        "computers-tablets" to "Ordinateurs et tablettes",
        "tvs-home-theater" to "TV et home cinema",
        "cameras-photography" to "Appareils photo",
        "audio-headphones" to "Audio et casques",
        "gaming-consoles" to "Consoles et jeux",
        "wearables" to "Objets connectes",
        "smart-home" to "Maison connectee",
        "video-games" to "Jeux video",
        "games-puzzles" to "Jeux et puzzles",
        "toys" to "Jouets",
        "game-assets" to "Assets de jeux",
        "board-games" to "Jeux de societe",
        "board-tabletop-games" to "Jeux de plateau"
    )

    private val englishCategoryNames = mapOf(
        "all" to "All",
        "other" to "Other",
        "automotive" to "Automotive",
        "baby-and-toys" to "Baby & Toys",
        "beauty-and-health" to "Beauty & Health",
        "books-and-media" to "Books & Media",
        "business-and-industrial" to "Business & Industrial",
        "collectibles-and-hobbies" to "Collectibles & Hobbies",
        "digital-products" to "Digital Products",
        "electronics" to "Electronics",
        "fashion" to "Fashion",
        "food-and-grocery" to "Food & Grocery",
        "home-and-furniture" to "Home & Furniture",
        "jobs-and-services" to "Jobs & Services",
        "pets" to "Pets",
        "real-estate" to "Real Estate",
        "sports-and-outdoors" to "Sports & Outdoors"
    )

    private val categoryAliases = mapOf(
        "electronics" to listOf("electronique", "electro", "electronics", "electronic", "electornics", "electorincs", "tech", "phone", "phones", "telephone", "smartphone", "smartphones", "computer", "ordinateur", "laptop", "chargeur", "charger", "camera", "audio", "headphones", "casque"),
        "baby-and-toys" to listOf("bebe", "baby", "toys", "toy", "jouet", "jouets", "jeux", "games", "game", "enfant", "kids"),
        "books-and-media" to listOf("books", "book", "livres", "livre", "media", "medias", "video games", "jeux video", "manga", "music"),
        "digital-products" to listOf("digital", "numerique", "game assets", "assets de jeux", "software", "template", "ebook"),
        "beauty-and-health" to listOf("beaute", "beauty", "sante", "health", "cosmetique", "cosmetics", "skincare", "soin", "gel douche", "parfum"),
        "fashion" to listOf("mode", "fashion", "vetements", "vetement", "clothes", "clothing", "pyjama", "chaussures", "shoes", "bag", "sac"),
        "home-and-furniture" to listOf("maison", "furniture", "meubles", "meuble", "home", "decor", "deco", "decoration", "lamp", "table"),
        "food-and-grocery" to listOf("food", "grocery", "groceries", "alimentation", "alimentaire", "epicerie", "snack", "boisson"),
        "sports-and-outdoors" to listOf("sport", "sports", "outdoor", "outdoors", "plein air", "fitness", "gym"),
        "automotive" to listOf("auto", "automotive", "car", "cars", "voiture", "vehicle", "vehicule", "moto"),
        "real-estate" to listOf("immobilier", "real estate", "maison", "appartement", "apartment", "rent", "sale"),
        "jobs-and-services" to listOf("services", "service", "jobs", "job", "emplois", "emploi", "repair", "reparation"),
        "collectibles-and-hobbies" to listOf("collectibles", "collection", "loisirs", "hobbies", "craft", "crafts", "artisanat", "handmade", "fait main")
    )

    private val categorySignalStopWords = setOf(
        "and", "or", "the", "for", "with", "from", "plus",
        "de", "des", "du", "la", "le", "les", "et", "en", "au", "aux",
        "all", "other", "product", "products", "produit", "produits"
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

    private fun MarketplaceCategory.aliasKeys(): List<String> {
        val normalizedKeys = listOf(id, slug, name, displayNameFor(this)).map(::slugify).toSet()
        return normalizedKeys.flatMap(::aliasesForKey).distinct()
    }

    private fun acceptedKeysFor(snapshot: CategorySnapshot, normalized: String): Set<String> {
        val selected = snapshot.byLookup[slugify(normalized)] ?: snapshot.byLookup[normalized]
        val acceptedProductKeys = snapshot.acceptedProductKeys.value
        return buildSet {
            addAll(acceptedProductKeys[normalized].orEmpty())
            addAll(categorySignalKeys(normalized))
            aliasesForKey(normalized).forEach { addAll(categorySignalKeys(it)) }
            selected?.let { category ->
                addAll(acceptedProductKeys[category.id].orEmpty())
                addAll(acceptedProductKeys[category.topLevelId].orEmpty())
                addAll(categorySignalKeys(category.id))
                addAll(categorySignalKeys(category.slug))
                addAll(categorySignalKeys(category.name))
                addAll(categorySignalKeys(displayNameFor(category)))
                category.aliasKeys().forEach { addAll(categorySignalKeys(it)) }
            }
        }
    }

    private fun categoryFallbackSearchText(key: String): String {
        val categorySnapshot = snapshot()
        val normalized = normalizeKey(key)
        val selected = categorySnapshot.byLookup[slugify(normalized)] ?: categorySnapshot.byLookup[normalized]
        return buildSet {
            add(key)
            add(normalized)
            add(displayNameForKnownKey(normalized))
            aliasesForKey(normalized).forEach(::add)
            selected?.let { category ->
                add(category.id)
                add(category.slug)
                add(category.name)
                add(displayNameFor(category))
                add(category.topLevelId)
                category.searchKeywords.forEach(::add)
                category.aliasKeys().forEach(::add)
            }
        }.joinToString(" ")
    }

    private fun productCategoryMatchKeys(product: Product, snapshot: CategorySnapshot): Set<String> {
        return structuredProductCategoryMatchKeys(product, snapshot) + descriptiveProductCategoryMatchKeys(product)
    }

    private fun structuredProductCategoryMatchKeys(product: Product, snapshot: CategorySnapshot): Set<String> {
        val structuredValues = listOf(product.category, product.categoryLeafId) + product.categoryIds
        return buildSet {
            structuredValues.forEach { value ->
                addAll(categoryValueKeys(value, snapshot))
                addAll(categorySignalKeys(value))
            }
        }
    }

    private fun descriptiveProductCategoryMatchKeys(product: Product): Set<String> {
        val descriptiveValues = product.tags + product.searchKeywords + listOf(
            product.productType,
            product.title,
            product.subtitle,
            product.description,
            product.bullets.joinToString(" "),
            product.origin
        )
        return buildSet {
            descriptiveValues.forEach { value ->
                addAll(categorySignalKeys(value))
            }
        }
    }

    private fun aliasesForKey(key: String): List<String> {
        val normalized = slugify(key)
        if (normalized.isBlank()) return emptyList()
        return aliasLookup[normalized].orEmpty().toList()
    }

    private fun categoryValueKeys(value: String, snapshot: CategorySnapshot): Set<String> {
        val slug = slugify(value)
        val category = snapshot.byLookup[slug] ?: snapshot.byLookup[value]
        return buildSet {
            addAll(categorySignalKeys(value))
            category?.let {
                addAll(categorySignalKeys(it.id))
                addAll(categorySignalKeys(it.slug))
                addAll(categorySignalKeys(it.name))
                addAll(categorySignalKeys(displayNameFor(it)))
                addAll(categorySignalKeys(it.topLevelId))
                it.aliasKeys().forEach { alias -> addAll(categorySignalKeys(alias)) }
            }
        }
    }

    private fun categorySignalKeys(value: String): Set<String> {
        val slug = slugify(value)
        if (slug.isBlank()) return emptySet()
        return buildSet {
            add(slug)
            slug.split("-")
                .asSequence()
                .map { it.trim() }
                .filter { it.length >= 2 && it !in categorySignalStopWords }
                .forEach(::add)
        }
    }

    private fun localizedCategoryName(value: String?): String? {
        val key = slugify(value.orEmpty())
        if (key.isBlank()) return null
        val names = if (locale.language.equals("fr", ignoreCase = true)) {
            frenchCategoryNames
        } else {
            englishCategoryNames
        }
        return names[key]
    }

    private fun fallbackOtherName(): String =
        if (locale.language.equals("fr", ignoreCase = true)) "Autre" else "Other"

    private fun slugify(value: String): String {
        val cacheKey = value.trim().lowercase(Locale.US)
        if (cacheKey.isBlank()) return ""
        return slugCache[cacheKey] ?: run {
            val normalized = Normalizer.normalize(cacheKey, Normalizer.Form.NFD)
            .replace(combiningMarksRegex, "")
            .replace("&", " and ")
            .replace("+", " plus ")
            .replace(nonAlphaNumericRegex, "-")
            .trim('-')
            slugCache[cacheKey] = normalized
            normalized
        }
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
