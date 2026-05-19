package isim.ia2y.myapplication

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FavoritesSyncState(
    val isSyncing: Boolean = false,
    val pendingRetry: Boolean = false,
    val errorMessage: String? = null,
    val version: Long = 0L
)

object FavoritesStore {
    private const val TAG = "FavoritesStore"
    private const val PREFS_NAME = "favoris_store"
    private const val KEY_IDS = "liked_ids"
    private const val GUEST_KEY = "guest"
    private const val MAX_SYNC_RETRY_ATTEMPTS = 4
    private const val SYNC_RETRY_BASE_DELAY_MS = 1_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _syncState = MutableStateFlow(FavoritesSyncState())
    val syncState: StateFlow<FavoritesSyncState> = _syncState.asStateFlow()

    @Volatile
    private var latestSyncToken: Long = 0L

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

    private fun currentUidOrNull(): String? = runCatching { FirebaseAuthManager.currentRealUid }.getOrNull()

    private fun syncCurrentFavoritesToCloud(
        context: Context,
        favorites: Set<String>,
        attempt: Int = 0
    ) {
        val uid = currentUidOrNull() ?: return
        val appContext = context.applicationContext
        val syncToken = System.nanoTime()
        latestSyncToken = syncToken
        scope.launch {
            _syncState.value = FavoritesSyncState(isSyncing = true, version = syncToken)
            val result = runCatching { FirestoreService.replaceFavorites(uid, favorites) }
            if (latestSyncToken != syncToken) return@launch

            result.fold(
                onSuccess = {
                    _syncState.value = FavoritesSyncState(version = syncToken)
                },
                onFailure = { error ->
                    val canRetry = attempt < MAX_SYNC_RETRY_ATTEMPTS
                    Log.w(TAG, "Favorites sync failed; retry=$canRetry", error)
                    _syncState.value = FavoritesSyncState(
                        isSyncing = false,
                        pendingRetry = canRetry,
                        errorMessage = error.message ?: "Favorites sync failed.",
                        version = syncToken
                    )
                    if (canRetry) {
                        val delayMs = SYNC_RETRY_BASE_DELAY_MS * (1L shl attempt).coerceAtMost(8L)
                        delay(delayMs)
                        if (latestSyncToken == syncToken) {
                            syncCurrentFavoritesToCloud(appContext, favorites, attempt + 1)
                        }
                    }
                }
            )
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
        val localFavorites = getFavorites(context)
        val remoteFavorites = runCatching { FirestoreService.fetchFavorites(uid) }
            .getOrElse { error ->
                Log.w(TAG, "Favorites cloud refresh failed", error)
                return localFavorites
            }
        val merged = (remoteFavorites + localFavorites).toSet()
        saveLocalFavorites(context, uid, merged)
        if (merged != remoteFavorites) {
            syncCurrentFavoritesToCloud(context, merged)
        }
        return merged
    }

    suspend fun mergeGuestFavoritesIntoCurrent(context: Context) {
        val uid = currentUidOrNull() ?: return
        val guestPrefs = prefsForAccount(context, GUEST_KEY)
        val guestFavorites = guestPrefs.getStringSet(KEY_IDS, emptySet()).orEmpty()
        val localFavorites = prefsForAccount(context, uid).getStringSet(KEY_IDS, emptySet()).orEmpty()
        val merged = runCatching { FirestoreService.fetchFavorites(uid) }
            .getOrDefault(prefsForAccount(context, uid).getStringSet(KEY_IDS, emptySet()).orEmpty())
            .toMutableSet()
        merged.addAll(localFavorites)
        merged.addAll(guestFavorites)
        saveLocalFavorites(context, uid, merged)
        guestPrefs.edit().remove(KEY_IDS).apply()
        syncCurrentFavoritesToCloud(context, merged)
    }
}
