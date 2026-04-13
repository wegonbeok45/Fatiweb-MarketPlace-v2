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

enum class CatalogSyncStatus {
    IDLE,
    LOADING,
    SUCCESS,
    ERROR
}

data class CatalogSyncState(
    val status: CatalogSyncStatus = CatalogSyncStatus.IDLE,
    val products: List<Product> = emptyList(),
    val error: Throwable? = null
) {
    val isRefreshing: Boolean
        get() = status == CatalogSyncStatus.LOADING
}

object CatalogSyncManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private var listenerRegistration: com.google.firebase.firestore.ListenerRegistration? = null
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
        emitSuccess(ProductCatalog.all(includeInactive = true))
    }

    suspend fun ensureSynced(force: Boolean = false): List<Product> {
        val cachedProducts = ProductCatalog.all(includeInactive = true)
        if (!force && _isFirstSyncCompleted) {
            emitSuccess(cachedProducts)
            return cachedProducts
        }

        inFlightSync?.let { deferred ->
            if (deferred.isActive) return deferred.await()
        }

        val deferred = syncMutex.withLock {
            inFlightSync?.takeIf { it.isActive } ?: scope.async {
                emitLoading(ProductCatalog.all(includeInactive = true))
                if (listenerRegistration == null) {
                    listenerRegistration = ProductService.listenToProducts(
                        onUpdate = ::handleRealtimeCatalogUpdate,
                        onError = ::handleRealtimeCatalogError
                    )
                }

                val remoteProducts = try {
                    ProductService.fetchProducts()
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    handleRealtimeCatalogError(throwable)
                    val fallbackProducts = ProductCatalog.all(includeInactive = true)
                    if (fallbackProducts.isNotEmpty()) {
                        fallbackProducts
                    } else {
                        throw throwable
                    }
                }

                if (remoteProducts != ProductCatalog.all(includeInactive = true)) {
                    ProductCatalog.replaceAll(remoteProducts)
                }
                _isFirstSyncCompleted = true
                emitSuccess(ProductCatalog.all(includeInactive = true))
                ProductCatalog.all(includeInactive = true)
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

    private fun handleRealtimeCatalogUpdate(products: List<Product>) {
        ProductCatalog.replaceAll(products)
        _isFirstSyncCompleted = true
        emitSuccess(ProductCatalog.all(includeInactive = true))
    }

    private fun handleRealtimeCatalogError(throwable: Throwable) {
        _syncState.value = CatalogSyncState(
            status = CatalogSyncStatus.ERROR,
            products = ProductCatalog.all(includeInactive = true),
            error = throwable
        )
    }

    private fun emitLoading(products: List<Product>) {
        _syncState.value = CatalogSyncState(
            status = CatalogSyncStatus.LOADING,
            products = products,
            error = null
        )
    }

    private fun emitSuccess(products: List<Product>) {
        _syncState.value = CatalogSyncState(
            status = CatalogSyncStatus.SUCCESS,
            products = products,
            error = null
        )
    }
}

object AppStartupCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val SESSION_REFRESH_DELAY_MS = 1200L
    private const val NOTIFICATION_REFRESH_DELAY_MS = 2400L

    @Volatile
    private var deferredStarted = false

    fun startDeferred(context: Context) {
        if (deferredStarted) return
        deferredStarted = true
        val appContext = context.applicationContext
        scope.launch { runCatching { CatalogSyncManager.ensureSynced(force = false) } }
        scope.launch {
            delay(SESSION_REFRESH_DELAY_MS)
            runCatching { CartStore.refreshFromCloud(appContext) }
            runCatching { FavoritesStore.refreshFromCloud(appContext) }
            runCatching { AddressBookStore.refreshFromCloud(appContext) }
        }
        scope.launch {
            delay(NOTIFICATION_REFRESH_DELAY_MS)
            runCatching {
                if (NotificationStore.shouldRefreshFromCloud(appContext)) {
                    NotificationStore.refreshFromCloud(appContext)
                }
            }
        }
    }
}
