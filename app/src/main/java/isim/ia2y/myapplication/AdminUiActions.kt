package isim.ia2y.myapplication

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

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

/**
 * Sets up the 6-tab admin bottom nav, selects the active tab, slides the pill
 * indicator to the active item, and wires navigation to the correct activities.
 */
fun AppCompatActivity.setupAdminBottomNav(activeTab: AdminNavTab) {
    // Map each tab to its nav item view ID
    val tabToNavId = mapOf(
        AdminNavTab.DASHBOARD      to R.id.adminNavDashboard,
        AdminNavTab.COMMANDES      to R.id.adminNavCommandes,
        AdminNavTab.PRODUITS       to R.id.adminNavProduits,
        AdminNavTab.CLIENTS        to R.id.adminNavClients,
        AdminNavTab.NOTIFICATIONS  to R.id.adminNavNotifications,
        AdminNavTab.SETTINGS       to R.id.adminNavSettings
    )

    val activeColor   = ContextCompat.getColor(this, R.color.colorPrimary)
    val inactiveColor = ContextCompat.getColor(this, R.color.home_nav_inactive)

    // Apply active/inactive tint immediately
    tabToNavId.forEach { (tab, viewId) ->
        val isActive = tab == activeTab
        setAdminNavItemColor(viewId, if (isActive) activeColor else inactiveColor)
    }

    // Slide pill to the active tab position (immediate, no animation on first mount)
    snapAdminPillTo(tabToNavId[activeTab] ?: R.id.adminNavDashboard)

    // Wire click listeners
    tabToNavId.forEach { (tab, viewId) ->
        findViewById<View?>(viewId)?.setOnClickListener {
            if (tab == activeTab) return@setOnClickListener
            animateAdminNavAndNavigate(tab, activeTab, tabToNavId)
        }
    }

    // Press feedback on all tabs
    applyPressFeedback(*tabToNavId.values.toIntArray())
}

private fun AppCompatActivity.snapAdminPillTo(@IdRes targetNavId: Int) {
    val indicator = findViewById<View?>(R.id.admin_nav_indicator) ?: return
    val target = findViewById<View?>(targetNavId) ?: return

    target.post {
        val navRoot = target.parent as? View ?: return@post
        val targetRect = android.graphics.Rect()
        target.getGlobalVisibleRect(targetRect)
        val navRect = android.graphics.Rect()
        navRoot.getGlobalVisibleRect(navRect)
        val targetCenterX = targetRect.centerX() - navRect.left
        val pillHalfWidth = indicator.width / 2
        indicator.translationX = (targetCenterX - pillHalfWidth - indicator.left).toFloat()
    }
}

private fun AppCompatActivity.animateAdminNavAndNavigate(
    tab: AdminNavTab,
    currentTab: AdminNavTab,
    tabToNavId: Map<AdminNavTab, Int>
) {
    val activeColor   = ContextCompat.getColor(this, R.color.colorPrimary)
    val inactiveColor = ContextCompat.getColor(this, R.color.home_nav_inactive)
    val reducedMotion = isReducedMotionEnabled()

    // Color transition
    tabToNavId.forEach { (t, viewId) ->
        val isActive = t == tab
        if (reducedMotion) {
            setAdminNavItemColor(viewId, if (isActive) activeColor else inactiveColor)
        } else {
            animateAdminNavItemColor(viewId, if (isActive) activeColor else inactiveColor)
        }
    }

    // Slide the pill
    val targetId = tabToNavId[tab] ?: return
    animateAdminPillTo(targetId)

    // Navigate after a small delay so the animation is visible
    findViewById<View?>(targetId)?.postDelayed({
        runCatching { navigateToAdminTab(tab) }
            .onFailure { showToast(getString(R.string.coming_soon)) }
    }, 100L)
}

private fun AppCompatActivity.animateAdminPillTo(@IdRes targetNavId: Int) {
    if (isReducedMotionEnabled()) {
        snapAdminPillTo(targetNavId)
        return
    }
    val indicator = findViewById<View?>(R.id.admin_nav_indicator) ?: return
    val target = findViewById<View?>(targetNavId) ?: return

    target.post {
        val navRoot = target.parent as? View ?: return@post
        val targetRect = android.graphics.Rect()
        target.getGlobalVisibleRect(targetRect)
        val navRect = android.graphics.Rect()
        navRoot.getGlobalVisibleRect(navRect)
        val targetCenterX = targetRect.centerX() - navRect.left
        val pillHalfWidth = indicator.width / 2
        val toTranslation = (targetCenterX - pillHalfWidth - indicator.left).toFloat()

        indicator.animate()
            .translationX(toTranslation)
            .setDuration(250L)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }
}

private fun AppCompatActivity.setAdminNavItemColor(@IdRes viewId: Int, color: Int) {
    val item = findViewById<LinearLayout?>(viewId) ?: return
    val icon = item.getChildAt(0) as? ImageView
    val label = item.getChildAt(1) as? TextView
    icon?.setColorFilter(color)
    label?.setTextColor(color)
}

private fun AppCompatActivity.animateAdminNavItemColor(@IdRes viewId: Int, toColor: Int) {
    val item = findViewById<LinearLayout?>(viewId) ?: return
    val icon = item.getChildAt(0) as? ImageView ?: return
    val label = item.getChildAt(1) as? TextView
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
    val target: Class<*> = when (tab) {
        AdminNavTab.DASHBOARD     -> AdminDashboardActivity::class.java
        AdminNavTab.COMMANDES     -> AdminCommandesActivity::class.java
        AdminNavTab.PRODUITS      -> AdminProduitsActivity::class.java
        AdminNavTab.CLIENTS       -> AdminClientsActivity::class.java
        AdminNavTab.NOTIFICATIONS -> AdminNotificationsActivity::class.java
        AdminNavTab.SETTINGS      -> AdminParametresActivity::class.java
    }
    if (this::class.java == target) return
    navigateNoShift(target as Class<out android.app.Activity>)
}
