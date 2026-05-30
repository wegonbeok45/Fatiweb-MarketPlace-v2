package isim.ia2y.myapplication

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.google.firebase.firestore.Source

enum class CatalogSyncStatus {
    IDLE,
    LOADING,
    SUCCESS,
    ERROR
}

data class CatalogSyncState(
    val status: CatalogSyncStatus = CatalogSyncStatus.IDLE,
    val products: List<Product> = emptyList(),
    val error: Throwable? = null,
    val fromCache: Boolean = false
) {
    val isRefreshing: Boolean
        get() = status == CatalogSyncStatus.LOADING
}

object CatalogSyncManager {
    private const val CATALOG_CACHE_TTL_MS = 60 * 60 * 1000L
    private const val COST_SAFE_CATALOG_CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val HOME_CATALOG_PAGE_SIZE = 24L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private val _syncState = MutableStateFlow(CatalogSyncState())
    val syncState: StateFlow<CatalogSyncState> = _syncState.asStateFlow()

    @Volatile
    private var _isFirstSyncCompleted = false
    val isFirstSyncCompleted: Boolean get() = _isFirstSyncCompleted

    @Volatile
    private var inFlightSync: kotlinx.coroutines.Deferred<List<Product>>? = null

    val isRefreshing: Boolean
        get() = syncState.value.isRefreshing

    fun refreshAsync(force: Boolean = false) {
        scope.launch {
            runCatching { ensureSynced(force) }
        }
    }

    fun publishCachedSnapshot() {
        _isFirstSyncCompleted = true
        emitSuccess(ProductCatalog.all(includeInactive = true), fromCache = false)
    }

    fun stop() {
        _isFirstSyncCompleted = false
        inFlightSync = null
        emitSuccess(ProductCatalog.all(includeInactive = true), fromCache = true)
    }

    suspend fun ensureSynced(force: Boolean = false): List<Product> {
        val appContext = MyApplication.instance
        var cachedProducts = ProductCatalog.all(includeInactive = true)
        if (cachedProducts.isEmpty()) {
            val diskProducts = CatalogDiskCache.load(appContext)
            if (diskProducts.isNotEmpty()) {
                ProductCatalog.replaceAll(diskProducts)
                cachedProducts = ProductCatalog.all(includeInactive = true)
                _isFirstSyncCompleted = true
                emitSuccess(cachedProducts, fromCache = true)
            }
        }
        val hasFreshCache = cachedProducts.isNotEmpty() && CatalogDiskCache.isFresh(appContext, CATALOG_CACHE_TTL_MS)
        val hasCostSafeFreshCache = cachedProducts.isNotEmpty() &&
            CatalogDiskCache.isFresh(appContext, COST_SAFE_CATALOG_CACHE_TTL_MS)
        if (FirebaseCostSafeMode.enabled && !force && hasCostSafeFreshCache) {
            _isFirstSyncCompleted = true
            emitSuccess(cachedProducts, fromCache = true)
            return cachedProducts
        }
        if (!force && _isFirstSyncCompleted && hasFreshCache) {
            emitSuccess(cachedProducts, fromCache = true)
            return cachedProducts
        }

        inFlightSync?.let { deferred ->
            if (deferred.isActive) return deferred.await()
        }

        val deferred = syncMutex.withLock {
            inFlightSync?.takeIf { it.isActive } ?: scope.async {
                val productsBeforeRemote = ProductCatalog.all(includeInactive = true)
                if (force || productsBeforeRemote.isEmpty()) {
                    emitLoading(productsBeforeRemote)
                } else {
                    emitSuccess(productsBeforeRemote, fromCache = true)
                }

                if (!force && productsBeforeRemote.isEmpty()) {
                    val firestoreCachedProducts = readFirestoreCatalogCache()
                    if (firestoreCachedProducts.isNotEmpty()) {
                        ProductCatalog.replaceAll(firestoreCachedProducts)
                        _isFirstSyncCompleted = true
                        emitSuccess(ProductCatalog.all(includeInactive = true), fromCache = true)
                    }
                }

                var remoteError: Throwable? = null
                val remoteProducts = try {
                    val lastSync = CatalogDiskCache.lastSyncMillis(appContext)
                    val productsBeforeServer = ProductCatalog.all(includeInactive = true)
                    val canMergeServerUpdates = !force && productsBeforeServer.isNotEmpty() && lastSync > 0L
                    if (canMergeServerUpdates) {
                        ProductService.fetchProductsUpdatedAfter(lastSync, limit = HOME_CATALOG_PAGE_SIZE, source = Source.SERVER)
                    } else {
                        ProductService.fetchProducts(limit = HOME_CATALOG_PAGE_SIZE, source = Source.SERVER)
                    }
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    remoteError = throwable
                    handleRealtimeCatalogError(throwable)
                    val fallbackProducts = ProductCatalog.all(includeInactive = true)
                    if (fallbackProducts.isNotEmpty()) {
                        fallbackProducts
                    } else {
                        throw throwable
                    }
                }
                if (remoteError != null) {
                    return@async ProductCatalog.all(includeInactive = true)
                }

                val canMergeServerUpdates = !force &&
                    ProductCatalog.all(includeInactive = true).isNotEmpty() &&
                    CatalogDiskCache.lastSyncMillis(appContext) > 0L
                if (canMergeServerUpdates) {
                    remoteProducts.forEach { ProductCatalog.upsert(it) }
                } else {
                    ProductCatalog.replaceAll(remoteProducts)
                }
                _isFirstSyncCompleted = true
                val products = ProductCatalog.all(includeInactive = true)
                CatalogDiskCache.save(appContext, products)
                emitSuccess(products, fromCache = false)
                products
            }.also { inFlightSync = it }
        }

        return try {
            deferred.await()
        } finally {
            if (!deferred.isActive) {
                inFlightSync = null
            }
        }
    }

