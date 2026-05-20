package isim.ia2y.myapplication

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
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
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.launch
import isim.ia2y.myapplication.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    enum class Tab {
        HOME, EXPLORE, CART, PROFILE
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

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        if (!isOnboardingCompleted()) {
            launchOnboardingFromLoader()
            return
        }
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
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
        startDeferredWorkAfterFirstFrame()
    }

    // Heavy resume work (cloud refresh + FCM token sync) only runs once per
    // RESUME_HEAVY_WORK_INTERVAL_MS to avoid hammering Firestore every time the user
    // returns from another screen. UI-only state always updates.
    private var lastHeavyResumeAt: Long = 0L

    override fun onResume() {
        super.onResume()
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
            if (NotificationPreferencesStore.load(this).pushEnabled) {
                maybeRequestNotificationPermissionForPush()
            }
            listenForUnreadMessages()
            if (runHeavyWork) {
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
        tabDataPrefetcher.shutdown()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_SELECTED_TAB, currentTab.name)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleNotificationIntent(intent)) return
        val requested = intent.getStringExtra(EXTRA_OPEN_TAB)
            ?.let { runCatching { Tab.valueOf(it) }.getOrNull() }
            ?: return
        selectTab(requested, animate = false)
        updateHostCartBadge()
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
        private const val KEY_SELECTED_TAB = "selected_tab"
        private const val TAG = "MainActivity"
        // 5 minutes — long enough to skip "return from product screen" bounces,
        // short enough to refresh after a real backgrounded session.
        private const val RESUME_HEAVY_WORK_INTERVAL_MS = 5L * 60L * 1000L
    }
}
