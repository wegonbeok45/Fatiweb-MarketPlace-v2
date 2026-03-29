package isim.ia2y.myapplication

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object FavoritesStore {
    private const val PREFS_NAME = "favoris_store"
    private const val KEY_IDS = "liked_ids"
    private const val GUEST_KEY = "guest"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(context: Context): android.content.SharedPreferences {
        return prefsForAccount(context, FirebaseAuthManager.currentUser?.uid ?: GUEST_KEY)
    }

    private fun prefsForAccount(
        context: Context,
        accountKey: String
    ): android.content.SharedPreferences {
        return context.getSharedPreferences("${accountKey}_$PREFS_NAME", Context.MODE_PRIVATE)
    }

    private fun saveLocalFavorites(context: Context, accountKey: String, favorites: Set<String>) {
        prefsForAccount(context, accountKey)
            .edit()
            .putStringSet(KEY_IDS, favorites)
            .apply()
    }

    private fun currentUidOrNull(): String? = runCatching { FirebaseAuthManager.currentUser?.uid }.getOrNull()

    private fun syncCurrentFavoritesToCloud(context: Context, favorites: Set<String>) {
        val uid = currentUidOrNull() ?: return
        scope.launch {
            runCatching { FirestoreService.replaceFavorites(uid, favorites) }
        }
    }

    fun getFavorites(context: Context): Set<String> {
        return prefsForAccount(context, currentUidOrNull() ?: GUEST_KEY).getStringSet(KEY_IDS, emptySet()) ?: emptySet()
    }

    fun isFavorite(context: Context, productId: String): Boolean {
        return getFavorites(context).contains(productId)
    }

    fun setFavorite(context: Context, productId: String, isFavorite: Boolean) {
        val current = getFavorites(context).toMutableSet()
        if (isFavorite) current.add(productId) else current.remove(productId)
        saveLocalFavorites(context, currentUidOrNull() ?: GUEST_KEY, current)
        syncCurrentFavoritesToCloud(context, current)
    }

    fun toggleFavorite(context: Context, productId: String): Boolean {
        val next = !isFavorite(context, productId)
        setFavorite(context, productId, next)
        return next
    }

    suspend fun refreshFromCloud(context: Context): Set<String> {
        val uid = currentUidOrNull() ?: return getFavorites(context)
        val remoteFavorites = runCatching { FirestoreService.fetchFavorites(uid) }.getOrDefault(getFavorites(context))
        saveLocalFavorites(context, uid, remoteFavorites)
        return remoteFavorites
    }

    suspend fun mergeGuestFavoritesIntoCurrent(context: Context) {
        val uid = currentUidOrNull() ?: return
        val guestPrefs = prefsForAccount(context, GUEST_KEY)
        val guestFavorites = guestPrefs.getStringSet(KEY_IDS, emptySet()).orEmpty()
        val merged = runCatching { FirestoreService.fetchFavorites(uid) }
            .getOrDefault(prefsForAccount(context, uid).getStringSet(KEY_IDS, emptySet()).orEmpty())
            .toMutableSet()
        merged.addAll(guestFavorites)
        saveLocalFavorites(context, uid, merged)
        guestPrefs.edit().remove(KEY_IDS).apply()
        runCatching { FirestoreService.replaceFavorites(uid, merged) }
    }
}
