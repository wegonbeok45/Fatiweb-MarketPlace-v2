package isim.ia2y.myapplication

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class LoadingScreen : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        if (isOnboardingCompleted()) {
            launchMainFromLoader(MainActivity.Tab.HOME)
        } else {
            launchOnboardingFromLoader()
        }
        finish()
        overridePendingTransition(0, 0)
    }
}
