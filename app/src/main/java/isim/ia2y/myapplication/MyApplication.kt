package isim.ia2y.myapplication

import android.app.Application

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LanguageManager.ensureDefaultAndApply(this)
    }
}
