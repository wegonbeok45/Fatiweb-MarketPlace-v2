package isim.ia2y.myapplication

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Lightweight shimmer pulse — no library dependency.
 *
 * Call [startShimmerPulse] on any skeleton container while loading, and
 * [stopShimmerPulse] when real content is ready. The `ValueAnimator` is
 * stored in the View's tag so it can be cancelled cleanly.
 *
 * Usage:
 *   shimmerContainer.startShimmerPulse()
 *   shimmerContainer.visibility = View.VISIBLE
 *   // … later …
 *   shimmerContainer.stopShimmerPulse()
 *   shimmerContainer.visibility = View.GONE
 */

private const val TAG_KEY_SHIMMER = 0x5CA1AFFE.toInt() // arbitrary non-colliding int key

fun View.startShimmerPulse() {
    // Cancel any existing animator first to avoid leaking repeats
    stopShimmerPulse()
    val animator = ValueAnimator.ofFloat(1f, 0.35f, 1f).apply {
        duration = 1100
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { this@startShimmerPulse.alpha = it.animatedValue as Float }
    }
    setTag(TAG_KEY_SHIMMER, animator)
    animator.start()
}

fun View.stopShimmerPulse() {
    (getTag(TAG_KEY_SHIMMER) as? ValueAnimator)?.cancel()
    setTag(TAG_KEY_SHIMMER, null)
    alpha = 1f
}
