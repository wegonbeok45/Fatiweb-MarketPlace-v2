package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class Onboard3 : AppCompatActivity() {
    private val contentIds = intArrayOf(
        R.id.ivBack,
        R.id.tvSkip,
        R.id.cardDeliveryIllustration,
        R.id.tvHeadline,
        R.id.tvDescription,
        R.id.layoutPagerIndicator,
        R.id.btnGetStarted
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isOnboardingCompleted() || !intent.getBooleanExtra(EXTRA_FROM_ONBOARDING, false)) {
            navigateToMainTab(MainActivity.Tab.HOME)
            finish()
            return
        }
        enableEdgeToEdge()
        setContentView(R.layout.activity_onboard3)
        val rootView = findViewById<View>(R.id.layoutOnboardSlideThreeRoot)
        val initialPaddingLeft = rootView.paddingLeft
        val initialPaddingTop = rootView.paddingTop
        val initialPaddingRight = rootView.paddingRight
        val initialPaddingBottom = rootView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                initialPaddingLeft + systemBars.left,
                initialPaddingTop + systemBars.top,
                initialPaddingRight + systemBars.right,
                initialPaddingBottom + systemBars.bottom
            )
            insets
        }
        setupOnboardingActions()
        forceViewsFullyVisible(*contentIds)
        emphasizeCta(R.id.btnGetStarted)
    }

    private fun setupOnboardingActions() {
        findViewById<View>(R.id.ivBack)?.setOnClickListener {
            navigateWithMotion(Onboard2::class.java, isForward = false)
        }
        findViewById<View>(R.id.tvSkip)?.setOnClickListener {
            setOnboardingCompleted()
            navigateToMainTab(MainActivity.Tab.HOME)
            finish()
        }
        findViewById<View>(R.id.btnGetStarted)?.setOnClickListener {
            navigateNoShift(RoleSelectionActivity::class.java)
        }
        bindComingSoon(R.id.cardDeliveryIllustration)
        applyPressFeedback(
            R.id.ivBack,
            R.id.tvSkip,
            R.id.cardDeliveryIllustration,
            R.id.btnGetStarted
        )
    }

    companion object {
        const val EXTRA_FROM_ONBOARDING = "extra_from_onboarding"
    }
}
