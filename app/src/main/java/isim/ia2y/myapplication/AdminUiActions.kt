package isim.ia2y.myapplication

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.ContextCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.transition.ChangeBounds
import androidx.transition.TransitionManager

/**
 * Tab identifiers for the admin bottom navigation bar.
 */
enum class AdminNavTab {
    DASHBOARD,
    COMMANDES,
    PRODUITS,
    CLIENTS,
    NOTIFICATIONS,
    SETTINGS
}

/** Maps each tab to the Activity class that represents it. */
private val TAB_TO_ACTIVITY: Map<AdminNavTab, Class<*>> = mapOf(
    AdminNavTab.DASHBOARD     to AdminDashboardActivity::class.java,
    AdminNavTab.COMMANDES     to AdminCommandesActivity::class.java,
    AdminNavTab.PRODUITS      to AdminProduitsActivity::class.java,
    AdminNavTab.CLIENTS       to AdminClientsActivity::class.java,
    AdminNavTab.NOTIFICATIONS to AdminNotificationsActivity::class.java,
    AdminNavTab.SETTINGS      to AdminParametresActivity::class.java
)

/** Maps each tab to its nav-item view ID in include_admin_bottom_nav.xml */
private val TAB_TO_NAV_ID: Map<AdminNavTab, Int> = mapOf(
    AdminNavTab.DASHBOARD     to R.id.adminNavDashboard,
    AdminNavTab.COMMANDES     to R.id.adminNavCommandes,
    AdminNavTab.PRODUITS      to R.id.adminNavProduits,
    AdminNavTab.CLIENTS       to R.id.adminNavClients,
    AdminNavTab.NOTIFICATIONS to R.id.adminNavNotifications,
    AdminNavTab.SETTINGS      to R.id.adminNavSettings
)

/**
 * Resolves which AdminNavTab THIS activity represents, so that
 * onResume can always refresh the bottom-nav to the correct state.
 */
fun AppCompatActivity.resolveAdminTab(): AdminNavTab {
    val activityClass = this::class.java
    return TAB_TO_ACTIVITY.entries.firstOrNull { it.value == activityClass }?.key
        ?: AdminNavTab.DASHBOARD
}

/**
 * Sets up the 6-tab admin bottom nav.
 * Call this once in onCreate – it wires click listeners.
 * The visual state (pill + colors) is applied via [refreshAdminBottomNav]
 * which must be called from onResume() of every admin activity.
 */
fun AppCompatActivity.setupAdminBottomNav(activeTab: AdminNavTab) {
    // Apply initial visual state
    refreshAdminBottomNav(activeTab)

    // Wire click listeners (only needs to happen once per activity creation)
    TAB_TO_NAV_ID.forEach { (tab, viewId) ->
        findViewById<View?>(viewId)?.setOnClickListener {
            val myTab = resolveAdminTab()
            if (tab == myTab) return@setOnClickListener   // already here
            animateAdminNavAndNavigate(tab, myTab)
        }
    }

    // Press feedback on all tabs
    applyPressFeedback(*TAB_TO_NAV_ID.values.toIntArray())
}

/**
 * Re-applies the correct pill position + icon/label colors for this tab.
 * Safe to call multiple times — idempotent.
 * Call from onResume() so that coming back via REORDER_TO_FRONT refreshes the state.
 */
fun AppCompatActivity.refreshAdminBottomNav(activeTab: AdminNavTab) {
    val activeColor   = ContextCompat.getColor(this, R.color.profile_text_primary)
    val inactiveColor = ContextCompat.getColor(this, R.color.home_nav_inactive)

    // Apply active/inactive tint immediately
    TAB_TO_NAV_ID.forEach { (tab, viewId) ->
        setAdminNavItemColor(viewId, if (tab == activeTab) activeColor else inactiveColor, isActive = tab == activeTab)
    }

    // Snap pill to the correct position (no animation)
    snapAdminPillTo(TAB_TO_NAV_ID[activeTab] ?: R.id.adminNavDashboard)
}

/* ------------------------------------------------------------------ */
/*  Internal helpers                                                    */
/* ------------------------------------------------------------------ */

private fun AppCompatActivity.snapAdminPillTo(@IdRes targetNavId: Int) {
    val navContainer = findViewById<ConstraintLayout>(R.id.adminBottomNav) ?: return
    val indicator = findViewById<View>(R.id.admin_nav_indicator) ?: return
    
    // Check if already snapped to correct target to avoid redundant layout passes
    if (indicator.tag == targetNavId) return
    indicator.tag = targetNavId

    val constraintSet = ConstraintSet()
    constraintSet.clone(navContainer)
    constraintSet.connect(R.id.admin_nav_indicator, ConstraintSet.START, targetNavId, ConstraintSet.START)
    constraintSet.connect(R.id.admin_nav_indicator, ConstraintSet.END,   targetNavId, ConstraintSet.END)
    constraintSet.applyTo(navContainer)
}

