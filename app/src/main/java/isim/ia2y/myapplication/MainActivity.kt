package isim.ia2y.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager

class MainActivity : AppCompatActivity() {

    enum class Tab {
        HOME, EXPLORE, CART, PROFILE
    }

    private val locationPermissionRequestCode = 903
    private var currentTab: Tab = Tab.HOME
    private var isTabLoading = false
    private var pendingTabSelection: Tab? = null
    private var pendingTabAnimate = true
    private var loadingErrorTab: Tab? = null
    private var tabLoadRequestToken = 0
    private var tabLoadingStartedAtMs = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var tabDataPrefetcher: TabDataPrefetcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        tabDataPrefetcher = TabDataPrefetcher(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupHostHeader()
        setupBottomNav()
        setupTabLoadingUi()
        requestLocationPermissionIfNeeded()
        if (LocationHelper.hasPermission(this)) {
            LocationHelper.resolveCurrentLocation(this)
        }

        currentTab = savedInstanceState?.getString(KEY_SELECTED_TAB)
            ?.let { runCatching { Tab.valueOf(it) }.getOrNull() }
            ?: intent.getStringExtra(EXTRA_OPEN_TAB)?.let { runCatching { Tab.valueOf(it) }.getOrNull() }
            ?: Tab.HOME
        selectTab(currentTab, animate = false)
        // Eagerly warm all tab data in parallel so the first visit to any tab is instant
        tabDataPrefetcher.preloadAll()

        onBackPressedDispatcher.addCallback(this) {
            if (currentTab != Tab.HOME) {
                selectTab(Tab.HOME, animate = true)
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val selected = pendingTabSelection ?: currentTab
        updateBottomNavSelection(selected)
        updateTabIndicator(selected, animate = false)
        updateHostCartBadge()
        updateHostNotificationBadge()
    }

    override fun onDestroy() {
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
        val requested = intent.getStringExtra(EXTRA_OPEN_TAB)
            ?.let { runCatching { Tab.valueOf(it) }.getOrNull() }
            ?: return
        selectTab(requested, animate = false)
        updateHostCartBadge()
        updateHostNotificationBadge()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (handleNotificationPermissionResult(requestCode, grantResults)) return
        if (requestCode == locationPermissionRequestCode) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                LocationHelper.resolveCurrentLocation(this)
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    fun selectTab(tab: Tab, animate: Boolean = true) {
        if (isTabLoading) return

        runCatching {
            if (tab == currentTab && supportFragmentManager.findFragmentByTag(tab.name) != null) {
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
            beginTabSelectionWithLoading(tab, animate)
        }.onFailure { error ->
            Log.e(TAG, "Failed to open tab: $tab", error)
            showToast(getString(R.string.coming_soon))
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
        val badgeContainer = findViewById<View>(R.id.hostCardBottomCartBadge)
        val badgeText = findViewById<TextView>(R.id.hostTvBottomCartBadge)
        val count = CartStore.itemCount(this)
        if (count <= 0) {
            badgeContainer.visibility = View.GONE
            return
        }
        badgeContainer.visibility = View.VISIBLE
        badgeText.text = count.toString()
    }

    fun updateHostNotificationBadge() {
        val badge = findViewById<View>(R.id.hostNotificationBadge) ?: return
        badge.visibility = if (NotificationStore.hasUnread(this)) View.VISIBLE else View.GONE
    }

    private fun setupBottomNav() {
        findViewById<View>(R.id.hostNavHome).setOnClickListener { selectTab(Tab.HOME) }
        findViewById<View>(R.id.hostNavExplore).setOnClickListener { selectTab(Tab.EXPLORE) }
        findViewById<View>(R.id.hostNavCart).setOnClickListener { selectTab(Tab.CART) }
        findViewById<View>(R.id.hostNavProfile).setOnClickListener { selectTab(Tab.PROFILE) }
    }

    private fun setupTabLoadingUi() {
        findViewById<View>(R.id.hostBtnTabRetry)?.setOnClickListener {
            val retryTab = loadingErrorTab ?: pendingTabSelection ?: return@setOnClickListener
            if (isTabLoading) return@setOnClickListener
            beginTabSelectionWithLoading(retryTab, pendingTabAnimate, forcePrefetch = true)
        }
    }

    private fun setupHostHeader() {
        findViewById<View>(R.id.hostIvHomeLogo).setOnClickListener { selectTab(Tab.HOME, animate = false) }
        findViewById<View>(R.id.hostTvBrand).setOnClickListener { selectTab(Tab.HOME, animate = false) }
        findViewById<View>(R.id.hostIvTopCart).setOnClickListener { navigateFromTop(favoris::class.java) }
        bindNotificationEntry(R.id.hostIvTopNotifications)
        applyPressFeedback(
            R.id.hostIvHomeLogo,
            R.id.hostTvBrand,
            R.id.hostIvTopCart,
            R.id.hostIvTopNotifications
        )
    }

    private fun updateBottomNavSelection(selected: Tab) {
        setNavItemState(
            containerId = R.id.hostNavHome,
            iconId = R.id.hostNavHomeIcon,
            labelId = R.id.hostNavHomeLabel,
            active = selected == Tab.HOME
        )
        setNavItemState(
            containerId = R.id.hostNavExplore,
            iconId = R.id.hostNavExploreIcon,
            labelId = R.id.hostNavExploreLabel,
            active = selected == Tab.EXPLORE
        )
        setNavItemState(
            containerId = R.id.hostNavCart,
            iconId = R.id.hostIvBottomCartIcon,
            labelId = R.id.hostNavCartLabel,
            active = selected == Tab.CART
        )
        setNavItemState(
            containerId = R.id.hostNavProfile,
            iconId = R.id.hostNavProfileIcon,
            labelId = R.id.hostNavProfileLabel,
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
        tabLoadingStartedAtMs = System.currentTimeMillis()

        updateBottomNavSelection(tab)
        updateTabIndicator(tab, animate = animate)
        setBottomNavEnabled(false)
        showTabLoading(loading = true, errorMessage = null)

        tabDataPrefetcher.preload(tab, force = forcePrefetch) { result ->
            if (requestToken != tabLoadRequestToken || isFinishing || isDestroyed) return@preload

            result.onSuccess {
                val minVisibleMs = 220L
                val elapsed = System.currentTimeMillis() - tabLoadingStartedAtMs
                val delay = (minVisibleMs - elapsed).coerceAtLeast(0L)
                mainHandler.postDelayed({
                    if (requestToken != tabLoadRequestToken || isFinishing || isDestroyed) return@postDelayed
                    openTabContent(tab, animate, requestToken)
                }, delay)
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
                // After the first tab is shown, silently pre-create all other tabs in the background
                if (tab == Tab.HOME) {
                    mainHandler.postDelayed({ preWarmAllTabs() }, 600L)
                }
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
            showToast(getString(R.string.coming_soon))
        }
    }

    private fun showTabLoading(loading: Boolean, errorMessage: String?) {
        val overlay = findViewById<View>(R.id.hostTabLoadingOverlay) ?: return
        val spinner = findViewById<View>(R.id.hostTabLoadingSpinner)
        val text = findViewById<TextView>(R.id.hostTabLoadingText)
        val errorText = findViewById<TextView>(R.id.hostTabLoadingError)
        val retry = findViewById<View>(R.id.hostBtnTabRetry)

        overlay.visibility = if (loading || errorMessage != null) View.VISIBLE else View.GONE
        spinner?.visibility = if (loading) View.VISIBLE else View.GONE
        text?.visibility = if (loading) View.VISIBLE else View.GONE
        errorText?.visibility = if (errorMessage != null) View.VISIBLE else View.GONE
        errorText?.text = errorMessage ?: getString(R.string.tab_loading_error)
        retry?.visibility = if (errorMessage != null) View.VISIBLE else View.GONE
    }

    private fun setBottomNavEnabled(enabled: Boolean) {
        listOf(
            R.id.hostNavHome,
            R.id.hostNavExplore,
            R.id.hostNavCart,
            R.id.hostNavProfile
        ).forEach { id ->
            findViewById<View>(id)?.isEnabled = enabled
            findViewById<View>(id)?.isClickable = enabled
            findViewById<View>(id)?.alpha = if (enabled) 1f else 0.95f
        }
    }

    private fun setNavItemState(
        containerId: Int,
        iconId: Int,
        labelId: Int?, 
        active: Boolean
    ) {
        val color = ContextCompat.getColor(
            this,
            if (active) R.color.home_nav_active else R.color.home_nav_inactive
        )
        val icon = findViewById<ImageView>(iconId)
        
        // Reset scale as we use pill indicator now
        icon.animate().cancel()
        icon.scaleX = 1f
        icon.scaleY = 1f

        icon.setColorFilter(color)

        if (labelId != null) {
            val label = findViewById<TextView>(labelId)
            label.setTextColor(color)
        }
    }

    private fun requestLocationPermissionIfNeeded() {
        val coarse = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (coarse || fine) return

        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION
            ),
            locationPermissionRequestCode
        )
    }

    private fun playTabEnterAnimation(enabled: Boolean) {
        if (!enabled || isReducedMotionEnabled()) return
        val content = findViewById<View>(R.id.hostFragmentContainer)
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
            .setDuration(300L)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    private fun updateTabIndicator(tab: Tab, animate: Boolean) {
        val navContainer = findViewById<ConstraintLayout>(R.id.hostLayoutBottomNav) ?: return
        val indicator = navContainer.findViewById<View>(R.id.nav_indicator) ?: return
        
        val targetViewId = getTabContainerId(tab)
        
        // Reset any residual translationX from old approach
        indicator.translationX = 0f

        val constraintSet = ConstraintSet()
        constraintSet.clone(navContainer)
        constraintSet.connect(R.id.nav_indicator, ConstraintSet.START, targetViewId, ConstraintSet.START)
        constraintSet.connect(R.id.nav_indicator, ConstraintSet.END, targetViewId, ConstraintSet.END)

        if (animate && !isReducedMotionEnabled()) {
            val transition = ChangeBounds()
            transition.duration = 300L
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

    /**
     * Silently pre-creates and attaches all tab fragments in hidden state.
     * This runs ~600ms after the HOME tab appears so the UI thread is free.
     * When the user later taps EXPLORE/CART/PROFILE, [selectTab] finds the existing
     * fragment and just shows it instantly — zero inflation delay.
     */
    private fun preWarmAllTabs() {
        if (isFinishing || isDestroyed) return
        runCatching {
            val tabsToWarm = Tab.entries.filter { it != currentTab }
            if (tabsToWarm.isEmpty()) return
            val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
            var addedAny = false
            for (tab in tabsToWarm) {
                if (supportFragmentManager.findFragmentByTag(tab.name) == null) {
                    val fragment = createTabFragment(tab)
                    transaction.add(R.id.hostFragmentContainer, fragment, tab.name)
                    transaction.hide(fragment)
                    addedAny = true
                }
            }
            if (addedAny) {
                transaction.commitAllowingStateLoss()
                Log.d(TAG, "Pre-warmed hidden tab fragments: ${tabsToWarm.map { it.name }}")
            }
        }.onFailure { e ->
            Log.w(TAG, "preWarmAllTabs failed (non-fatal)", e)
        }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_main_tab"
        private const val KEY_SELECTED_TAB = "selected_tab"
        private const val TAG = "MainActivity"
    }
}
