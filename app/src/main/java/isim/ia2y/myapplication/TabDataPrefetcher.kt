package isim.ia2y.myapplication

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections

class TabDataPrefetcher(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val warmedTabs = Collections.synchronizedSet(mutableSetOf<MainActivity.Tab>())

    fun preload(tab: MainActivity.Tab, force: Boolean = false, callback: (Result<Unit>) -> Unit) {
        if (!force && warmedTabs.contains(tab)) {
            scope.launch(Dispatchers.Main) { callback(Result.success(Unit)) }
            return
        }

        scope.launch {
            val result = runCatching {
                when (tab) {
                    MainActivity.Tab.HOME,
                    MainActivity.Tab.EXPLORE -> {
                        CatalogSyncManager.ensureSynced(force = false)
                        ProductCatalog.all().size
                    }
                    MainActivity.Tab.CART -> {
                        CatalogSyncManager.ensureSynced(force = false)
                        val cart = CartStore.getCart(appContext)
                        ProductCatalog.orderedFavorites(cart.keys)
                    }
                    MainActivity.Tab.PROFILE -> {
                        FirebaseAuthManager.currentUser?.uid?.let { uid ->
                            appContext.getSharedPreferences("profile_prefs", Context.MODE_PRIVATE)
                                .getString("avatar_uri_$uid", null)
                            FirestoreService.fetchUserProfile(uid)
                            FirestoreService.fetchUserRole(uid)
                        }
                    }
                }
                warmedTabs.add(tab)
                Unit
            }
            withContext(Dispatchers.Main) { callback(result) }
        }
    }

    fun shutdown() {
        scope.cancel()
    }
}
