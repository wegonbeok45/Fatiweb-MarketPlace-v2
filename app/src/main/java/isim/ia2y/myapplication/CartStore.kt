package isim.ia2y.myapplication

import android.content.Context
import java.util.Locale

// Cette classe organise cette partie de l'app.
object CartStore {
    private const val PREFS_NAME = "cart_store"
    private const val KEY_QTY = "cart_qty"
    const val LIVRAISON_FEE = 7.000

    // Cette fonction fait une action de cette partie de l'app.
    private fun prefs(context: Context): android.content.SharedPreferences {
        val uid = FirebaseAuthManager.currentUser?.uid ?: "guest"
        return context.getSharedPreferences("${uid}_$PREFS_NAME", Context.MODE_PRIVATE)
    }

    // Cette fonction fait une action de cette partie de l'app.
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

    // Cette fonction fait une action de cette partie de l'app.
    private fun encode(map: Map<String, Int>): Set<String> {
        return map
            .filterValues { it > 0 }
            .map { (id, qty) -> "$id:$qty" }
            .toSet()
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun getCart(context: Context): Map<String, Int> {
        return decode(prefs(context).getStringSet(KEY_QTY, emptySet()))
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun setQuantity(context: Context, productId: String, qty: Int) {
        val cart = getCart(context).toMutableMap()
        if (qty <= 0) {
            cart.remove(productId)
        } else {
            cart[productId] = qty
        }
        prefs(context).edit().putStringSet(KEY_QTY, encode(cart)).apply()
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun addOne(context: Context, productId: String) {
        val current = getCart(context)[productId] ?: 0
        setQuantity(context, productId, current + 1)
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun increment(context: Context, productId: String): Int {
        val next = (getCart(context)[productId] ?: 0) + 1
        setQuantity(context, productId, next)
        return next
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun decrement(context: Context, productId: String): Int {
        val current = getCart(context)[productId] ?: 0
        val next = (current - 1).coerceAtLeast(0)
        setQuantity(context, productId, next)
        return next
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun remove(context: Context, productId: String) {
        setQuantity(context, productId, 0)
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_QTY).apply()
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun itemCount(context: Context): Int {
        return getCart(context).values.sum()
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun subtotal(context: Context): Double {
        val cart = getCart(context)
        return cart.entries.sumOf { (id, qty) ->
            val product = ProductCatalog.byId(id)
            (product?.unitPrice ?: 0.0) * qty
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun total(context: Context): Double {
        val subtotal = subtotal(context)
        val livraison = if (subtotal > 0.0) LIVRAISON_FEE else 0.0
        return subtotal + livraison
    }
}

// Cette fonction fait une action de cette partie de l'app.
fun formatDt(value: Double): String = String.format(Locale.US, "%.3f DT", value)
