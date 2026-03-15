package isim.ia2y.myapplication

import android.app.Application
import com.google.android.material.color.DynamicColors

// Cette classe organise cette partie de l'app.
class MyApplication : Application() {
    // Cette fonction fait une action de cette partie de l'app.
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        LanguageManager.ensureDefaultAndApply(this)
    }
}
