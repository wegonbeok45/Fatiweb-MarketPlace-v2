package isim.ia2y.myapplication

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object CartStore {
    private const val PREFS_NAME = "cart_store"
    private const val KEY_QTY = "cart_qty"
    private const val GUEST_KEY = "guest"
    const val LIVRAISON_FEE = 7.000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val SYNC_DEBOUNCE_MS = 500L
    private const val SYNC_RETRY_DELAY_MS = 1_000L
    private const val SYNC_MAX_ATTEMPTS = 3

    private var pendingSyncJob: Job? = null
    private var pendingSyncCart: Map<String, Int>? = null
    private var pendingSyncContext: Context? = null
    private var pendingSyncToken = 0L

    private var _syncState = MutableStateFlow(CartSyncState())
    val syncState: StateFlow<CartSyncState> = _syncState.asStateFlow()

    private var _syncToken = 0L

    data class CartSyncState(
        val isSyncing: Boolean = false,
        val pendingRetry: Boolean = false,
        val errorMessage: String? = null,
        val version: Long = 0L
    )

    fun subtotalMinor(context: Context): Long = toMinorUnits(subtotal(context))

    fun retryCloudSync(context: Context) {
        val cartToRetry = synchronized(this) {
            pendingSyncCart ?: getCart(context)
        }
        syncCurrentCartToCloud(context, cartToRetry)
    }

    private fun prefs(context: Context): android.content.SharedPreferences {
        return prefsForAccount(context, currentAccountKey())
    }

    private fun prefsForAccount(
        context: Context,
        accountKey: String
    ): android.content.SharedPreferences {
        return context.getSharedPreferences("${accountKey}_$PREFS_NAME", Context.MODE_PRIVATE)
    }

    private fun currentAccountKey(): String {
        return currentUidOrNull() ?: GUEST_KEY
    }

    private fun currentUidOrNull(): String? = runCatching { FirebaseAuthManager.currentRealUid }.getOrNull()

    private fun decode(entries: Set<String>?): MutableMap<String, Int> {
        val result = mutableMapOf<String, Int>()
        entries.orEmpty().forEach { row ->
            val parts = row.split(":")
            if (parts.size == 2) {
                val id = parts[0]
                val qty = parts[1].toIntOrNull() ?: 0
                if (qty > 0) result[id] = qty
            }
        }
        return result
    }

    private fun encode(map: Map<String, Int>): Set<String> {
        return map
            .filterValues { it > 0 }
            .map { (id, qty) -> "$id:$qty" }
            .toSet()
    }

    private fun saveLocalCart(context: Context, accountKey: String, cart: Map<String, Int>) {
        prefsForAccount(context, accountKey)
            .edit()
            .putStringSet(KEY_QTY, encode(cart))
            .apply()
    }

    private fun syncCurrentCartToCloud(context: Context, cart: Map<String, Int>) {
        val shouldStartWorker = synchronized(this) {
            pendingSyncCart = cart.toMap()
            pendingSyncContext = context
            pendingSyncToken = ++_syncToken
            _syncState.value = _syncState.value.copy(
                isSyncing = true,
                pendingRetry = false,
                errorMessage = null,
                version = pendingSyncToken
            )
            pendingSyncJob?.isActive != true
        }

        if (shouldStartWorker) {
            pendingSyncJob = scope.launch { drainCloudSyncQueue() }
        }
    }

    private suspend fun drainCloudSyncQueue() {
        while (true) {
            val queued = synchronized(this) {
                val cart = pendingSyncCart ?: return
                val context = pendingSyncContext ?: return
                QueuedCartSync(context, cart, pendingSyncToken)
            }

            delay(SYNC_DEBOUNCE_MS)

            val latestBeforeWrite = synchronized(this) {
                QueuedCartSync(
                    pendingSyncContext ?: return,
                    pendingSyncCart ?: return,
                    pendingSyncToken
                )
            }
            if (latestBeforeWrite.token != queued.token) continue

            val uid = currentUidOrNull() ?: return markCloudSyncSkipped(latestBeforeWrite.token)
            val result = syncLatestCartSnapshot(uid, latestBeforeWrite)
            if (result.isSuccess) {
                val finished = synchronized(this) {
                    if (pendingSyncToken == latestBeforeWrite.token) {
                        pendingSyncCart = null
                        pendingSyncContext = null
                        _syncState.value = CartSyncState(
                            isSyncing = false,
                            pendingRetry = false,
                            errorMessage = null,
                            version = latestBeforeWrite.token
                        )
                        true
                    } else {
                        false
                    }
                }
                if (finished) return
            } else {
                val error = result.exceptionOrNull()
                synchronized(this) {
                    if (pendingSyncToken == latestBeforeWrite.token) {
                        _syncState.value = _syncState.value.copy(
                            isSyncing = false,
                            pendingRetry = true,
                            errorMessage = error?.message,
                            version = latestBeforeWrite.token
                        )
                        pendingSyncJob = null
                        return
                    }
                }
            }
        }
    }

    private suspend fun syncLatestCartSnapshot(
        uid: String,
        queued: QueuedCartSync
    ): Result<Unit> {
        var lastError: Throwable? = null
        repeat(SYNC_MAX_ATTEMPTS) { attempt ->
            if (!isQueuedSyncLatest(queued.token)) return Result.success(Unit)
            val result = runCatching { CartFirestoreService.replaceCart(uid, queued.cart) }
            if (result.isSuccess) return result
            lastError = result.exceptionOrNull()
            if (attempt < SYNC_MAX_ATTEMPTS - 1) {
                delay(SYNC_RETRY_DELAY_MS)
            }
        }
        return Result.failure(lastError ?: IllegalStateException("Cart sync failed."))
    }

    private fun isQueuedSyncLatest(token: Long): Boolean {
        return synchronized(this) { pendingSyncToken == token }
    }

    private fun markCloudSyncSkipped(token: Long) {
        synchronized(this) {
            if (pendingSyncToken == token) {
                pendingSyncCart = null
                pendingSyncContext = null
                _syncState.value = CartSyncState(isSyncing = false, pendingRetry = false, errorMessage = null, version = token)
                pendingSyncJob = null
            }
        }
    }

    private data class QueuedCartSync(
        val context: Context,
        val cart: Map<String, Int>,
        val token: Long
    )

    private fun hasPendingCloudSync(): Boolean {
        return synchronized(this) {
            pendingSyncCart != null || _syncState.value.pendingRetry
        }
    }

    fun cancelPendingSync() {
        pendingSyncJob?.cancel()
        pendingSyncJob = null
        pendingSyncCart = null
        pendingSyncContext = null
        pendingSyncToken = ++_syncToken
    }

    fun getCart(context: Context): Map<String, Int> {
        val raw = decode(prefs(context).getStringSet(KEY_QTY, emptySet()))
        if (!CatalogSyncManager.isFirstSyncCompleted) return raw

        val filtered = raw.filterKeys { ProductCatalog.byId(it) != null }
        if (filtered.size != raw.size) {
            saveLocalCart(context, currentAccountKey(), filtered)
        }
        return filtered
    }

    fun setQuantity(context: Context, productId: String, qty: Int) {
        val cartToSync = synchronized(this) {
            val currentAccountKey = currentAccountKey()
            val cart = getCart(context).toMutableMap()
            val stock = ProductCatalog.byId(productId)?.stock ?: Int.MAX_VALUE
            val safeQty = qty.coerceAtMost(stock).coerceAtLeast(0)
            if (safeQty <= 0) {
                cart.remove(productId)
            } else {
                cart[productId] = safeQty
            }
            saveLocalCart(context, currentAccountKey, cart)
            cart
        }
        syncCurrentCartToCloud(context, cartToSync)
    }

    fun add(context: Context, productId: String, quantity: Int = 1): Int {
        if (quantity <= 0) return 0

        var addedQuantity = 0
        val cartToSync = synchronized(this) {
            val cart = getCart(context).toMutableMap()
            val current = cart[productId] ?: 0
            val stock = ProductCatalog.byId(productId)?.stock ?: Int.MAX_VALUE
            val next = (current + quantity).coerceAtMost(stock)
            addedQuantity = (next - current).coerceAtLeast(0)

            if (addedQuantity > 0) {
                cart[productId] = next
                saveLocalCart(context, currentAccountKey(), cart)
                cart
            } else null
        }

        cartToSync?.let { syncCurrentCartToCloud(context, it) }
        return addedQuantity
    }

    fun addOne(context: Context, productId: String) {
        add(context, productId, quantity = 1)
    }

    fun increment(context: Context, productId: String) {
        add(context, productId, quantity = 1)
    }

    fun decrement(context: Context, productId: String) {
        val cartToSync = synchronized(this) {
            val cart = getCart(context).toMutableMap()
            val current = cart[productId] ?: 0
            if (current > 0) {
                val next = current - 1
                if (next <= 0) cart.remove(productId) else cart[productId] = next
                saveLocalCart(context, currentAccountKey(), cart)
                cart
            } else null
        }
        cartToSync?.let { syncCurrentCartToCloud(context, it) }
    }

    fun remove(context: Context, productId: String) {
        setQuantity(context, productId, 0)
    }

    fun clear(context: Context) {
        synchronized(this) {
            val key = currentAccountKey()
            saveLocalCart(context, key, emptyMap())
        }
        cancelPendingSync()
        val uid = currentUidOrNull()
        if (uid != null) {
            scope.launch { runCatching { CartFirestoreService.replaceCart(uid, emptyMap()) } }
        }
    }

    fun itemCount(context: Context): Int {
        return getCart(context).values.sum()
    }

    fun subtotal(context: Context): Double {
        val cart = getCart(context)
        return cart.entries.sumOf { (id, qty) ->
            val product = ProductCatalog.byId(id)
            (product?.unitPrice ?: 0.0) * qty
        }
    }

    fun total(context: Context, extraShippingFee: Double = 0.0): Double {
        val subtotal = subtotal(context)
        val livraison = if (subtotal > 0.0) LIVRAISON_FEE + extraShippingFee else 0.0
        return subtotal + livraison
    }

    suspend fun refreshFromCloud(context: Context): Map<String, Int> {
        val uid = currentUidOrNull() ?: return getCart(context)
        if (hasPendingCloudSync()) return getCart(context)
        val remoteCart = runCatching { CartFirestoreService.fetchCart(uid) }.getOrDefault(getCart(context))
        synchronized(this) {
            saveLocalCart(context, uid, remoteCart)
        }
        return remoteCart
    }

    suspend fun mergeGuestCartIntoCurrent(context: Context) {
        val uid = currentUidOrNull() ?: return
        cancelPendingSync()
        val guestPrefs = prefsForAccount(context, GUEST_KEY)
        
        val guestCart = decode(guestPrefs.getStringSet(KEY_QTY, emptySet()))
        val remoteCart = runCatching { CartFirestoreService.fetchCart(uid) }
            .getOrDefault(decode(prefsForAccount(context, uid).getStringSet(KEY_QTY, emptySet())))

        val mergedCart = synchronized(this) {
            val merged = remoteCart.toMutableMap()
            guestCart.forEach { (productId, guestQty) ->
                val stock = ProductCatalog.byId(productId)?.stock ?: Int.MAX_VALUE
                val existingQty = merged[productId] ?: 0
                merged[productId] = (existingQty + guestQty).coerceAtMost(stock)
            }
            saveLocalCart(context, uid, merged)
            guestPrefs.edit().remove(KEY_QTY).apply()
            merged
        }
        runCatching { CartFirestoreService.replaceCart(uid, mergedCart) }
    }
}
