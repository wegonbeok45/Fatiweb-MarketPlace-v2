package isim.ia2y.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
class SettingsActivity : AppCompatActivity() {
    private val languageSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) recreate()
        }

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
        findViewById<View>(R.id.cardLanguage)?.setOnClickListener {
            languageSettingsLauncher.launch(Intent(this, LanguageSettingsActivity::class.java))
            if (isReducedMotionEnabled()) {
                overridePendingTransition(0, 0)
            } else {
                overridePendingTransition(R.anim.motion_activity_enter_forward, R.anim.motion_activity_exit_forward)
            }
        }
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
