package isim.ia2y.myapplication

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.snackbar.Snackbar

internal object MotionTokens {
    const val QUICK = 140L
    const val STANDARD = 220L
    const val EMPHASIS = 320L
}

private fun Context.prefersReducedMotion(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
        return true
    }
    val durationScale = runCatching {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    return durationScale == 0f
}

fun AppCompatActivity.isReducedMotionEnabled(): Boolean = prefersReducedMotion()

fun View.performLightHapticFeedback() {
    performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            HapticFeedbackConstants.KEYBOARD_TAP
        else
            HapticFeedbackConstants.KEYBOARD_TAP,
        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
    )
}

fun View.applyPressFeedback() {
    stateListAnimator = null
    setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                v.performLightHapticFeedback()
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(MotionTokens.QUICK)
                    .setInterpolator(FastOutSlowInInterpolator()).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(MotionTokens.QUICK)
                    .setInterpolator(FastOutSlowInInterpolator()).start()
            }
        }
        false
    }
}

fun AppCompatActivity.applyPressFeedback(
    vararg ids: Int,
    pressedScale: Float = 0.97f
) {
    val reducedMotion = isReducedMotionEnabled()
    ids.forEach { viewId ->
        val view = findViewById<View?>(viewId) ?: return@forEach
        view.isClickable = true
        view.isFocusable = true
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.performLightHapticFeedback()
                    if (reducedMotion) {
                        view.alpha = 0.92f
                    } else {
                        view.animate()
                            .scaleX(pressedScale)
                            .scaleY(pressedScale)
                            .alpha(0.94f)
                            .setDuration(MotionTokens.QUICK)
                            .setInterpolator(AccelerateDecelerateInterpolator())
                            .start()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (reducedMotion) {
                        view.alpha = 1f
                    } else {
                        view.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(MotionTokens.QUICK)
                            .setInterpolator(FastOutSlowInInterpolator())
                            .start()
                    }
                }
            }
            false
        }
    }
}

fun AppCompatActivity.animateExploreEntrance(
    @IdRes topSectionId: Int,
    @IdRes scrollId: Int,
    @IdRes bottomNavId: Int,
    vararg cardIds: Int
) {
    if (isReducedMotionEnabled()) return

    val topSection = findViewById<View?>(topSectionId)
    val scroll = findViewById<View?>(scrollId)
    val bottomNav = findViewById<View?>(bottomNavId)
    val cards = cardIds.map { findViewById<View?>(it) }.filterNotNull()

    val interpolator = FastOutSlowInInterpolator()

    topSection?.apply {
        alpha = 0f
        translationY = -22f
        animate().alpha(1f).translationY(0f).setDuration(MotionTokens.STANDARD).setInterpolator(interpolator).start()
    }

    scroll?.apply {
        alpha = 0f
        translationY = 30f
        animate().alpha(1f).translationY(0f).setStartDelay(70L).setDuration(MotionTokens.EMPHASIS)
            .setInterpolator(interpolator).start()
    }

    bottomNav?.apply {
        alpha = 0f
        translationY = 26f
        animate().alpha(1f).translationY(0f).setStartDelay(110L).setDuration(MotionTokens.STANDARD)
            .setInterpolator(interpolator).start()
    }

    cards.forEachIndexed { index, card ->
        card.alpha = 0f
        card.translationY = 20f
        card.animate().alpha(1f).translationY(0f)
            .setStartDelay(90L + (index * 35L))
            .setDuration(MotionTokens.STANDARD)
            .setInterpolator(interpolator)
            .start()
    }
}

fun AppCompatActivity.revealViewsInOrder(
    vararg ids: Int,
    fromTranslationDp: Float = 18f,
    startDelayMs: Long = 0L,
    staggerMs: Long = 42L,
    durationMs: Long = MotionTokens.STANDARD
) {
    if (isReducedMotionEnabled()) return

    val distance = fromTranslationDp * resources.displayMetrics.density
    val interpolator = FastOutSlowInInterpolator()
    ids.forEachIndexed { index, id ->
        val view = findViewById<View?>(id) ?: return@forEachIndexed
        view.alpha = 0f
        view.translationY = distance
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(startDelayMs + (index * staggerMs))
            .setDuration(durationMs)
            .setInterpolator(interpolator)
            .start()
    }
}

fun AppCompatActivity.revealSingleView(
    @IdRes id: Int,
    fromTranslationDp: Float = 14f,
    durationMs: Long = MotionTokens.STANDARD
) {
    if (isReducedMotionEnabled()) return

    val view = findViewById<View?>(id) ?: return
    val distance = fromTranslationDp * resources.displayMetrics.density
    view.alpha = 0f
    view.translationY = distance
    view.animate()
        .alpha(1f)
        .translationY(0f)
        .setDuration(durationMs)
        .setInterpolator(FastOutSlowInInterpolator())
        .start()
}

fun AppCompatActivity.forceViewsFullyVisible(vararg ids: Int) {
    ids.forEach { id ->
        findViewById<View?>(id)?.apply {
            alpha = 1f
            translationX = 0f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
            visibility = View.VISIBLE
        }
    }
}

fun AppCompatActivity.animateListItemEntry(
    item: View,
    index: Int,
    startDelayMs: Long = 70L
) {
    if (isReducedMotionEnabled()) return

    item.alpha = 0f
    item.translationY = 16f * resources.displayMetrics.density
    item.animate()
        .alpha(1f)
        .translationY(0f)
        .setStartDelay(startDelayMs + (index * 26L))
        .setDuration(MotionTokens.STANDARD)
        .setInterpolator(FastOutSlowInInterpolator())
        .start()
}

fun AppCompatActivity.emphasizeCta(@IdRes id: Int, delayMs: Long = 260L) {
    if (isReducedMotionEnabled()) return
    val view = findViewById<View?>(id) ?: return
    view.postDelayed({
        view.animate()
            .scaleX(1.02f)
            .scaleY(1.02f)
            .setDuration(MotionTokens.STANDARD)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(MotionTokens.STANDARD)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .start()
            }
            .start()
    }, delayMs)
}

fun AppCompatActivity.showMotionSnackbar(message: String, @IdRes anchorId: Int? = null) {
    val root = findViewById<View>(android.R.id.content)
    val snackbar = Snackbar.make(root, message, Snackbar.LENGTH_SHORT)
    anchorId?.let { snackbar.anchorView = findViewById(it) }
    snackbar.show()

    if (!isReducedMotionEnabled()) {
        snackbar.view.alpha = 0f
        snackbar.view.translationY = 20f * resources.displayMetrics.density
        snackbar.view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(MotionTokens.EMPHASIS)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }
}
