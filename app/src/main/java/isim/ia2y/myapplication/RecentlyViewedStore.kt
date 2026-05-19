package isim.ia2y.myapplication

import android.content.Context

object RecentlyViewedStore {
    private const val PREFS_NAME = "recently_viewed_store"
    private const val KEY_IDS = "recently_viewed_ids"
    private const val GUEST_KEY = "guest"
    private const val MAX_ITEMS = 20

    private fun accountKey(): String = FirebaseAuthManager.currentUser?.uid ?: GUEST_KEY

    private fun prefs(context: Context) =
        context.getSharedPreferences("${accountKey()}_$PREFS_NAME", Context.MODE_PRIVATE)

    fun record(context: Context, productId: String) {
        val normalized = productId.trim()
        if (normalized.isBlank()) return

        val next = getIds(context).toMutableList()
        next.removeAll { it == normalized }
        next.add(0, normalized)
        prefs(context).edit()
            .putString(KEY_IDS, next.take(MAX_ITEMS).joinToString(","))
            .apply()
    }

    fun getIds(context: Context): List<String> {
        return prefs(context)
            .getString(KEY_IDS, "")
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_ITEMS)
    }

    fun getProducts(context: Context, limit: Int = 10): List<Product> {
        val byId = ProductCatalog.all(includeInactive = true).associateBy { it.id }
        return getIds(context)
            .mapNotNull { byId[it] }
            .filter { it.isActive && it.isDisplayReady }
            .take(limit)
    }
}
