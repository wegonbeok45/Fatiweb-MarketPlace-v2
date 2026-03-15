package isim.ia2y.myapplication

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// Cette classe organise cette partie de l'app.
class TabDataPrefetcher(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    // Use a thread pool so all tabs can load their data in parallel
    private val ioExecutor: ExecutorService = Executors.newFixedThreadPool(4)
    private val warmedTabs = Collections.synchronizedSet(mutableSetOf<MainActivity.Tab>())

    // Cette fonction fait une action de cette partie de l'app.
    fun preload(tab: MainActivity.Tab, force: Boolean = false, callback: (Result<Unit>) -> Unit) {
        if (!force && warmedTabs.contains(tab)) {
            mainHandler.post { callback(Result.success(Unit)) }
            return
        }

        ioExecutor.execute {
            val result = runCatching {
                when (tab) {
                    MainActivity.Tab.HOME -> {
                        ProductCatalog.all().size
                        FavoritesStore.getFavorites(appContext).size
                    }
                    MainActivity.Tab.EXPLORE -> {
                        ProductCatalog.all().size
                    }
                    MainActivity.Tab.CART -> {
                        val cart = CartStore.getCart(appContext)
                        ProductCatalog.orderedFavorites(cart.keys)
                    }
                    MainActivity.Tab.PROFILE -> {
                        appContext.getSharedPreferences("profile_prefs", Context.MODE_PRIVATE)
                            .getString("avatar_uri", null)
                    }
                }
                warmedTabs.add(tab)
                Unit
            }
            mainHandler.post { callback(result) }
        }
    }

    /**
     * Fire-and-forget: preloads data for ALL tabs in parallel on background threads.
     * Called right after the first tab is shown so every tab is data-ready before the user taps.
     */
    // Cette fonction fait une action de cette partie de l'app.
    fun preloadAll() {
        val remaining = AtomicInteger(MainActivity.Tab.entries.size)
        for (tab in MainActivity.Tab.entries) {
            preload(tab) {
                // No-op callback — we just want the side-effect of warming the cache
                remaining.decrementAndGet()
            }
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun shutdown() {
        ioExecutor.shutdownNow()
    }
}