private fun AppCompatActivity.animateAdminNavAndNavigate(
    tab: AdminNavTab,
    currentTab: AdminNavTab
) {
    val activeColor   = ContextCompat.getColor(this, R.color.profile_text_primary)
    val inactiveColor = ContextCompat.getColor(this, R.color.home_nav_inactive)
    val reducedMotion = isReducedMotionEnabled()

    // Color transition
    TAB_TO_NAV_ID.forEach { (t, viewId) ->
        val isActive = t == tab
        if (reducedMotion) {
            setAdminNavItemColor(viewId, if (isActive) activeColor else inactiveColor, isActive)
        } else {
            animateAdminNavItemColor(viewId, if (isActive) activeColor else inactiveColor, isActive)
        }
    }

    // Slide the pill
    val targetId = TAB_TO_NAV_ID[tab] ?: return
    animateAdminPillTo(targetId)

    // Navigate after a small delay so the user can see the pill slide
    findViewById<View?>(targetId)?.postDelayed({
        runCatching { navigateToAdminTab(tab) }
            .onFailure { showToast(getString(R.string.coming_soon)) }
    }, 120L)
}

private fun AppCompatActivity.animateAdminPillTo(@IdRes targetNavId: Int) {
    val indicator = findViewById<View>(R.id.admin_nav_indicator) ?: return
    if (indicator.tag == targetNavId) return
    indicator.tag = targetNavId

    if (isReducedMotionEnabled()) {
        snapAdminPillTo(targetNavId)
        return
    }
    val navContainer = findViewById<ConstraintLayout>(R.id.adminBottomNav) ?: return

    val constraintSet = ConstraintSet()
    constraintSet.clone(navContainer)
    constraintSet.connect(R.id.admin_nav_indicator, ConstraintSet.START, targetNavId, ConstraintSet.START)
    constraintSet.connect(R.id.admin_nav_indicator, ConstraintSet.END,   targetNavId, ConstraintSet.END)

    val transition = ChangeBounds()
    transition.duration = 250L
    transition.interpolator = FastOutSlowInInterpolator()
    TransitionManager.beginDelayedTransition(navContainer, transition)

    constraintSet.applyTo(navContainer)
}

private fun AppCompatActivity.setAdminNavItemColor(@IdRes viewId: Int, color: Int, isActive: Boolean = false) {
    val item = findViewById<LinearLayout?>(viewId) ?: return
    val icon = item.getChildAt(0) as? ImageView ?: return
    val label = item.getChildAt(1) as? TextView

    icon.animate().cancel()
    if (isActive) {
        icon.scaleX = 0.8f
        icon.scaleY = 0.8f
        icon.animate()
            .scaleX(1.15f)
            .scaleY(1.15f)
            .setDuration(150L)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                icon.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100L)
                    .setInterpolator(android.view.animation.OvershootInterpolator())
                    .start()
            }
            .start()
    } else {
        icon.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150L)
            .start()
    }

    icon.setColorFilter(color)
    label?.setTextColor(color)
}

private fun AppCompatActivity.animateAdminNavItemColor(@IdRes viewId: Int, toColor: Int, isActive: Boolean) {
    val item = findViewById<LinearLayout?>(viewId) ?: return
    val icon = item.getChildAt(0) as? ImageView ?: return
    val label = item.getChildAt(1) as? TextView
    
    icon.animate().cancel()
    if (isActive) {
        icon.scaleX = 0.8f
        icon.scaleY = 0.8f
        icon.animate()
            .scaleX(1.15f)
            .scaleY(1.15f)
            .setDuration(150L)
            .setInterpolator(FastOutSlowInInterpolator())
            .withEndAction {
                icon.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100L)
                    .setInterpolator(android.view.animation.OvershootInterpolator())
                    .start()
            }
            .start()
    } else {
        icon.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(150L)
            .start()
    }

    val fromColor = label?.currentTextColor ?: toColor
    if (fromColor == toColor) return

    ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor).apply {
        duration = 140L
        addUpdateListener { anim ->
            val c = anim.animatedValue as Int
            icon.setColorFilter(c)
            label?.setTextColor(c)
        }
        start()
    }
}

private fun AppCompatActivity.navigateToAdminTab(tab: AdminNavTab) {
    val target = TAB_TO_ACTIVITY[tab] ?: return
    if (this::class.java == target) return

    val intent = Intent(this, target).apply {
        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
    
    // We launch the activity intent without any window animations
    startActivity(intent)
    
    // We suppress the default Android shared element fade that creates a dual bottom nav 'ghosting' effect
    overridePendingTransition(0, 0)
}
