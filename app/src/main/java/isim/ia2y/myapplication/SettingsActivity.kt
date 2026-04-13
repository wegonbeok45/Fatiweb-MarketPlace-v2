package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

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
        findViewById<View>(R.id.cardSupport)?.setOnClickListener { showSupportDialog() }
        findViewById<View>(R.id.cardAbout)?.setOnClickListener { showAboutDialog() }

        applyPressFeedback(R.id.ivBack, R.id.cardLanguage, R.id.cardNotifications, R.id.cardSupport, R.id.cardAbout)
        revealViewsInOrder(R.id.layoutTopBar, R.id.cardLanguage, R.id.cardNotifications, R.id.cardSupport, R.id.cardAbout)
    }

    private fun showSupportDialog() {
        val options = arrayOf(
            getString(R.string.support_whatsapp_label),
            getString(R.string.support_email_label)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settings_support_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openWhatsApp(getString(R.string.support_whatsapp_number))
                    1 -> openEmail(getString(R.string.support_email), "Support FatiWeb")
                }
            }
            .setNegativeButton(getString(R.string.profile_edit_cancel), null)
            .show()
    }

    private fun showAboutDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settings_about_title))
            .setMessage(getString(R.string.settings_about_message, BuildConfig.VERSION_NAME))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
