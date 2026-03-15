package isim.ia2y.myapplication

import android.content.Context

// Cette classe organise cette partie de l'app.
object FavoritesStore {
    private const val PREFS_NAME = "favoris_store"
    private const val KEY_IDS = "liked_ids"

    // Cette fonction fait une action de cette partie de l'app.
    private fun prefs(context: Context): android.content.SharedPreferences {
        val uid = FirebaseAuthManager.currentUser?.uid ?: "guest"
        return context.getSharedPreferences("${uid}_$PREFS_NAME", Context.MODE_PRIVATE)
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun getFavorites(context: Context): Set<String> {
        return prefs(context).getStringSet(KEY_IDS, emptySet()) ?: emptySet()
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun isFavorite(context: Context, productId: String): Boolean {
        return getFavorites(context).contains(productId)
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun setFavorite(context: Context, productId: String, isFavorite: Boolean) {
        val current = getFavorites(context).toMutableSet()
        if (isFavorite) current.add(productId) else current.remove(productId)
        prefs(context).edit().putStringSet(KEY_IDS, current).apply()
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun toggleFavorite(context: Context, productId: String): Boolean {
        val next = !isFavorite(context, productId)
        setFavorite(context, productId, next)
        return next
    }
}
