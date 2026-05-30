package isim.ia2y.myapplication

import android.util.Log
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.text.Normalizer
import java.util.Locale

enum class ProductSearchSort {
    POPULAR,
    PRICE_LOW,
    PRICE_HIGH,
    NEWEST
}

data class ProductSearchPage(
    val products: List<Product>,
    val nextCursor: DocumentSnapshot?,
    val reachedEnd: Boolean
)

internal data class PublicProductQueryShape(
    val activeField: String = "isActive",
    val activeValue: Boolean = true,
    val statusField: String = "status",
    val statusValue: String = "published",
    val updatedAtField: String = "updatedAt"
)

internal val PUBLIC_PRODUCT_QUERY_SHAPE = PublicProductQueryShape()

enum class ProductSaveMode {
    CREATE,
    EDIT
}

class ProductSavePermissionException(
    val mode: ProductSaveMode,
    message: String
) : SecurityException(message)

object ProductService {
    private const val TAG = "ProductService"
    const val DEFAULT_PRODUCT_PAGE_SIZE = 30L
    private const val SEARCH_MAX_SCANNED_DOCS = 360
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val productsRef get() = db.collection(FirestoreCollections.PRODUCTS)

    private fun publicProductsQuery(): Query =
        productsRef
            .whereEqualTo(PUBLIC_PRODUCT_QUERY_SHAPE.activeField, PUBLIC_PRODUCT_QUERY_SHAPE.activeValue)
            .whereEqualTo(PUBLIC_PRODUCT_QUERY_SHAPE.statusField, PUBLIC_PRODUCT_QUERY_SHAPE.statusValue)

    suspend fun fetchProduct(id: String): Product? {
        val cachedDoc = runCatching { productsRef.document(id).get(Source.CACHE).await() }.getOrNull()
        if (cachedDoc?.exists() == true) {
            FirebaseCostTracker.read("ProductService.fetchProduct", "products/$id", 1, Source.CACHE.name)
            return productFromMap(cachedDoc.id, cachedDoc.data ?: return null)
        }
        val doc = productsRef.document(id).get(Source.SERVER).await()
        FirebaseCostTracker.read("ProductService.fetchProduct", "products/$id", if (doc.exists()) 1 else 0, Source.SERVER.name)
        val data = doc.data ?: return null
        return productFromMap(doc.id, data)
    }

