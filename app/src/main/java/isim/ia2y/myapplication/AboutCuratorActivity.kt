package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AboutCuratorActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_about_curator)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<TextView>(R.id.tvAboutVersion)?.text =
            getString(R.string.about_curator_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

        bindActions()
        revealViewsInOrder(
            R.id.layoutTopBar,
            R.id.layoutBrandBlock,
            R.id.layoutAboutPrimaryLinks,
            R.id.layoutAboutSecondaryLinks
        )
    }

    private fun bindActions() {
        findViewById<View>(R.id.ivBack)?.setOnClickListener { finishWithMotion() }
        findViewById<View>(R.id.cardTerms)?.setOnClickListener {
            showStaticSheet(
                getString(R.string.about_curator_terms_title),
                getString(R.string.about_curator_terms_body)
            )
        }
        findViewById<View>(R.id.cardPrivacy)?.setOnClickListener {
            showStaticSheet(
                getString(R.string.about_curator_privacy_title),
                getString(R.string.about_curator_privacy_body)
            )
        }
        findViewById<View>(R.id.cardLicenses)?.setOnClickListener {
            showStaticSheet(
                getString(R.string.about_curator_licenses_title),
                getString(R.string.about_curator_licenses_body)
            )
        }
        findViewById<View>(R.id.cardContactSupport)?.setOnClickListener {
            navigateNoShift(HelpCenterActivity::class.java)
        }
        findViewById<View>(R.id.cardInstagram)?.setOnClickListener {
            showMotionSnackbar(getString(R.string.about_curator_social_pending))
        }
        findViewById<View>(R.id.cardTwitter)?.setOnClickListener {
            showMotionSnackbar(getString(R.string.about_curator_social_pending))
        }

        applyPressFeedback(
            R.id.ivBack,
            R.id.cardTerms,
            R.id.cardPrivacy,
            R.id.cardLicenses,
            R.id.cardContactSupport,
            R.id.cardInstagram,
            R.id.cardTwitter
        )
    }

    private fun showStaticSheet(title: String, body: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(body)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
