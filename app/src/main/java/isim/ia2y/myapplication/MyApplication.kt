package isim.ia2y.myapplication

import android.app.Application
import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        configureAppCheck()
        LanguageManager.ensureDefaultAndApply(this)
    }

    private fun configureAppCheck() {
        val firebaseAppCheck = FirebaseAppCheck.getInstance()
        if (BuildConfig.DEBUG) {
            if (installDebugAppCheckProvider(firebaseAppCheck)) {
                Log.d("MyApplication", "Firebase App Check configured with debug provider.")
            } else {
                firebaseAppCheck.installAppCheckProviderFactory(
                    PlayIntegrityAppCheckProviderFactory.getInstance()
                )
                Log.w("MyApplication", "Debug App Check provider missing; falling back to Play Integrity.")
            }
        } else {
            firebaseAppCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
        }
        firebaseAppCheck.setTokenAutoRefreshEnabled(true)
    }

    private fun installDebugAppCheckProvider(firebaseAppCheck: FirebaseAppCheck): Boolean {
        return runCatching {
            val factoryClass = Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
            val factory = factoryClass.getMethod("getInstance").invoke(null)
            val providerFactoryClass = Class.forName("com.google.firebase.appcheck.AppCheckProviderFactory")
            val installMethod = firebaseAppCheck.javaClass.getMethod(
                "installAppCheckProviderFactory",
                providerFactoryClass
            )
            installMethod.invoke(firebaseAppCheck, factory)
        }.isSuccess
    }
}
