package isim.ia2y.myapplication

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

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

    private val requestLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        val permanentlyDenied = LocationHelper.isPermanentlyDenied(this)
        LocationPermissionStore.markPermissionResult(this, granted, permanentlyDenied)
        Log.d("LocationFlow", if (granted) "Permission accepted" else "Permission rejected")
        if (granted) fetchAndSaveStartupLocation()
    }

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
        rootView.post { maybeAskLocationOnFirstOpen() }
    }

    private fun setupOnboardingActions() {
        findViewById<View>(R.id.ivBack)?.setOnClickListener {
            finishWithMotion(isForward = false)
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

    private fun maybeAskLocationOnFirstOpen() {
        if (!LocationPermissionStore.shouldAskOnStartup(this)) return
        LocationPermissionStore.markStartupRequestShown(this)
        Log.d("LocationFlow", "Location permission requested")
        requestLocationLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun fetchAndSaveStartupLocation() {
        lifecycleScope.launch {
            LocationHelper.fetchCurrentLocation(this@Onboard1)
                .onSuccess { LocationProfileSync.saveLocation(this@Onboard1, it) }
                .onFailure { Log.w("LocationFlow", "Startup location failed", it) }
        }
    }
}
