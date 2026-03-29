package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class Onboard1 : AppCompatActivity() {
    private val contentIds = intArrayOf(
        R.id.ivBack,
        R.id.tvSkip,
        R.id.cardHeroImage,
        R.id.tvHeadline,
        R.id.tvDescription,
        R.id.layoutPagerIndicator,
        R.id.btnNext
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_onboard1)
        val rootView = findViewById<View>(R.id.layoutOnboardSlideOneRoot)
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
        emphasizeCta(R.id.btnNext)
    }

    private fun setupOnboardingActions() {
        findViewById<View>(R.id.ivBack)?.setOnClickListener {
            navigateWithMotion(LoadingScreen::class.java, isForward = false)
        }
        findViewById<View>(R.id.tvSkip)?.setOnClickListener {
            setOnboardingCompleted()
            navigateToMainTab(MainActivity.Tab.HOME)
            finish()
        }
        findViewById<View>(R.id.btnNext)?.setOnClickListener {
            navigateNoShift(Onboard2::class.java)
        }
        bindComingSoon(R.id.cardHeroImage)
        applyPressFeedback(R.id.ivBack, R.id.tvSkip, R.id.cardHeroImage, R.id.btnNext)
    }
}
