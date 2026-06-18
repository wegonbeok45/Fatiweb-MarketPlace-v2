package isim.ia2y.myapplication

import android.Manifest
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.ListenerRegistration
import isim.ia2y.myapplication.databinding.ActivityMainBinding
import isim.ia2y.myapplication.voice.FatiVoiceController
import isim.ia2y.myapplication.voice.FatiVoiceGeminiService
import isim.ia2y.myapplication.voice.FatiVoicePreferences
import isim.ia2y.myapplication.voice.FatiVoiceService
import isim.ia2y.myapplication.voice.FatiVoiceWaveView
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    enum class Tab {
        HOME, EXPLORE, CART, PROFILE
    }

    enum class FatiVoiceState {
        IDLE, LISTENING, THINKING, SPEAKING
    }

    private var currentTab: Tab = Tab.HOME
    private var isTabLoading = false
    private var pendingTabSelection: Tab? = null
    private var pendingTabAnimate = true
    private var loadingErrorTab: Tab? = null
    private var tabLoadRequestToken = 0
    private var unreadMessagesListener: ListenerRegistration? = null
    private lateinit var tabDataPrefetcher: TabDataPrefetcher
    private lateinit var binding: ActivityMainBinding
    private lateinit var fatiVoiceService: FatiVoiceService
    private lateinit var fatiVoiceController: FatiVoiceController
    private lateinit var fatiVoiceOverlay: View
    private lateinit var fatiVoiceSheet: View
    private lateinit var fatiVoiceUserText: TextView
    private lateinit var fatiVoiceResponseText: TextView
    private lateinit var fatiVoiceMicButton: View
    private lateinit var fatiVoiceIndicator: View
    private lateinit var fatiVoiceWaveView: FatiVoiceWaveView
    private var fatiVoiceTimerEnabled = true
    private var fatiVoiceOverlayAnimator: ObjectAnimator? = null
    private var fatiVoiceSheetAnimator: ObjectAnimator? = null
    private var fatiVoiceMicPulseAnimator: ValueAnimator? = null
    private val fatiVoiceInactivityHandler = Handler(Looper.getMainLooper())
    private val fatiVoiceInactivityRunnable = Runnable {
        activateFatiVoice()
    }
    private var fatiVoiceDebugReceiver: BroadcastReceiver? = null

    private val isMainUiReady: Boolean
        get() = this::binding.isInitialized && this::tabDataPrefetcher.isInitialized

    private val fatiVoicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val prefs = FatiVoicePreferences.getPrefs(this)
        prefs.edit()
            .putBoolean(FatiVoicePreferences.KEY_MIC_GRANTED, granted == true)
            .putBoolean(FatiVoicePreferences.KEY_PERMISSIONS_REQUESTED, true)
            .apply()
        if (granted == true) {
            fatiVoiceTimerEnabled = FatiVoicePreferences.isVoiceEnabled(this)
            if (fatiVoiceTimerEnabled) {
                resetFatiVoiceInactivityTimer()
                fatiVoiceController.start()
            }
        } else {
            showMotionSnackbar(getString(R.string.fativoice_mic_permission_denied))
            updateFatiVoiceEntryVisibility()
        }
    }

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
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (!isOnboardingCompleted()) {
            launchOnboardingFromLoader()
            finish()
            return
        }
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        fatiVoiceOverlay = findViewById(R.id.fatiVoiceOverlay)
        fatiVoiceSheet = findViewById(R.id.fatiVoiceSheet)
        fatiVoiceUserText = findViewById(R.id.tvFatiVoiceUserText)
        fatiVoiceResponseText = findViewById(R.id.tvFatiVoiceResponseText)
        fatiVoiceMicButton = findViewById(R.id.fatiVoiceMicButton)
        fatiVoiceIndicator = findViewById(R.id.fatiVoiceIndicator)
        fatiVoiceWaveView = findViewById(R.id.fatiVoiceWaveView)
        tabDataPrefetcher = TabDataPrefetcher(this)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, 0)

            binding.hostFragmentContainer.setPadding(
                binding.hostFragmentContainer.paddingLeft,
                systemBars.top,
                binding.hostFragmentContainer.paddingRight,
                0
            )
            binding.hostTabLoadingOverlay.setPadding(
                binding.hostTabLoadingOverlay.paddingLeft,
                systemBars.top,
                binding.hostTabLoadingOverlay.paddingRight,
                0
            )
            binding.hostLayoutBottomNav.apply {
                updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                    bottomMargin = systemBars.bottom +
                        resources.getDimensionPixelSize(R.dimen.main_bottom_nav_outer_margin_bottom)
                }
                setPadding(paddingLeft, paddingTop, paddingRight, 0)
            }

            insets
        }

        setupBottomNav()
        setupTabLoadingUi()
        setupMessagingEntry()
        setupFatiVoiceController()
        applyFatiVoiceDebugPrefs(intent)

        currentTab = savedInstanceState?.getString(KEY_SELECTED_TAB)
            ?.let { runCatching { Tab.valueOf(it) }.getOrNull() }
            ?: intent.getStringExtra(EXTRA_OPEN_TAB)?.let { runCatching { Tab.valueOf(it) }.getOrNull() }
            ?: Tab.HOME
        if (savedInstanceState == null && supportFragmentManager.fragments.isEmpty()) {
            openInitialTabContent(currentTab)
        } else {
            selectTab(currentTab, animate = false)
        }
        handleNotificationIntent(intent)
        onBackPressedDispatcher.addCallback(this) {
            if (currentTab != Tab.HOME) {
                selectTab(Tab.HOME, animate = true)
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
        setupFatiVoiceButton()
        registerFatiVoiceDebugReceiver()
        maybeShowFatiVoiceIntro()
        promptFatiVoicePermissionsOnce()
        handleFatiVoiceDebugIntent(intent)
        startDeferredWorkAfterFirstFrame()
    }

    // Heavy resume work (cloud refresh + FCM token sync) only runs once per
    // RESUME_HEAVY_WORK_INTERVAL_MS to avoid hammering Firestore every time the user
    // returns from another screen. UI-only state always updates.
    private var lastHeavyResumeAt: Long = 0L

    override fun onResume() {
        super.onResume()
        if (!isMainUiReady) return
        resetFatiVoiceInactivityTimer()

        val selected = pendingTabSelection ?: currentTab
        updateBottomNavSelection(selected)
        updateTabIndicator(selected, animate = false)
        updateHostCartBadge()

        val now = android.os.SystemClock.elapsedRealtime()
        val runHeavyWork = now - lastHeavyResumeAt >= RESUME_HEAVY_WORK_INTERVAL_MS
        if (runHeavyWork) {
            lastHeavyResumeAt = now
        }

        if (FirebaseAuthManager.isLoggedIn) {
            AppNotificationChannels.ensureCreated(this)
            if (!FirebaseCostSafeMode.enabled && NotificationPreferencesStore.load(this).pushEnabled) {
                maybeRequestNotificationPermissionForPush()
            }
            listenForUnreadMessages()
            if (!FirebaseCostSafeMode.enabled && runHeavyWork) {
                if (NotificationStore.shouldRefreshFromCloud(this)) {
                    lifecycleScope.launch {
                        runCatching { NotificationStore.refreshFromCloud(this@MainActivity) }
                    }
                }
                lifecycleScope.launch {
                    runCatching { FcmTokenService.syncCurrentUserToken(this@MainActivity) }
                }
            }
        } else {
            unreadMessagesListener?.remove()
            unreadMessagesListener = null
            binding.chatFabDot.visibility = View.GONE
        }
    }


    override fun onDestroy() {
        unreadMessagesListener?.remove()
        if (this::tabDataPrefetcher.isInitialized) {
            tabDataPrefetcher.shutdown()
        }
        if (this::fatiVoiceController.isInitialized) {
            fatiVoiceController.destroy()
        }
        fatiVoiceInactivityHandler.removeCallbacks(fatiVoiceInactivityRunnable)
        fatiVoiceDebugReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        fatiVoiceDebugReceiver = null
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN && isMainUiReady) {
            resetFatiVoiceInactivityTimer()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun promptFatiVoicePermissionsOnce() {
        val enabled = FatiVoicePreferences.isVoiceEnabled(this)
        fatiVoiceTimerEnabled = enabled

        if (!isFatiVoiceIntroShown()) return

        val prefs = FatiVoicePreferences.getPrefs(this)
        val requested = prefs.getBoolean(FatiVoicePreferences.KEY_PERMISSIONS_REQUESTED, false)
        val micGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        prefs.edit()
            .putBoolean(FatiVoicePreferences.KEY_MIC_GRANTED, micGranted)
            .apply()

        if (enabled && micGranted) {
            resetFatiVoiceInactivityTimer()
            fatiVoiceController.start()
            return
        }

        if (!requested && enabled) {
            fatiVoicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun resetFatiVoiceInactivityTimer() {
        if (!fatiVoiceTimerEnabled) {
            fatiVoiceInactivityHandler.removeCallbacks(fatiVoiceInactivityRunnable)
            return
        }
        fatiVoiceInactivityHandler.removeCallbacks(fatiVoiceInactivityRunnable)
        fatiVoiceInactivityHandler.postDelayed(fatiVoiceInactivityRunnable, 2_000)
    }

    private fun maybeShowFatiVoiceIntro() {
        if (isFatiVoiceIntroShown()) return
        fatiVoiceTimerEnabled = false
        showFatiVoiceIntroDialog()
    }

    private fun setupFatiVoiceController() {
        fatiVoiceService = FatiVoiceService(this)
        fatiVoiceController = FatiVoiceController(
            this,
            fatiVoiceService,
            FatiVoiceGeminiService
        )
        fatiVoiceController.onVoiceEnabledChanged = { enabled ->
            fatiVoiceTimerEnabled = enabled
            if (enabled) {
                updateFatiVoiceEntryVisibility()
                resetFatiVoiceInactivityTimer()
            } else {
                fatiVoiceInactivityHandler.removeCallbacks(fatiVoiceInactivityRunnable)
                setFatiVoiceState(FatiVoiceState.IDLE)
                updateFatiVoiceEntryVisibility()
            }
        }
    }

    private fun activateFatiVoice(byUser: Boolean = false) {
        if (!::fatiVoiceController.isInitialized) {
            setupFatiVoiceController()
        }

        if (byUser) {
            FatiVoicePreferences.setVoiceEnabled(this, true)
            fatiVoiceTimerEnabled = true
        }

        if (!FatiVoicePreferences.isVoiceEnabled(this)) {
            return
        }

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasMicPermission) {
            fatiVoiceController.start()
        } else {
            fatiVoicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun checkMicPermissionAndStartIfNeeded() {
        activateFatiVoice(byUser = true)
    }

    private fun showFatiVoiceIntroDialog() {
        val introView = layoutInflater.inflate(R.layout.dialog_fativoice_intro, null)
        val btnEnable = introView.findViewById<MaterialButton>(R.id.btnFatiVoiceIntroEnable)
        val btnSkip = introView.findViewById<MaterialButton>(R.id.btnFatiVoiceIntroSkip)
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(introView)
            .setCancelable(false)
            .create()

        fun acceptIntroActivation() {
            if (dialog.isShowing) {
                dialog.dismiss()
            }
            setFatiVoiceIntroShown(true)
            FatiVoicePreferences.setVoiceEnabled(this, true)
            fatiVoiceTimerEnabled = true
            updateFatiVoiceEntryVisibility()
            resetFatiVoiceInactivityTimer()
            checkMicPermissionAndStartIfNeeded()
        }

        btnEnable.setOnClickListener {
            acceptIntroActivation()
        }

        btnSkip.setOnClickListener {
            setFatiVoiceIntroShown(true)
            FatiVoicePreferences.setVoiceEnabled(this, false)
            fatiVoiceTimerEnabled = false
            fatiVoiceInactivityHandler.removeCallbacks(fatiVoiceInactivityRunnable)
            updateFatiVoiceEntryVisibility()
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            fatiVoiceInactivityHandler.removeCallbacks(fatiVoiceInactivityRunnable)
        }

        dialog.show()
    }

    private fun setupFatiVoiceButton() {
        updateFatiVoiceEntryVisibility()
        binding.fatiVoiceFab.setOnClickListener {
            it.performLightHapticFeedback()
            activateFatiVoice(byUser = true)
        }
    }

    private fun updateFatiVoiceEntryVisibility() {
        if (!this::binding.isInitialized) return
        val voiceEnabled = FatiVoicePreferences.isVoiceEnabled(this)
        binding.fatiVoiceFab.visibility = if (voiceEnabled) {
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.hostNavVoiceSpace.visibility = if (voiceEnabled) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun setFatiVoiceState(
        state: FatiVoiceState,
        userSpeech: String = "",
        response: String = ""
    ) {
        when (state) {
            FatiVoiceState.IDLE -> {
                hideFatiVoiceOverlay()
                updateFatiVoiceEntryVisibility()
            }
            FatiVoiceState.LISTENING -> {
                showFatiVoiceOverlay()
                fatiVoiceUserText.text = "Je vous ecoute..."
                fatiVoiceResponseText.text = ""
                fatiVoiceIndicator.visibility = View.GONE
                fatiVoiceMicButton.visibility = View.VISIBLE
                fatiVoiceWaveView.setWaveSpeed(700)
                fatiVoiceWaveView.setWaveAmplitude(0.22f)
                fatiVoiceWaveView.startWave()
                startFatiVoiceMicPulse()
                binding.fatiVoiceFab.visibility = View.GONE
            }
            FatiVoiceState.THINKING -> {
                showFatiVoiceOverlay()
                fatiVoiceUserText.text = "Reflexion..."
                fatiVoiceResponseText.text = ""
                fatiVoiceIndicator.visibility = View.VISIBLE
                fatiVoiceMicButton.visibility = View.GONE
                fatiVoiceWaveView.setWaveSpeed(2000)
                fatiVoiceWaveView.setWaveAmplitude(0.14f)
                fatiVoiceWaveView.startWave()
                stopFatiVoiceMicPulse()
            }
            FatiVoiceState.SPEAKING -> {
                showFatiVoiceOverlay()
                fatiVoiceUserText.text = if (userSpeech.isBlank()) "FatiVoice" else userSpeech
                fatiVoiceResponseText.text = response
                fatiVoiceIndicator.visibility = View.GONE
                fatiVoiceMicButton.visibility = View.GONE
                fatiVoiceWaveView.setWaveSpeed(1300)
                fatiVoiceWaveView.setWaveAmplitude(0.18f)
                fatiVoiceWaveView.startWave()
                stopFatiVoiceMicPulse()
            }
        }
    }

    private fun showFatiVoiceOverlay() {
        if (fatiVoiceOverlay.visibility != View.VISIBLE) {
            fatiVoiceOverlay.alpha = 0f
            fatiVoiceOverlay.visibility = View.VISIBLE
            fatiVoiceOverlayAnimator?.cancel()
            fatiVoiceOverlayAnimator = ObjectAnimator.ofFloat(fatiVoiceOverlay, "alpha", 0f, 1f).apply {
                duration = 300
                start()
            }
        }

        fatiVoiceSheet.post {
            fatiVoiceSheet.translationY = fatiVoiceSheet.height.toFloat()
            fatiVoiceSheetAnimator?.cancel()
            fatiVoiceSheetAnimator = ObjectAnimator.ofFloat(
                fatiVoiceSheet,
                "translationY",
                fatiVoiceSheet.translationY,
                0f
            ).apply {
                duration = 400
                start()
            }
        }
    }

    private fun hideFatiVoiceOverlay() {
        if (fatiVoiceOverlay.visibility != View.VISIBLE) return

        fatiVoiceOverlayAnimator?.cancel()
        fatiVoiceOverlayAnimator = ObjectAnimator.ofFloat(fatiVoiceOverlay, "alpha", 1f, 0f).apply {
            duration = 300
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    fatiVoiceOverlay.visibility = View.GONE
                }
            })
            start()
        }

        fatiVoiceSheetAnimator?.cancel()
        fatiVoiceSheetAnimator = ObjectAnimator.ofFloat(
            fatiVoiceSheet,
            "translationY",
            0f,
            fatiVoiceSheet.height.toFloat()
        ).apply {
            duration = 400
            start()
        }

        fatiVoiceWaveView.stopWave()
        stopFatiVoiceMicPulse()
    }

    private fun startFatiVoiceMicPulse() {
        fatiVoiceMicPulseAnimator?.cancel()
        fatiVoiceMicPulseAnimator = ValueAnimator.ofFloat(1f, 1.08f).apply {
            duration = 800
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                val scale = it.animatedValue as Float
                fatiVoiceMicButton.scaleX = scale
                fatiVoiceMicButton.scaleY = scale
            }
            start()
        }
    }

    private fun stopFatiVoiceMicPulse() {
        fatiVoiceMicPulseAnimator?.cancel()
        fatiVoiceMicButton.scaleX = 1f
        fatiVoiceMicButton.scaleY = 1f
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_SELECTED_TAB, currentTab.name)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!isMainUiReady) return

        applyFatiVoiceDebugPrefs(intent)
        if (handleFatiVoiceDebugIntent(intent)) return
        if (handleNotificationIntent(intent)) return
        val requested = intent.getStringExtra(EXTRA_OPEN_TAB)
            ?.let { runCatching { Tab.valueOf(it) }.getOrNull() }
            ?: return
        selectTab(requested, animate = false)
        updateHostCartBadge()
    }

    private fun applyFatiVoiceDebugPrefs(intent: Intent) {
        if (!BuildConfig.DEBUG) return
        if (intent.hasExtra(EXTRA_TEST_FATIVOICE_INTRO_SHOWN)) {
            setFatiVoiceIntroShown(intent.getBooleanExtra(EXTRA_TEST_FATIVOICE_INTRO_SHOWN, false))
        }
        if (intent.hasExtra(EXTRA_TEST_FATIVOICE_ENABLED)) {
            val enabled = intent.getBooleanExtra(EXTRA_TEST_FATIVOICE_ENABLED, false)
            FatiVoicePreferences.setVoiceEnabled(this, enabled)
            fatiVoiceTimerEnabled = enabled
        }
    }

    private fun handleFatiVoiceDebugIntent(intent: Intent): Boolean {
        if (!BuildConfig.DEBUG) return false
        var handled = false

        if (intent.getBooleanExtra(EXTRA_TEST_FATIVOICE_ACTIVATE, false)) {
            handled = true
            binding.root.post {
                Log.d(TAG, "FatiVoice debug activate")
                activateFatiVoice(byUser = false)
            }
        }

        val command = intent.getStringExtra(EXTRA_TEST_FATIVOICE_COMMAND).orEmpty()
        if (command.isNotBlank()) {
            handled = true
            binding.root.postDelayed({
                Log.d(TAG, "FatiVoice debug command: $command")
                fatiVoiceController.handleVoiceInput(command)
            }, 500L)
        }

        return handled
    }

    private fun registerFatiVoiceDebugReceiver() {
        if (!BuildConfig.DEBUG || fatiVoiceDebugReceiver != null) return
        fatiVoiceDebugReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_TEST_FATIVOICE) return
                applyFatiVoiceDebugPrefs(intent)
                handleFatiVoiceDebugIntent(intent)
            }
        }
        val filter = IntentFilter(ACTION_TEST_FATIVOICE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(fatiVoiceDebugReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(fatiVoiceDebugReceiver, filter)
        }
    }

    private fun handleNotificationIntent(intent: android.content.Intent): Boolean {
        val conversationId = intent.getStringExtra("conversationId").orEmpty()
        if (conversationId.isNotBlank()) {
            intent.removeExtra("conversationId")
            startActivity(ConversationActivity.createIntent(this, conversationId))
            return true
        }

        val orderId = intent.getStringExtra("orderId").orEmpty()
        if (orderId.isNotBlank()) {
            intent.removeExtra("orderId")
            startActivity(OrderDetailsActivity.createIntent(this, orderId))
            return true
        }

        // F-15: deep links to product, category, and promo screens via FCM data payload.
        val productId = intent.getStringExtra("productId").orEmpty()
        if (productId.isNotBlank()) {
            intent.removeExtra("productId")
            navigateToProductDetails(productId)
            return true
        }

        val category = intent.getStringExtra("category").orEmpty()
        if (category.isNotBlank()) {
            intent.removeExtra("category")
            startActivity(CategoryProductsActivity.createIntent(this, category))
            return true
        }

        // Generic "open tab" payload — already implemented via EXTRA_OPEN_TAB but we
        // also accept "tab" lower-case from server-side push payloads.
        val tab = intent.getStringExtra("tab").orEmpty()
        if (tab.isNotBlank()) {
            intent.removeExtra("tab")
            runCatching { Tab.valueOf(tab.uppercase(java.util.Locale.ROOT)) }
                .getOrNull()
                ?.let { selectTab(it, animate = false) }
            return true
        }
        return false
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (handleNotificationPermissionResult(requestCode, grantResults)) return
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    fun selectTab(tab: Tab, animate: Boolean = true) {
        if (!isMainUiReady) return
        if (isTabLoading) return

        runCatching {
            if (tab == currentTab && supportFragmentManager.findFragmentByTag(tab.name) != null) {
                (supportFragmentManager.findFragmentByTag(tab.name) as? TabReselectionHandler)?.onTabReselected()
                updateBottomNavSelection(tab)
                updateTabIndicator(tab, animate = animate)
                updateHostCartBadge()
                return
            }
            val existingTarget = supportFragmentManager.findFragmentByTag(tab.name)
            if (existingTarget != null) {
                openTabContentDirect(tab, animate)
                return
            }
            openTabContentDirect(tab, animate)
        }.onFailure { error ->
            Log.e(TAG, "Failed to open tab: $tab", error)
            showMotionSnackbar(getString(R.string.main_tab_load_failed))
            if (tab != Tab.HOME) {
                runCatching { selectTab(Tab.HOME, animate = false) }
            }
        }
    }

    private fun createTabFragment(tab: Tab): Fragment = when (tab) {
        Tab.HOME -> HomeTabFragment()
        Tab.EXPLORE -> ExploreTabFragment()
        Tab.CART -> CartTabFragment()
        Tab.PROFILE -> ProfileTabFragment()
    }

    fun updateHostCartBadge() {
        if (!this::binding.isInitialized) return

        val count = CartStore.itemCount(this)
        if (count <= 0) {
            binding.cardBottomCartBadge.visibility = View.GONE
            return
        }
        binding.cardBottomCartBadge.visibility = View.VISIBLE
        binding.tvBottomCartBadge.text = count.toString()
    }

    private fun setupBottomNav() {
        binding.hostNavHome.setOnClickListener { selectTab(Tab.HOME) }
        binding.hostNavExplore.setOnClickListener { selectTab(Tab.EXPLORE) }
        binding.hostNavCart.setOnClickListener { selectTab(Tab.CART) }
        binding.hostNavProfile.setOnClickListener { selectTab(Tab.PROFILE) }
    }

    private fun setupTabLoadingUi() {
        binding.hostBtnTabRetry.setOnClickListener {
            val retryTab = loadingErrorTab ?: pendingTabSelection ?: return@setOnClickListener
            if (isTabLoading) return@setOnClickListener
            beginTabSelectionWithLoading(retryTab, pendingTabAnimate, forcePrefetch = true)
        }
    }

    private fun setupMessagingEntry() {
        binding.chatFab.visibility = View.GONE
        binding.chatFab.setOnClickListener(null)
        binding.chatFabDot.visibility = View.GONE
    }

    private fun startDeferredWorkAfterFirstFrame() {
        binding.root.post {
            binding.root.post {
                if (!isFinishing && !isDestroyed) {
                    AppStartupCoordinator.startDeferred(applicationContext)
                }
            }
        }
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
            LocationHelper.fetchCurrentLocation(this@MainActivity)
                .onSuccess { LocationProfileSync.saveLocation(this@MainActivity, it) }
                .onFailure { Log.w("LocationFlow", "Startup location failed", it) }
        }
    }

    private fun listenForUnreadMessages() {
        binding.chatFabDot.visibility = View.GONE
    }

    private fun updateBottomNavSelection(selected: Tab) {
        setNavItemState(
            icon = binding.hostNavHomeIcon,
            label = binding.hostNavHomeLabel,
            active = selected == Tab.HOME
        )
        setNavItemState(
            icon = binding.hostNavExploreIcon,
            label = binding.hostNavExploreLabel,
            active = selected == Tab.EXPLORE
        )
        setNavItemState(
            icon = binding.hostIvBottomCartIcon,
            label = binding.hostNavCartLabel,
            active = selected == Tab.CART
        )
        setNavItemState(
            icon = binding.hostNavProfileIcon,
            label = binding.hostNavProfileLabel,
            active = selected == Tab.PROFILE
        )
    }

    private fun beginTabSelectionWithLoading(
        tab: Tab,
        animate: Boolean,
        forcePrefetch: Boolean = false
    ) {
        if (isTabLoading) return

        pendingTabSelection = tab
        pendingTabAnimate = animate
        loadingErrorTab = null
        isTabLoading = true
        tabLoadRequestToken += 1
        val requestToken = tabLoadRequestToken

        updateBottomNavSelection(tab)
        updateTabIndicator(tab, animate = animate)
        setBottomNavEnabled(false)
        showTabLoading(loading = true, errorMessage = null)

        tabDataPrefetcher.preload(tab, force = forcePrefetch) { result ->
            if (requestToken != tabLoadRequestToken || isFinishing || isDestroyed) return@preload

            result.onSuccess {
                openTabContent(tab, animate, requestToken)
            }.onFailure { error ->
                Log.e(TAG, "Failed to preload tab data: $tab", error)
                loadingErrorTab = tab
                pendingTabSelection = null
                isTabLoading = false
                setBottomNavEnabled(true)
                showTabLoading(loading = false, errorMessage = getString(R.string.tab_loading_error))
            }
        }
    }

    private fun openInitialTabContent(tab: Tab) {
        runCatching {
            val target = supportFragmentManager.findFragmentByTag(tab.name) ?: createTabFragment(tab)
            if (!target.isAdded) {
                supportFragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .add(R.id.hostFragmentContainer, target, tab.name)
                    .commitNow()
            }
            currentTab = tab
            pendingTabSelection = null
            loadingErrorTab = null
                isTabLoading = false
                showTabLoading(loading = false, errorMessage = null)
                setBottomNavEnabled(true)
                updateBottomNavSelection(tab)
                binding.hostLayoutBottomNav.post {
                    updateTabIndicator(tab, animate = false)
                }
            updateHostCartBadge()
        }.onFailure { error ->
            Log.e(TAG, "Failed to open initial tab: $tab", error)
            selectTab(tab, animate = false)
        }
    }

    private fun openTabContent(tab: Tab, animate: Boolean, requestToken: Int) {
        runCatching {
            val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)

            val currentFragment = supportFragmentManager.findFragmentByTag(currentTab.name)
            if (currentFragment != null) {
                transaction.hide(currentFragment)
            }

            val target = supportFragmentManager.findFragmentByTag(tab.name) ?: createTabFragment(tab).also {
                transaction.add(R.id.hostFragmentContainer, it, tab.name)
            }
            transaction.show(target)
            transaction.runOnCommit {
                if (requestToken != tabLoadRequestToken || isFinishing || isDestroyed) return@runOnCommit
                currentTab = tab
                pendingTabSelection = null
                isTabLoading = false
                loadingErrorTab = null
                updateBottomNavSelection(tab)
                updateTabIndicator(tab, animate = animate)
                updateHostCartBadge()
                showTabLoading(loading = false, errorMessage = null)
                setBottomNavEnabled(true)
                playTabEnterAnimation(enabled = animate)
            }
            transaction.commit()
        }.onFailure { error ->
            Log.e(TAG, "Failed to open tab content: $tab", error)
            loadingErrorTab = tab
            pendingTabSelection = null
            isTabLoading = false
            setBottomNavEnabled(true)
            showTabLoading(loading = false, errorMessage = getString(R.string.tab_loading_error))
        }
    }

    private fun openTabContentDirect(tab: Tab, animate: Boolean) {
        runCatching {
            val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)

            val currentFragment = supportFragmentManager.findFragmentByTag(currentTab.name)
            if (currentFragment != null) {
                transaction.hide(currentFragment)
            }

            val target = supportFragmentManager.findFragmentByTag(tab.name) ?: createTabFragment(tab).also {
                transaction.add(R.id.hostFragmentContainer, it, tab.name)
            }
            transaction.show(target)
            transaction.runOnCommit {
                currentTab = tab
                pendingTabSelection = null
                loadingErrorTab = null
                isTabLoading = false
                showTabLoading(loading = false, errorMessage = null)
                setBottomNavEnabled(true)
                updateBottomNavSelection(tab)
                updateTabIndicator(tab, animate = animate)
                updateHostCartBadge()
                playTabEnterAnimation(enabled = animate)
            }
            transaction.commit()
        }.onFailure { error ->
            Log.e(TAG, "Failed to open existing tab content: $tab", error)
            showTabLoading(loading = false, errorMessage = null)
            setBottomNavEnabled(true)
            showMotionSnackbar(getString(R.string.main_tab_load_failed))
        }
    }

    private fun showTabLoading(loading: Boolean, errorMessage: String?) {
        binding.hostTabLoadingOverlay.visibility = if (loading || errorMessage != null) View.VISIBLE else View.GONE
        binding.hostTabLoadingSpinner.visibility = if (loading) View.VISIBLE else View.GONE
        binding.hostTabLoadingText.visibility = if (loading) View.VISIBLE else View.GONE
        binding.hostTabLoadingError.visibility = if (errorMessage != null) View.VISIBLE else View.GONE
        binding.hostTabLoadingError.text = errorMessage ?: getString(R.string.tab_loading_error)
        binding.hostBtnTabRetry.visibility = if (errorMessage != null) View.VISIBLE else View.GONE
    }

    private fun setBottomNavEnabled(enabled: Boolean) {
        listOf(
            binding.hostNavHome,
            binding.hostNavExplore,
            binding.hostNavCart,
            binding.hostNavProfile
        ).forEach { view ->
            view.isEnabled = enabled
            view.isClickable = enabled
            view.alpha = if (enabled) 1f else 0.95f
        }
    }

    private fun setNavItemState(
        icon: ImageView,
        label: TextView?,
        active: Boolean
    ) {
        val color = ContextCompat.getColor(
            this,
            if (active) R.color.home_ref_text_primary else R.color.home_ref_nav_icon
        )

        icon.animate().cancel()
        if (active) {
            icon.scaleX = 0.9f
            icon.scaleY = 0.9f
            icon.animate()
                .scaleX(1.08f)
                .scaleY(1.08f)
                .setDuration(MotionTokens.QUICK)
                .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
                .withEndAction {
                    icon.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(MotionTokens.QUICK)
                        .setInterpolator(android.view.animation.OvershootInterpolator())
                        .start()
                }
                .start()
        } else {
            icon.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(MotionTokens.QUICK)
                .start()
        }

        icon.setColorFilter(color)
        icon.alpha = if (active) 1f else 0.92f

        if (label != null) {
            label.setTextColor(color)
        }
    }

    private fun playTabEnterAnimation(enabled: Boolean) {
        if (!enabled || isReducedMotionEnabled()) return
        val content = binding.hostFragmentContainer
        val distance = 14f * resources.displayMetrics.density
        content.animate().cancel()
        content.alpha = 0f
        content.scaleX = 0.98f
        content.scaleY = 0.98f
        content.translationY = distance
        content.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(MotionTokens.EMPHASIS)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    private fun updateTabIndicator(tab: Tab, animate: Boolean) {
        val navContainer = binding.hostBottomNavConstraint
        val indicator = binding.navIndicator
        
        val targetViewId = getTabContainerId(tab)
        
        indicator.translationX = 0f

        val constraintSet = ConstraintSet()
        constraintSet.clone(navContainer)
        constraintSet.connect(R.id.nav_indicator, ConstraintSet.START, targetViewId, ConstraintSet.START)
        constraintSet.connect(R.id.nav_indicator, ConstraintSet.END, targetViewId, ConstraintSet.END)

        if (animate && !isReducedMotionEnabled()) {
            val transition = ChangeBounds()
            transition.duration = MotionTokens.EMPHASIS
            transition.interpolator = FastOutSlowInInterpolator()
            TransitionManager.beginDelayedTransition(navContainer, transition)
        }

        constraintSet.applyTo(navContainer)
    }

    private fun getTabContainerId(tab: Tab): Int = when (tab) {
        Tab.HOME -> R.id.hostNavHome
        Tab.EXPLORE -> R.id.hostNavExplore
        Tab.CART -> R.id.hostNavCart
        Tab.PROFILE -> R.id.hostNavProfile
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_main_tab"
        const val EXTRA_TEST_FATIVOICE_COMMAND = "fativoice_test_command"
        const val EXTRA_TEST_FATIVOICE_ENABLED = "fativoice_test_enabled"
        const val EXTRA_TEST_FATIVOICE_INTRO_SHOWN = "fativoice_test_intro_shown"
        const val EXTRA_TEST_FATIVOICE_ACTIVATE = "fativoice_test_activate"
        const val ACTION_TEST_FATIVOICE = "com.fatiweb.store.FATIVOICE_TEST"
        private const val KEY_SELECTED_TAB = "selected_tab"
        private const val TAG = "MainActivity"
        // 5 minutes — long enough to skip "return from product screen" bounces,
        // short enough to refresh after a real backgrounded session.
        private const val RESUME_HEAVY_WORK_INTERVAL_MS = 5L * 60L * 1000L
    }
}
