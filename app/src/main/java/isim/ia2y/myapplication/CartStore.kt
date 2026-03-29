package isim.ia2y.myapplication

import android.content.Context
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object CartStore {
    private const val PREFS_NAME = "cart_store"
    private const val KEY_QTY = "cart_qty"
    private const val GUEST_KEY = "guest"
    const val LIVRAISON_FEE = 7.000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

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

    private fun currentUidOrNull(): String? = runCatching { FirebaseAuthManager.currentUser?.uid }.getOrNull()

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
        val uid = currentUidOrNull() ?: return
        scope.launch {
            runCatching { CartFirestoreService.replaceCart(uid, cart) }
        }
    }

    fun getCart(context: Context): Map<String, Int> {
        return decode(prefs(context).getStringSet(KEY_QTY, emptySet()))
    }

    fun setQuantity(context: Context, productId: String, qty: Int) {
        scope.launch {
            mutex.withLock {
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
                syncCurrentCartToCloud(context, cart)
            }
        }
    }

    fun add(context: Context, productId: String, quantity: Int = 1): Int {
        if (quantity <= 0) return 0

        val cart = getCart(context).toMutableMap()
        val current = cart[productId] ?: 0
        val stock = ProductCatalog.byId(productId)?.stock ?: Int.MAX_VALUE
        val next = (current + quantity).coerceAtMost(stock)
        val addedQuantity = (next - current).coerceAtLeast(0)

        if (addedQuantity > 0) {
            cart[productId] = next
            saveLocalCart(context, currentAccountKey(), cart)
            syncCurrentCartToCloud(context, cart)
        }

        return addedQuantity
    }

    fun addOne(context: Context, productId: String) {
        add(context, productId, quantity = 1)
    }

    fun increment(context: Context, productId: String) {
        add(context, productId, quantity = 1)
    }

    fun decrement(context: Context, productId: String) {
        scope.launch {
            mutex.withLock {
                val cart = getCart(context).toMutableMap()
                val current = cart[productId] ?: 0
                if (current > 0) {
                    val next = current - 1
                    if (next <= 0) cart.remove(productId) else cart[productId] = next
                    saveLocalCart(context, currentAccountKey(), cart)
                    syncCurrentCartToCloud(context, cart)
                }
            }
        }
    }

    fun remove(context: Context, productId: String) {
        setQuantity(context, productId, 0)
    }

    fun clear(context: Context) {
        scope.launch {
            mutex.withLock {
                val key = currentAccountKey()
                saveLocalCart(context, key, emptyMap())
                syncCurrentCartToCloud(context, emptyMap())
            }
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

    fun total(context: Context): Double {
        val subtotal = subtotal(context)
        val livraison = if (subtotal > 0.0) LIVRAISON_FEE else 0.0
        return subtotal + livraison
    }

    suspend fun refreshFromCloud(context: Context): Map<String, Int> {
        val uid = currentUidOrNull() ?: return getCart(context)
        val remoteCart = runCatching { CartFirestoreService.fetchCart(uid) }.getOrDefault(getCart(context))
        mutex.withLock {
            saveLocalCart(context, uid, remoteCart)
        }
        return remoteCart
    }

    suspend fun mergeGuestCartIntoCurrent(context: Context) {
        val uid = currentUidOrNull() ?: return
        val guestPrefs = prefsForAccount(context, GUEST_KEY)
        
        mutex.withLock {
            val guestCart = decode(guestPrefs.getStringSet(KEY_QTY, emptySet()))
            val mergedCart = runCatching { CartFirestoreService.fetchCart(uid) }
                .getOrDefault(decode(prefsForAccount(context, uid).getStringSet(KEY_QTY, emptySet())))
                .toMutableMap()
            guestCart.forEach { (productId, guestQty) ->
                val stock = ProductCatalog.byId(productId)?.stock ?: Int.MAX_VALUE
                val existingQty = mergedCart[productId] ?: 0
                mergedCart[productId] = (existingQty + guestQty).coerceAtMost(stock)
            }
            saveLocalCart(context, uid, mergedCart)
            guestPrefs.edit().remove(KEY_QTY).apply()
            runCatching { CartFirestoreService.replaceCart(uid, mergedCart) }
        }
    }
}

fun formatDt(value: Double): String = String.format(Locale.US, "%.3f DT", value)
