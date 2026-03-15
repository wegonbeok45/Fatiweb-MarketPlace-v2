package isim.ia2y.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

// Cette classe organise cette partie de l'app.
class Onboard2 : AppCompatActivity() {
    // Cette fonction fait une action de cette partie de l'app.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_onboard2)
        val rootView = findViewById<View>(R.id.layoutOnboardSlideTwoRoot)
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
        val motionLayout = findViewById<MotionLayout?>(R.id.layoutOnboardSlideTwoRoot)
        motionLayout?.post {
            if (isReducedMotionEnabled()) {
                motionLayout.progress = 1f
            } else {
                motionLayout.progress = 0f
                motionLayout.transitionToEnd()
            }
        }
        emphasizeCta(R.id.btnContinue)
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun setupOnboardingActions() {
        findViewById<View>(R.id.ivBack)?.setOnClickListener {
            navigateWithMotion(Onboard1::class.java, isForward = false)
        }
        findViewById<View>(R.id.tvSkip)?.setOnClickListener {
            setOnboardingCompleted()
            navigateToMainTab(MainActivity.Tab.HOME)
            finish()
        }
        findViewById<View>(R.id.btnContinue)?.setOnClickListener {
            startActivity(
                Intent(this, Onboard3::class.java).apply {
                    putExtra(Onboard3.EXTRA_FROM_ONBOARDING, true)
                }
            )
            if (isReducedMotionEnabled()) {
                overridePendingTransition(0, 0)
            } else {
                overridePendingTransition(
                    R.anim.motion_activity_enter_forward,
                    R.anim.motion_activity_exit_forward
                )
            }
        }

        bindComingSoon(
            R.id.cardCategoryFood,
            R.id.cardCategoryCosmetics,
            R.id.cardCategoryCrafts
        )
        applyPressFeedback(
            R.id.ivBack,
            R.id.tvSkip,
            R.id.cardCategoryFood,
            R.id.cardCategoryCosmetics,
            R.id.cardCategoryCrafts,
            R.id.btnContinue
        )
    }
}
