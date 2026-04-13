package isim.ia2y.myapplication

import android.content.Context

object GuestSessionMerger {

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
    }
}
