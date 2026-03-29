package isim.ia2y.myapplication

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object CatalogSyncManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    @Volatile
    private var lastSyncAtMs: Long = 0L

    @Volatile
    private var inFlightSync: kotlinx.coroutines.Deferred<List<Product>>? = null

    private const val SYNC_TTL_MS = 5 * 60 * 1000L

    fun refreshAsync(force: Boolean = false) {
        scope.launch {
            ensureSynced(force)
        }
    }

    suspend fun ensureSynced(force: Boolean = false): List<Product> {
        val now = System.currentTimeMillis()
        val currentProducts = ProductCatalog.all(includeInactive = true)
        val cacheFresh = !force && currentProducts.isNotEmpty() && (now - lastSyncAtMs) < SYNC_TTL_MS
        if (cacheFresh) return currentProducts

        inFlightSync?.let { deferred ->
            if (deferred.isActive) return deferred.await()
        }

        val deferred = syncMutex.withLock {
            inFlightSync?.takeIf { it.isActive } ?: scope.async {

                val remoteProducts = runCatching { ProductService.fetchProducts() }.getOrDefault(emptyList())
                if (remoteProducts.isNotEmpty()) {
                    ProductCatalog.replaceAll(remoteProducts)
                }
                lastSyncAtMs = System.currentTimeMillis()
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
