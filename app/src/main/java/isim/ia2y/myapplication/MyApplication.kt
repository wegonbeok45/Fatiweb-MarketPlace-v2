package isim.ia2y.myapplication

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings

class MyApplication : Application(), ImageLoaderFactory {
    companion object {
        lateinit var instance: MyApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        configureAppCheck()
        configureFirestoreOffline()
        UserService.init(this)
        FirebaseAuthManager.syncCrashlyticsUser()
        LanguageManager.ensureDefaultAndApply(this)
    }

    private val firebaseAppCheck: FirebaseAppCheck get() = FirebaseAppCheck.getInstance()

    private fun configureAppCheck() {
        val appCheck = firebaseAppCheck
        if (BuildConfig.DEBUG) {
            val debugProviderFactory = runCatching {
                Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
                    .getMethod("getInstance")
                    .invoke(null) as com.google.firebase.appcheck.AppCheckProviderFactory
            }.onFailure { error ->
                Log.w("MyApplication", "Firebase App Check debug provider is unavailable.", error)
            }.getOrNull()

            if (debugProviderFactory != null) {
                appCheck.installAppCheckProviderFactory(debugProviderFactory)
                appCheck.setTokenAutoRefreshEnabled(true)
                Log.d("MyApplication", "Using Firebase App Check debug provider for Firebase Storage uploads.")
            }
            return
        }

        appCheck.installAppCheckProviderFactory(
            PlayIntegrityAppCheckProviderFactory.getInstance()
        )
        appCheck.setTokenAutoRefreshEnabled(true)
    }

    private fun configureFirestoreOffline() {
        val db = FirebaseFirestore.getInstance()
        val settings = FirebaseFirestoreSettings.Builder()
            .setCacheSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
            .setPersistenceEnabled(true)
            .build()
        db.firestoreSettings = settings
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .crossfade(120)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            .respectCacheHeaders(false)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.12)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_product_cache"))
                    .maxSizeBytes(64L * 1024L * 1024L)
                    .build()
            }
            .build()
    }
}
