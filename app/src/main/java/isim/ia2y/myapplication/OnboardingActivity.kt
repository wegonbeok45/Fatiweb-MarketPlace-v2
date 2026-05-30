package isim.ia2y.myapplication

import android.Manifest
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Single-screen, swipeable onboarding built on ViewPager2.
 *
 * Replaces the old three-activity flow (Onboard1/2/3). Each page is data-driven,
 * pages animate via [OnboardingPageTransformer], and the location permission is
 * still requested on first open (previously done in Onboard1).
 */
class OnboardingActivity : AppCompatActivity() {

    private data class OnboardingPage(
        val title: String,
        val description: String,
        @DrawableRes val heroImage: Int?,
        val showcaseIcons: List<Int> = emptyList()
    )

    private val pages: List<OnboardingPage> by lazy {
        listOf(
            OnboardingPage(
                title = getString(R.string.onboard_slide1_title),
                description = getString(R.string.onboard_slide1_subtitle),
                heroImage = R.drawable.onboard1image1
            ),
            OnboardingPage(
                title = getString(R.string.onboard_slide2_title),
                description = getString(R.string.onboard_slide2_subtitle),
                heroImage = null,
                showcaseIcons = listOf(
                    R.drawable.category_fashion,
                    R.drawable.category_electronics,
                    R.drawable.category_home_furniture,
                    R.drawable.category_beauty_health,
                    R.drawable.category_food_grocery,
                    R.drawable.category_sports_outdoors
                )
            ),
            OnboardingPage(
                title = getString(R.string.onboard_slide3_title),
                description = getString(R.string.onboard_slide3_subtitle),
                heroImage = R.drawable.onboard3delivery
            )
        )
    }

    private lateinit var viewPager: ViewPager2
    private lateinit var indicatorContainer: LinearLayout
    private lateinit var primaryButton: MaterialButton
    private val indicatorDots = mutableListOf<View>()

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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_onboarding)
        applyInsets()

        viewPager = findViewById(R.id.vpOnboarding)
        indicatorContainer = findViewById(R.id.layoutIndicator)
        primaryButton = findViewById(R.id.btnPrimary)

        viewPager.adapter = OnboardingAdapter(pages)
        viewPager.setPageTransformer(OnboardingPageTransformer())
        buildIndicators()
        updateForPage(0)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateForPage(position)
            }
        })

        findViewById<View>(R.id.tvSkip).setOnClickListener { finishOnboarding() }
        primaryButton.setOnClickListener {
            if (viewPager.currentItem >= pages.lastIndex) {
                finishOnboarding()
            } else {
                viewPager.currentItem += 1
            }
        }

        viewPager.post { maybeAskLocationOnFirstOpen() }
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.onboardingRoot)
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
    }

    private fun buildIndicators() {
        indicatorDots.clear()
        indicatorContainer.removeAllViews()
        val height = resources.getDimensionPixelSize(R.dimen.onboarding_indicator_height)
        val gap = resources.getDimensionPixelSize(R.dimen.space_8)
        pages.indices.forEach { index ->
            val dot = View(this)
            val params = LinearLayout.LayoutParams(0, height)
            if (index > 0) params.marginStart = gap
            dot.layoutParams = params
            indicatorContainer.addView(dot)
            indicatorDots.add(dot)
        }
    }

    private fun updateForPage(position: Int) {
        val activeWidth = resources.getDimensionPixelSize(R.dimen.onboarding_indicator_active_width)
        val dotWidth = resources.getDimensionPixelSize(R.dimen.onboarding_indicator_dot_width)
        indicatorDots.forEachIndexed { index, dot ->
            val params = dot.layoutParams as LinearLayout.LayoutParams
            params.width = if (index == position) activeWidth else dotWidth
            dot.layoutParams = params
            dot.setBackgroundResource(
                if (index == position) R.drawable.bg_home_banner_dot_active
                else R.drawable.bg_home_banner_dot_inactive
            )
        }
        primaryButton.setText(
            if (position == pages.lastIndex) R.string.onboard_start else R.string.onboard_next
        )
    }

    private fun finishOnboarding() {
        setOnboardingCompleted()
        // navigateToMainTab already plays the forward window transition; just close
        // onboarding without stacking a second animation.
        navigateToMainTab(MainActivity.Tab.HOME)
        finish()
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
            LocationHelper.fetchCurrentLocation(this@OnboardingActivity)
                .onSuccess { LocationProfileSync.saveLocation(this@OnboardingActivity, it) }
                .onFailure { Log.w("LocationFlow", "Startup location failed", it) }
        }
    }

    private class OnboardingAdapter(
        private val pages: List<OnboardingPage>
    ) : RecyclerView.Adapter<OnboardingAdapter.PageViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_onboarding_page, parent, false)
            return PageViewHolder(view)
        }

        override fun getItemCount(): Int = pages.size

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            holder.bind(pages[position])
        }

        class PageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val title: TextView = itemView.findViewById(R.id.tvOnboardTitle)
            private val description: TextView = itemView.findViewById(R.id.tvOnboardDescription)
            private val heroCard: View = itemView.findViewById(R.id.cardOnboardHero)
            private val heroImage: ImageView = itemView.findViewById(R.id.ivOnboardHero)
            private val showcase: View = itemView.findViewById(R.id.layoutOnboardShowcase)
            private val showcaseImages: List<ImageView> = listOf(
                itemView.findViewById(R.id.ivShowcase1),
                itemView.findViewById(R.id.ivShowcase2),
                itemView.findViewById(R.id.ivShowcase3),
                itemView.findViewById(R.id.ivShowcase4),
                itemView.findViewById(R.id.ivShowcase5),
                itemView.findViewById(R.id.ivShowcase6)
            )

            fun bind(page: OnboardingPage) {
                title.text = page.title
                description.text = page.description
                if (page.heroImage != null) {
                    heroCard.visibility = View.VISIBLE
                    showcase.visibility = View.GONE
                    heroImage.setImageResource(page.heroImage)
                } else {
                    heroCard.visibility = View.GONE
                    showcase.visibility = View.VISIBLE
                    showcaseImages.forEachIndexed { index, iv ->
                        page.showcaseIcons.getOrNull(index)?.let { iv.setImageResource(it) }
                    }
                }
            }
        }
    }
}
