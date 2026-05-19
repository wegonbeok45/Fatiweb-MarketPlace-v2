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
        if (isStableChromeId(id)) {
            view.alpha = 1f
            view.translationX = 0f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            view.visibility = View.VISIBLE
            return@forEachIndexed
        }
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

private fun AppCompatActivity.isStableChromeId(@IdRes id: Int): Boolean {
    val name = runCatching { resources.getResourceEntryName(id) }.getOrDefault("")
    return name.contains("TopBar", ignoreCase = true) ||
        name.contains("AppBar", ignoreCase = true)
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

/**
 * Routes generic snackbar calls to the typed feedback system, auto-classifying by message
 * heuristic so legacy callers light up in red/green without per-site updates.
 */
fun AppCompatActivity.showMotionSnackbar(message: String, @IdRes anchorId: Int? = null) {
    showFeedback(message, classifyFeedback(message), anchorId)
}

private fun classifyFeedback(message: String): FeedbackType {
    val m = message.lowercase()
    val isError = ERROR_HINTS.any { m.contains(it) }
    if (isError) return FeedbackType.ERROR
    val isSuccess = SUCCESS_HINTS.any { m.contains(it) }
    if (isSuccess) return FeedbackType.SUCCESS
    return FeedbackType.INFO
}

private val ERROR_HINTS = listOf(
    "impossible", "echec", "échec", "erreur", "invalide", "refus", "refuse",
    "denied", "failed", "error", "unauthor", "interdit", "non autoris",
    "indisponible", "introuvable", "expir", "incorrect", "manquant",
    "permission", "perdu", "perdue",
    // Negation phrases — force ERROR even if a success keyword appears later
    "ne peut pas", "n a pas pu", "n'a pas pu", "n'avons pas", "n avons pas",
    "pas pu", "couldn't", "can't", "cannot", "cant "
)
private val SUCCESS_HINTS = listOf(
    "succes", "succès", "réussi", "reussi", "enregistr", "ajout",
    "publi", "mis à jour", "mis a jour", "envoyé", "envoyée", "envoye",
    "supprim", "saved", "added", "updated", "deleted", "published",
    "sent", "applied", "confirmée", "confirmee", "confirmed", "validé",
    "valide"
)
