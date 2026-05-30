package isim.ia2y.myapplication

import android.app.Application
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings

class MyApplication : Application(), ImageLoaderFactory {
    companion object {
        lateinit var instance: MyApplication
            private set
    }

    override fun onCreate() {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
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
        val factory = if (BuildConfig.DEBUG) {
            debugAppCheckProviderFactory()
        } else {
            PlayIntegrityAppCheckProviderFactory.getInstance()
        }
        appCheck.installAppCheckProviderFactory(factory)
        appCheck.setTokenAutoRefreshEnabled(true)
        if (BuildConfig.DEBUG) {
            Log.d("MyApplication", "Firebase App Check: debug provider installed.")
        }
    }

    private fun debugAppCheckProviderFactory(): AppCheckProviderFactory {
        val providerClass = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
        return providerClass.getMethod("getInstance").invoke(null) as AppCheckProviderFactory
    }

    private fun configureFirestoreOffline() {
        val db = FirebaseFirestore.getInstance()
        val settings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(
                PersistentCacheSettings.newBuilder()
                    .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                    .build()
            )
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
