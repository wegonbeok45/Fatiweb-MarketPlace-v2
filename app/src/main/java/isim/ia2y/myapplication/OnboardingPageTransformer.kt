package isim.ia2y.myapplication

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import kotlin.math.abs

/**
 * Gives the onboarding pager a polished feel: pages fade and scale down slightly
 * as they leave the center, and the hero illustration drifts with a gentle
 * parallax so the swipe reads as depth rather than a flat slide.
 */
class OnboardingPageTransformer : ViewPager2.PageTransformer {

    override fun transformPage(page: View, position: Float) {
        val clamped = position.coerceIn(-1f, 1f)
        val absPos = abs(clamped)

        // Fade neighbouring pages so the focused page stands out.
        page.alpha = 1f - absPos * 0.5f

        // Subtle scale so off-center pages recede.
        val scale = 1f - absPos * 0.10f
        page.scaleX = scale
        page.scaleY = scale

        // Parallax the hero area: it moves slower than the page itself.
        page.findViewById<View>(R.id.heroContainer)?.let { hero ->
            hero.translationX = -clamped * (page.width / 6f)
        }
    }
}
