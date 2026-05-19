package isim.ia2y.myapplication

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

object GuestSessionMerger {
    private const val TAG = "GuestSessionMerger"
    private const val BACKGROUND_MERGE_TIMEOUT_MS = 12_000L
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun mergeIntoCurrentUserInBackground(context: Context) {
        val appContext = context.applicationContext
        backgroundScope.launch {
            runCatching {
                withTimeout(BACKGROUND_MERGE_TIMEOUT_MS) {
                    mergeIntoCurrentUser(appContext)
                }
            }.onFailure { error ->
                if (error is TimeoutCancellationException) {
                    Log.w(TAG, "Guest session merge timed out after ${BACKGROUND_MERGE_TIMEOUT_MS}ms; continuing login.")
                } else {
                    Log.w(TAG, "Guest session merge failed; continuing login.", error)
                }
            }
        }
    }

    suspend fun mergeIntoCurrentUser(context: Context) {
        if (!FirebaseAuthManager.isLoggedIn) return
        val appContext = context.applicationContext
        runCatching { CartStore.mergeGuestCartIntoCurrent(appContext) }
        runCatching { FavoritesStore.mergeGuestFavoritesIntoCurrent(appContext) }
        runCatching { AddressBookStore.mergeGuestAddressesIntoCurrent(appContext) }
        runCatching { NotificationStore.mergeGuestNotificationsIntoCurrent(appContext) }
        runCatching { CartStore.refreshFromCloud(appContext) }
        runCatching { FavoritesStore.refreshFromCloud(appContext) }
        runCatching { AddressBookStore.refreshFromCloud(appContext) }
        runCatching { NotificationStore.refreshFromCloud(appContext) }
        runCatching { FcmTokenService.syncCurrentUserToken(appContext) }
    }
}
