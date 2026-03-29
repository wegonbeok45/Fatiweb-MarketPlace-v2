package isim.ia2y.myapplication

import android.content.Context

object GuestSessionMerger {

    suspend fun mergeIntoCurrentUser(context: Context) {
        if (!FirebaseAuthManager.isLoggedIn) return
        val appContext = context.applicationContext
        CartStore.mergeGuestCartIntoCurrent(appContext)
        FavoritesStore.mergeGuestFavoritesIntoCurrent(appContext)
        AddressBookStore.mergeGuestAddressesIntoCurrent(appContext)
        NotificationStore.mergeGuestNotificationsIntoCurrent(appContext)
        CartStore.refreshFromCloud(appContext)
        FavoritesStore.refreshFromCloud(appContext)
        AddressBookStore.refreshFromCloud(appContext)
        NotificationStore.refreshFromCloud(appContext)
    }
}
