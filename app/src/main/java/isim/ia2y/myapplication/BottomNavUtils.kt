package isim.ia2y.myapplication

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

fun AppCompatActivity.bindBottomNav(
    @IdRes homeId: Int,
    @IdRes exploreId: Int,
    @IdRes favoritesId: Int? = null,
    @IdRes cartId: Int,
    @IdRes profileId: Int
) {
    val navIds = listOfNotNull(homeId, exploreId, favoritesId, cartId, profileId)

    findViewById<View?>(homeId)?.setOnClickListener {
        animateBottomNavAndNavigate(navIds, homeId, MainActivity::class.java) {
            navigateToMainTab(MainActivity.Tab.HOME)
        }
    }
    findViewById<View?>(exploreId)?.setOnClickListener {
        animateBottomNavAndNavigate(navIds, exploreId, MainActivity::class.java) {
            navigateToMainTab(MainActivity.Tab.EXPLORE)
        }
    }
    favoritesId?.let { id ->
        findViewById<View?>(id)?.setOnClickListener {
            animateBottomNavAndNavigate(navIds, id, FavoritesActivity::class.java)
        }
    }
    findViewById<View?>(cartId)?.setOnClickListener {
        animateBottomNavAndNavigate(navIds, cartId, MainActivity::class.java) {
            navigateToMainTab(MainActivity.Tab.CART)
        }
    }
    findViewById<View?>(profileId)?.setOnClickListener {
        animateBottomNavAndNavigate(navIds, profileId, MainActivity::class.java) {
            navigateToMainTab(MainActivity.Tab.PROFILE)
        }
    }
}

fun AppCompatActivity.applyBottomNavSelection(
    @IdRes selectedId: Int,
    @IdRes homeId: Int,
    @IdRes exploreId: Int,
    @IdRes favoritesId: Int? = null,
    @IdRes cartId: Int,
    @IdRes profileId: Int
) {
    val navIds = listOfNotNull(homeId, exploreId, favoritesId, cartId, profileId)
    val activeColor = ContextCompat.getColor(this, R.color.profile_nav_active)
    val inactiveColor = ContextCompat.getColor(this, R.color.profile_nav_inactive)
    navIds.forEach { id ->
        setNavItemColorImmediate(id, if (id == selectedId) activeColor else inactiveColor)
    }
}

private fun AppCompatActivity.animateBottomNavAndNavigate(
    navIds: List<Int>,
    selectedId: Int,
    target: Class<out Activity>,
    navigateOverride: (() -> Unit)? = null
) {
    if (this::class.java == target) return

    val activeColor = ContextCompat.getColor(this, R.color.profile_nav_active)
    val inactiveColor = ContextCompat.getColor(this, R.color.profile_nav_inactive)

    navIds.forEach { id ->
        val item = findViewById<View?>(id) ?: return@forEach
        animateNavItemColor(item, if (id == selectedId) activeColor else inactiveColor)
    }

    findViewById<View?>(selectedId)?.postDelayed({
        runCatching {
            navigateOverride?.invoke() ?: navigateNoShift(target)
        }.onFailure { error ->
            Log.e("UiActionsNav", "Bottom nav navigation failed for target=${target.simpleName}", error)
            showToast(getString(R.string.coming_soon))
        }
    }, 90L)
}

private fun AppCompatActivity.setNavItemColorImmediate(@IdRes itemId: Int, color: Int) {
    val item = findViewById<View?>(itemId) as? LinearLayout ?: return
    val firstChild = item.getChildAt(0)
    val icon = when (firstChild) {
        is ImageView -> firstChild
        is android.view.ViewGroup -> {
            (0 until firstChild.childCount)
                .mapNotNull { idx -> firstChild.getChildAt(idx) as? ImageView }
                .firstOrNull()
        }
        else -> null
    }
    val label = item.getChildAt(1) as? TextView
    label?.setTextColor(color)
    icon?.setColorFilter(color)
}

private fun animateNavItemColor(item: View, toColor: Int) {
    val navItem = item as? LinearLayout ?: return
    val firstChild = navItem.getChildAt(0)
    val icon = when (firstChild) {
        is ImageView -> firstChild
        is android.view.ViewGroup -> {
            (0 until firstChild.childCount)
                .mapNotNull { idx -> firstChild.getChildAt(idx) as? ImageView }
                .firstOrNull()
        }
        else -> null
    }
    val label = navItem.getChildAt(1) as? TextView
    val fromColor = label?.currentTextColor ?: toColor
    if (fromColor == toColor) return

    ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor).apply {
        duration = MotionTokens.QUICK
        addUpdateListener { animator ->
            val color = animator.animatedValue as Int
            label?.setTextColor(color)
            icon?.setColorFilter(color)
        }
        start()
    }
}

fun AppCompatActivity.updateBottomCartBadge(
    @IdRes badgeContainerId: Int = R.id.cardBottomCartBadge,
    @IdRes badgeTextId: Int = R.id.tvBottomCartBadge
) {
    val badgeContainer = findViewById<View?>(badgeContainerId) ?: return
    val badgeText = findViewById<TextView?>(badgeTextId) ?: return
    val count = CartStore.itemCount(this)
    if (count <= 0) {
        badgeContainer.visibility = View.GONE
        return
    }

    badgeContainer.visibility = View.VISIBLE
    badgeText.text = count.toString()
}
