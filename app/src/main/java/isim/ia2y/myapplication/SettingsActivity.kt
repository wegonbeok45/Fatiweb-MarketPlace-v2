package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<View>(R.id.ivBack)?.setOnClickListener { finishWithMotion() }
        findViewById<View>(R.id.cardLanguage)?.setOnClickListener { navigateNoShift(LanguageSettingsActivity::class.java) }
        findViewById<View>(R.id.cardNotifications)?.setOnClickListener { navigateNoShift(NotificationPreferencesActivity::class.java) }
        findViewById<View>(R.id.cardHelpCenter)?.setOnClickListener { navigateNoShift(HelpCenterActivity::class.java) }
        findViewById<View>(R.id.cardAboutCurator)?.setOnClickListener { navigateNoShift(AboutCuratorActivity::class.java) }

        applyPressFeedback(
            R.id.ivBack,
            R.id.cardLanguage,
            R.id.cardNotifications,
            R.id.cardHelpCenter,
            R.id.cardAboutCurator
        )
        revealViewsInOrder(
            R.id.layoutTopBar,
            R.id.sectionLocalization,
            R.id.cardLanguage,
            R.id.sectionPreferences,
            R.id.cardNotifications,
            R.id.sectionSupport,
            R.id.cardHelpCenter,
            R.id.cardAboutCurator
        )
    }
}