    private suspend fun readFirestoreCatalogCache(): List<Product> {
        return runCatching {
            ProductService.fetchProducts(source = Source.CACHE)
        }.getOrDefault(emptyList())
    }

    private fun handleRealtimeCatalogError(throwable: Throwable) {
        val products = ProductCatalog.all(includeInactive = true)
        if (products.isNotEmpty()) {
            _isFirstSyncCompleted = true
        }
        _syncState.value = CatalogSyncState(
            status = CatalogSyncStatus.ERROR,
            products = products,
            error = throwable,
            fromCache = products.isNotEmpty()
        )
    }

    private fun emitLoading(products: List<Product>) {
        _syncState.value = CatalogSyncState(
            status = CatalogSyncStatus.LOADING,
            products = products,
            error = null
        )
    }

    private fun emitSuccess(products: List<Product>, fromCache: Boolean) {
        _syncState.value = CatalogSyncState(
            status = CatalogSyncStatus.SUCCESS,
            products = products,
            error = null,
            fromCache = fromCache
        )
    }
}

object AppStartupCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val CATALOG_REFRESH_DELAY_MS = 700L
    private const val SESSION_REFRESH_DELAY_MS = 1200L
    private const val NOTIFICATION_REFRESH_DELAY_MS = 2400L
    private const val NON_CRITICAL_STARTUP_DELAY_MS = 3500L

    @Volatile
    private var deferredStarted = false

    fun startDeferred(context: Context) {
        if (deferredStarted) return
        deferredStarted = true
        val appContext = context.applicationContext
        scope.launch {
            if (FirebaseCostSafeMode.enabled) return@launch
            runCatching { AdminSession.init(appContext) }
        }
        scope.launch {
            delay(CATALOG_REFRESH_DELAY_MS)
            if (FirebaseCostSafeMode.enabled) {
                runCatching { CatalogSyncManager.ensureSynced(force = false) }
                return@launch
            }
            runCatching { CatalogSyncManager.ensureSynced(force = false) }
        }
        scope.launch {
            delay(SESSION_REFRESH_DELAY_MS)
            if (FirebaseCostSafeMode.enabled) return@launch
            runCatching { CartStore.refreshFromCloud(appContext) }
            runCatching { FavoritesStore.refreshFromCloud(appContext) }
            runCatching { AddressBookStore.refreshFromCloud(appContext) }
        }
        scope.launch {
            delay(NOTIFICATION_REFRESH_DELAY_MS)
            if (FirebaseCostSafeMode.enabled) return@launch
            runCatching {
                if (NotificationStore.shouldRefreshFromCloud(appContext)) {
                    NotificationStore.refreshFromCloud(appContext)
                }
            }
        }
        scope.launch {
            delay(NON_CRITICAL_STARTUP_DELAY_MS)
            if (FirebaseCostSafeMode.enabled) return@launch
            runCatching { AppNotificationChannels.ensureCreated(appContext) }
            if (FirebaseAuthManager.isLoggedIn &&
                NotificationPreferencesStore.load(appContext).pushEnabled
            ) {
                runCatching { FcmTokenService.syncCurrentUserToken(appContext) }
            }
            runCatching { AnalyticsTracker.appOpen() }
        }
    }

    fun resetDeferred() {
        deferredStarted = false
    }
}