    suspend fun fetchProducts(
        limit: Long = DEFAULT_PRODUCT_PAGE_SIZE,
        source: Source = Source.DEFAULT
    ): List<Product> {
        val snapshot = publicProductsQuery()
            .orderBy(PUBLIC_PRODUCT_QUERY_SHAPE.updatedAtField, Query.Direction.DESCENDING)
            .limit(limit.coerceIn(1L, 50L))
            .get(source)
            .await()
        FirebaseCostTracker.read("ProductService.fetchProducts", "products", snapshot.size(), source.name)
        return snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            productFromMap(doc.id, data)
        }
    }

    suspend fun fetchProductsUpdatedAfter(
        updatedAfterMillis: Long,
        limit: Long = DEFAULT_PRODUCT_PAGE_SIZE,
        source: Source = Source.DEFAULT
    ): List<Product> {
        if (updatedAfterMillis <= 0L) return fetchProducts(limit, source)
        val snapshot = publicProductsQuery()
            .whereGreaterThan(PUBLIC_PRODUCT_QUERY_SHAPE.updatedAtField, com.google.firebase.Timestamp(Date(updatedAfterMillis)))
            .orderBy(PUBLIC_PRODUCT_QUERY_SHAPE.updatedAtField, Query.Direction.DESCENDING)
            .limit(limit.coerceIn(1L, 50L))
            .get(source)
            .await()
        FirebaseCostTracker.read("ProductService.fetchProductsUpdatedAfter", "products", snapshot.size(), source.name)
        return snapshot.documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            productFromMap(doc.id, data)
        }
    }

    suspend fun searchProductsPage(
        queryText: String,
        pageSize: Long,
        lastDoc: DocumentSnapshot? = null,
        categoryFilter: String? = null,
        locationFilter: String? = null,
        minPrice: Double = 0.0,
        maxPrice: Double = Double.MAX_VALUE,
        bioOnly: Boolean = false,
        sort: ProductSearchSort = ProductSearchSort.POPULAR
    ): ProductSearchPage {
        val safePageSize = pageSize.coerceIn(1L, 50L)
        val targetPageSize = safePageSize.toInt()
        val fetchLimit = (safePageSize * 3L).coerceAtMost(90L)
        val queryTokens = normalizedSearchTokens(queryText)
        val indexedTokens = queryTokens.filter { it.length >= 3 }.take(10)

        var baseQuery: Query = productsRef
            .whereEqualTo(PUBLIC_PRODUCT_QUERY_SHAPE.activeField, PUBLIC_PRODUCT_QUERY_SHAPE.activeValue)
            .whereEqualTo(PUBLIC_PRODUCT_QUERY_SHAPE.statusField, PUBLIC_PRODUCT_QUERY_SHAPE.statusValue)

        val normalizedCategoryFilter = categoryFilter
            ?.takeIf { it.isNotBlank() && it != "all" }
            ?.let { MarketplaceCategories.normalizeKey(it) }

        if (!normalizedCategoryFilter.isNullOrBlank() &&
            MarketplaceCategories.categoryFor(normalizedCategoryFilter)?.level == 0
        ) {
            baseQuery = baseQuery.whereEqualTo("category", normalizedCategoryFilter)
        }

        if (indexedTokens.isNotEmpty()) {
            baseQuery = baseQuery.whereArrayContainsAny("searchKeywords", indexedTokens)
        }

        baseQuery = when (sort) {
            ProductSearchSort.PRICE_LOW -> baseQuery.orderBy("price", Query.Direction.ASCENDING)
            ProductSearchSort.PRICE_HIGH -> baseQuery.orderBy("price", Query.Direction.DESCENDING)
            ProductSearchSort.NEWEST -> baseQuery.orderBy("updatedAt", Query.Direction.DESCENDING)
            ProductSearchSort.POPULAR -> baseQuery.orderBy("reviewsCount", Query.Direction.DESCENDING)
        }

        var cursor = lastDoc
        var nextCursor: DocumentSnapshot? = lastDoc
        var scannedDocuments = 0
        var reachedEnd = false
        val products = mutableListOf<Product>()

        while (products.size < targetPageSize && scannedDocuments < SEARCH_MAX_SCANNED_DOCS && !reachedEnd) {
            var pageQuery = baseQuery.limit(fetchLimit)
            if (cursor != null) {
                pageQuery = pageQuery.startAfter(cursor)
            }

            val snapshot = pageQuery.get().await()
            val documents = snapshot.documents
            FirebaseCostTracker.read("ProductService.searchProductsPage", "products", documents.size, "default")
            scannedDocuments += documents.size
            nextCursor = documents.lastOrNull() ?: cursor
            reachedEnd = documents.size.toLong() < fetchLimit

            documents
                .asSequence()
                .mapNotNull { doc -> productFromMap(doc.id, doc.data ?: return@mapNotNull null) }
                .filter { product ->
                    product.matchesSearchPageFilters(
                        queryText = queryText,
                        tokens = queryTokens,
                        locationFilter = locationFilter,
                        minPrice = minPrice,
                        maxPrice = maxPrice,
                        bioOnly = bioOnly
                    ) &&
                        (normalizedCategoryFilter.isNullOrBlank() ||
                            MarketplaceCategories.matches(product, normalizedCategoryFilter))
                }
                .take(targetPageSize - products.size)
                .forEach { products += it }

            cursor = nextCursor
        }

        return ProductSearchPage(
            products = products,
            nextCursor = nextCursor,
            reachedEnd = reachedEnd
        )
    }

    suspend fun fetchProductsPaginated(
        pageSize: Long,
        lastDoc: DocumentSnapshot? = null,
        categoryFilter: String? = null,
        sellerIdFilter: String? = null,
        source: Source = Source.DEFAULT
    ): Pair<List<Product>, DocumentSnapshot?> {
        val safePageSize = pageSize.coerceIn(1L, 50L)
        var query: Query = productsRef

        if (!categoryFilter.isNullOrBlank() && categoryFilter != "all") {
            query = query.whereEqualTo("category", categoryFilter)
        }

        if (!sellerIdFilter.isNullOrBlank()) {
            query = query.whereEqualTo("sellerId", sellerIdFilter)
        }
        query = query
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(safePageSize)

        if (lastDoc != null) {
            query = query.startAfter(lastDoc)
        }

        val snapshot = query.get(source).await()
        FirebaseCostTracker.read("ProductService.fetchProductsPaginated", "products", snapshot.size(), source.name)
        val products = snapshot.documents
            .mapNotNull { doc -> productFromMap(doc.id, doc.data ?: return@mapNotNull null) }
        val nextDoc = if (snapshot.size() > 0) snapshot.documents[snapshot.size() - 1] else null
        return Pair(products, nextDoc)
    }

    suspend fun saveProduct(product: Product, knownExistingProduct: Product? = null): Product {
        if ((product.imageUrls + listOfNotNull(product.imageUrl)).mapNotNull(::verifiedRemoteImageUrl).isEmpty()) {
            throw IllegalArgumentException("At least one product image is required.")
        }
        val uid = FirebaseAuthManager.currentUser?.uid
            ?: throw IllegalStateException("Authentication is required to save a product.")
        val role = UserService.fetchUserRole(uid)
        val sellerIdResolved = product.sellerId.takeIf { it.isNotBlank() } ?: uid
        var existing = knownExistingProduct ?: ProductCatalog.byId(product.id)
        val saveMode = if (existing == null) ProductSaveMode.CREATE else ProductSaveMode.EDIT
        if (role != UserRoles.ADMIN && role != UserRoles.VENDEUR) {
            throw ProductSavePermissionException(
                saveMode,
                if (saveMode == ProductSaveMode.CREATE) {
                    "You don't have permission to publish products."
                } else {
                    "You don't have permission to edit this product."
                }
            )
        }
        if (role == UserRoles.VENDEUR && existing != null && existing.sellerId != uid) {
            throw ProductSavePermissionException(
                ProductSaveMode.EDIT,
                "You don't have permission to edit this product."
            )
        }
        val normalized = product.copy(
            sellerId = sellerIdResolved,
            updatedAt = System.currentTimeMillis()
        )
        Log.d(
            TAG,
            "saveProduct id=${normalized.id} actor=${uid.shortForLog()} role=$role mode=$saveMode existingSeller=${existing?.sellerId.shortForLog()} resolvedSeller=${sellerIdResolved.shortForLog()}"
        )
        if (role == UserRoles.ADMIN) {
            directUpsertProduct(normalized, existing)
        } else {
            runCatching { BackendFunctionsService.upsertProduct(normalized) }
                .onFailure { error ->
                    Log.e(TAG, "=== CF REJECTION: $error ===")
                    Log.e(TAG, "CF error class=${error::class.java.simpleName}")
                    Log.e(TAG, "CF error message=${error.message}")
                    if (error is BackendFunctionException) {
                        Log.e(TAG, "CF error code=${error.code}")
                    }
                    Log.w(TAG, "Callable product upsert failed for id=${normalized.id}; checking direct fallback", error)
                    CrashlyticsHelper.recordNonFatal(TAG, "Product upsert callable failed id=${normalized.id}", error)
                }
                .recoverCatching { error ->
                    val canFallback = TrustedMutationFallbackPolicy.allowDirectWriteFallback(error) ||
                        isUnknownFieldRejection(error)
                    if (canFallback && canWriteProductsDirectly()) {
                        if (existing == null) {
                            existing = fetchProduct(normalized.id)
                        }
                        if (role == UserRoles.VENDEUR && existing != null && existing?.sellerId != uid) {
                            throw ProductSavePermissionException(
                                ProductSaveMode.EDIT,
                                "You don't have permission to edit this product."
                            )
                        }
                        Log.d(TAG, "Using direct product write fallback for id=${normalized.id}")
                        directUpsertProduct(normalized, existing)
                    } else {
                        throw error
                    }
                }
                .getOrThrow()
        }
        ProductCatalog.upsert(normalized)
        CatalogSyncManager.publishCachedSnapshot()
        return normalized
    }

    suspend fun deleteProduct(productId: String) {
        runCatching { BackendFunctionsService.deleteProduct(productId) }
            .recoverCatching { error ->
                if (TrustedMutationFallbackPolicy.allowDirectWriteFallback(error) && canWriteProductsDirectly()) {
                    val uid = FirebaseAuthManager.currentUser?.uid ?: throw error
        val role = UserService.fetchUserRole(uid, forceRefresh = true)
                    val product = fetchProduct(productId)
                    if (role == UserRoles.VENDEUR && product?.sellerId != uid) {
                        throw SecurityException("You can delete only your own products.")
                    }
                    productsRef.document(productId).delete().await()
                } else {
                    throw error
                }
            }
            .getOrThrow()
        ProductCatalog.remove(productId)
        CatalogSyncManager.publishCachedSnapshot()
    }

    suspend fun updateSellerAvatarForProducts(sellerId: String, avatarUrl: String) {
        if (sellerId.isBlank() || avatarUrl.isBlank()) return

        val snapshot = productsRef
            .whereEqualTo("sellerId", sellerId)
            .limit(450)
            .get()
            .await()

        if (snapshot.isEmpty) return

        val update = mapOf(
            "sellerAvatarUrl" to avatarUrl,
            "sellerProfileUpdatedAt" to FieldValue.serverTimestamp()
        )
        snapshot.documents.chunked(400).forEach { documents ->
            val batch = db.batch()
            documents.forEach { document ->
                batch.set(document.reference, update, SetOptions.merge())
            }
            batch.commit().await()
        }

        ProductCatalog.all(includeInactive = true)
            .filter { it.sellerId == sellerId }
            .forEach { product -> ProductCatalog.upsert(product.copy(sellerAvatarUrl = avatarUrl)) }
        CatalogSyncManager.publishCachedSnapshot()
    }

    private fun productToMap(product: Product): Map<String, Any?> {
        val imageUrls = (product.imageUrls + listOfNotNull(product.imageUrl))
            .mapNotNull(::verifiedRemoteImageUrl)
            .distinct()
            .take(5)
        val imageUrl = imageUrls.firstOrNull()

        return mapOf(
            "title" to product.title,
            "subtitle" to product.subtitle,
            "price" to product.price,
            "priceMinor" to product.priceMinor,
            "rating" to product.rating,
            "reviewsCount" to product.reviewsCount,
            "tags" to product.tags,
            "description" to product.description,
            "bullets" to product.bullets,
            "imageUrl" to imageUrl,
            "imageUrls" to imageUrls,
            "category" to product.category,
            "categoryIds" to product.categoryIds.ifEmpty { listOf(product.category) },
            "categoryLeafId" to product.categoryLeafId.ifBlank { product.categoryIds.lastOrNull() ?: product.category },
            "origin" to product.origin,
            "stock" to product.stock,
            "isBio" to product.isBio,
            "isActive" to product.isActive,
            "status" to product.status,
            "approvalStatus" to product.approvalStatus,
            "discountPercent" to product.discountPercentClamped,
            "searchKeywords" to product.searchKeywords.ifEmpty { generateKeywords(product) },
            "sellerId" to product.sellerId,
            "sellerName" to product.sellerName,
            "sellerAvatarUrl" to product.sellerAvatarUrl,
            "sellerVerifiedAt" to product.sellerVerifiedAt,
            "sellerMemberSince" to product.sellerMemberSince,
            "sellerTotalSold" to product.sellerTotalSold,
            "sellerRating" to product.sellerRating,
            "sellerRatingCount" to product.sellerRatingCount,
            "productType" to product.productType,
            "attributes" to product.attributes,
            "colorOptions" to product.colorOptions.map { it.toMap() },
            "sizeOptions" to product.sizeOptions,
            "variants" to product.variants.map { it.toMap() },
            "createdAt" to product.createdAt,
            "updatedAt" to (product.updatedAt ?: com.google.firebase.Timestamp.now())
        )
    }

    private fun generateKeywords(product: Product): List<String> {
        return expandedSearchTokens(
            listOf(
                product.title,
                product.subtitle,
                product.description,
                product.category,
                product.origin,
                product.tags.joinToString(" ")
            ).joinToString(" ")
        )
    }

    private suspend fun canWriteProductsDirectly(): Boolean {
        val uid = FirebaseAuthManager.currentUser?.uid ?: return false
        val role = UserService.fetchUserRole(uid)
        return AdminSession.isVerified(uid) || role == UserRoles.ADMIN || role == UserRoles.VENDEUR
    }

    private fun isUnknownFieldRejection(error: Throwable): Boolean {
        val backend = error as? BackendFunctionException ?: return false
        if (backend.code != com.google.firebase.functions.FirebaseFunctionsException.Code.INVALID_ARGUMENT) {
            return false
        }
        val msg = (backend.message.orEmpty() + " " + (backend.cause?.message.orEmpty())).lowercase()
        return msg.contains("unknown field") ||
            msg.contains("unexpected field") ||
            msg.contains("invalid field") ||
            msg.contains("discount")
    }

    private suspend fun directUpsertProduct(product: Product, existing: Product?) {
        val payload = productToMap(product).toMutableMap()
        val uid = FirebaseAuthManager.currentUser?.uid ?: return
        val role = UserService.fetchUserRole(uid)
        if (role == UserRoles.VENDEUR && existing != null && existing.sellerId != uid) {
            throw SecurityException("You can edit only your own products.")
        }
        val sellerProfile = UserService.fetchUserProfile(uid)
        val sellerName = sellerProfile?.name?.takeIf { it.isNotBlank() }
            ?: FirebaseAuthManager.currentUser?.displayName
            ?: FirebaseAuthManager.currentUser?.email
            ?: "Fatiweb Seller"
        val sellerAvatarUrl = sellerProfile?.avatarUrl.orEmpty()
        val resolvedSellerId = when {
            role == UserRoles.ADMIN && existing?.sellerId?.isNotBlank() == true -> existing.sellerId
            role == UserRoles.ADMIN && product.sellerId.isNotBlank() -> product.sellerId
            else -> uid
        }
        payload["id"] = product.id
        payload["sellerId"] = resolvedSellerId
        payload["sellerName"] = existing?.sellerName?.takeIf { it.isNotBlank() }
            ?: product.sellerName.takeIf { it.isNotBlank() }
            ?: sellerName
        payload["sellerAvatarUrl"] = existing?.sellerAvatarUrl?.takeIf { it.isNotBlank() }
            ?: product.sellerAvatarUrl.takeIf { it.isNotBlank() }
            ?: sellerAvatarUrl
        payload["createdAt"] = existing?.createdAt ?: FieldValue.serverTimestamp()
        payload["updatedAt"] = FieldValue.serverTimestamp()
        Log.d(
            TAG,
            "directUpsertProduct id=${product.id} actor=${uid.shortForLog()} role=$role existingSeller=${existing?.sellerId.shortForLog()} targetSeller=${(payload["sellerId"] as? String).shortForLog()}"
        )
        try {
            productsRef.document(product.id).set(payload, com.google.firebase.firestore.SetOptions.merge()).await()
            Log.d(TAG, "directUpsertProduct succeeded id=${product.id}")
        } catch (error: Exception) {
            Log.e(TAG, "directUpsertProduct failed id=${product.id} actor=${uid.shortForLog()} role=$role", error)
            throw error
        }
    }

    internal fun productFromMap(id: String, map: Map<String, Any>): Product {
        val imageUrls = (map["imageUrls"] as? List<*>)
            ?.mapNotNull { verifiedRemoteImageUrl(it as? String) }
            ?.distinct()
            ?.take(5)
            ?: emptyList()
        val legacyImageUrl = verifiedRemoteImageUrl(map["imageUrl"] as? String)
        val thumbnailUrls = (map["thumbnailUrls"] as? List<*>)
            ?.mapNotNull { verifiedRemoteImageUrl(it as? String) }
            ?.distinct()
            ?.take(5)
            ?: emptyList()
        val thumbnailUrl = verifiedRemoteImageUrl(map["thumbnailUrl"] as? String)

        return Product(
            id = id,
            title = map["title"] as? String ?: "",
            subtitle = map["subtitle"] as? String ?: "",
            price = (map["price"] as? Number)?.toDouble() ?: 0.0,
            priceMinor = (map["priceMinor"] as? Number)?.toLong()
                ?: toMinorUnits((map["price"] as? Number)?.toDouble() ?: 0.0),
            rating = (map["rating"] as? Number)?.toDouble() ?: 0.0,
            reviewsCount = (map["reviewsCount"] as? Number)?.toInt() ?: 0,
            tags = (map["tags"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            description = map["description"] as? String ?: "",
            bullets = (map["bullets"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            imageRes = 0,
            imageUrl = legacyImageUrl,
            imageUrls = imageUrls.ifEmpty { legacyImageUrl?.let(::listOf).orEmpty() },
            thumbnailUrl = thumbnailUrl,
            thumbnailUrls = thumbnailUrls.ifEmpty { thumbnailUrl?.let(::listOf).orEmpty() },
            category = map["category"] as? String ?: "electronics",
            categoryIds = (map["categoryIds"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            categoryLeafId = map["categoryLeafId"] as? String
                ?: (map["categoryIds"] as? List<*>)?.mapNotNull { it as? String }?.lastOrNull()
                ?: (map["category"] as? String ?: "electronics"),
            origin = map["origin"] as? String ?: "tunisia",
            stock = (map["stock"] as? Number)?.toInt() ?: 0,
            isBio = map["isBio"] as? Boolean ?: false,
            isActive = map["isActive"] as? Boolean ?: true,
            status = map["status"] as? String ?: "published",
            approvalStatus = map["approvalStatus"] as? String ?: "approved",
            discountPercent = (map["discountPercent"] as? Number)?.toInt() ?: 0,
            searchKeywords = (map["searchKeywords"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            sellerId = map["sellerId"] as? String ?: "",
            sellerName = map["sellerName"] as? String ?: "",
            sellerAvatarUrl = map["sellerAvatarUrl"] as? String ?: "",
            sellerVerifiedAt = map["sellerVerifiedAt"] ?: map["verifiedAt"],
            sellerMemberSince = map["sellerMemberSince"] ?: map["memberSince"],
            sellerTotalSold = (map["sellerTotalSold"] as? Number)?.toInt()
                ?: (map["totalSold"] as? Number)?.toInt()
                ?: 0,
            sellerRating = (map["sellerRating"] as? Number)?.toDouble()
                ?: (map["sellerRatingAvg"] as? Number)?.toDouble()
                ?: 0.0,
            sellerRatingCount = (map["sellerRatingCount"] as? Number)?.toInt() ?: 0,
            productType = map["productType"] as? String ?: "",
            attributes = (map["attributes"] as? Map<*, *>)
                ?.entries
                ?.mapNotNull { (k, v) -> (k as? String)?.let { it to v } }
                ?.toMap()
                ?: emptyMap(),
            colorOptions = (map["colorOptions"] as? List<*>)
                ?.mapNotNull { (it as? Map<*, *>)?.let(ProductColor::fromMap) }
                ?: emptyList(),
            sizeOptions = (map["sizeOptions"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            variants = (map["variants"] as? List<*>)
                ?.mapNotNull { (it as? Map<*, *>)?.let(ProductVariant::fromMap) }
                ?: emptyList(),
            createdAt = map["createdAt"],
            updatedAt = map["updatedAt"]
        )
    }

    private fun Product.matchesSearchPageFilters(
        queryText: String,
        tokens: List<String>,
        locationFilter: String?,
        minPrice: Double,
        maxPrice: Double,
        bioOnly: Boolean
    ): Boolean {
        val normalizedQuery = normalizeSearchText(queryText)
        val queryTokens = tokens.ifEmpty { normalizedSearchTokens(queryText) }
        val productTokens = normalizedSearchTokens(
            listOf(
                title,
                subtitle,
                description,
                category,
                MarketplaceCategories.displayNameFor(category),
                origin,
                sellerName,
                searchKeywords.joinToString(" "),
                tags.joinToString(" ")
            ).joinToString(" ")
        )
        val queryMatch = normalizedQuery.isBlank() ||
            normalizeSearchText(searchableText).contains(normalizedQuery) ||
            queryTokens.all { queryToken ->
                productTokens.any { productToken -> productToken.matchesQueryToken(queryToken) }
            }
        val locationKeyword = when (locationFilter) {
            "medina" -> "medina"
            "djerba" -> "djerba"
            "kairouan" -> "kairouan"
            else -> null
        }
        val locationMatch = locationKeyword == null || origin.contains(locationKeyword, ignoreCase = true)
        val priceMatch = price in minPrice..maxPrice
        val bioMatch = !bioOnly || isBio
        return isActive && status == "published" && queryMatch && locationMatch && priceMatch && bioMatch
    }

    private fun normalizedSearchTokens(value: String): List<String> {
        return normalizeSearchText(value)
            .split(Regex("[^a-z0-9\\p{IsArabic}]+"))
            .map { it.trim() }
            .filter { it.length >= 2 }
            .distinct()
    }

    private fun expandedSearchTokens(value: String): List<String> {
        return normalizedSearchTokens(value)
            .flatMap { token ->
                buildList {
                    add(token)
                    for (length in 3 until token.length) {
                        add(token.take(length))
                    }
                }
            }
            .distinct()
    }

    private fun normalizeSearchText(value: String): String {
        return Normalizer.normalize(value.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .trim()
            .replace("\\s+".toRegex(), " ")
    }

    private fun String.matchesQueryToken(queryToken: String): Boolean {
        return startsWith(queryToken) ||
            contains(queryToken) ||
            (queryToken.length >= 4 && length >= 4 && editDistanceAtMostOne(this, queryToken))
    }

    private fun editDistanceAtMostOne(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) {
                i++
                j++
            } else {
                edits++
                if (edits > 1) return false
                when {
                    a.length > b.length -> i++
                    a.length < b.length -> j++
                    else -> {
                        i++
                        j++
                    }
                }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    private fun verifiedRemoteImageUrl(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        return trimmed.takeIf {
            it.startsWith("https://", ignoreCase = true) ||
                it.startsWith("http://", ignoreCase = true)
        }
    }

    private fun Any?.toSortMillis(): Long = when (this) {
        is Number -> toLong()
        is com.google.firebase.Timestamp -> toDate().time
        is Map<*, *> -> (this["seconds"] as? Number)?.toLong()?.times(1000L) ?: 0L
        else -> 0L
    }

    private fun String?.shortForLog(): String {
        val value = this?.takeIf { it.isNotBlank() } ?: return "-"
        return value.takeLast(8)
    }
}
