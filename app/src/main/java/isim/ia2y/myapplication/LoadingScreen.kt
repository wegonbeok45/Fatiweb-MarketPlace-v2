package isim.ia2y.myapplication

import android.animation.ObjectAnimator
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.util.concurrent.Executors
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class LoadingScreen : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newFixedThreadPool(4)
    private val logTag = "LoadingScreen"

    /**
     * Each milestone has:
     *  - [label]    : string res shown in the progress label
     *  - [progress] : target progress value (0–100)
     *  - [work]     : real background task that must FINISH before the bar advances
     */
    private data class Milestone(
        val label: Int,
        val progress: Int,
        val work: (Context) -> Unit
    )

    private val milestones by lazy {
        listOf(
            Milestone(R.string.loading_step_preferences, 15) { ctx ->
                // Read all shared-preference files so they're cached in memory
                ctx.getSharedPreferences("profile_prefs", Context.MODE_PRIVATE).all
                ctx.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).all
                AddressBookStore.getAddresses(ctx)
                CartStore.getCart(ctx)
                LanguageManager.ensureDefaultAndApply(ctx)
            },
            Milestone(R.string.loading_step_language, 30) { ctx ->
                // Apply locale and read notification state
                NotificationStore.hasUnread(ctx)
                FavoritesStore.getFavorites(ctx)
            },
            Milestone(R.string.loading_step_catalog, 50) { _ ->
                // Force the entire product catalog into memory
                val catalog = ProductCatalog.all()
                // Touch every product so its fields are JIT-compiled
                catalog.forEach { product ->
                    product.id; product.title; product.price
                    product.tags; product.imageRes
                }
            },
            Milestone(R.string.loading_step_cart, 65) { ctx ->
                // Load cart + favorites so CartStore/FavoritesStore are warm
                val cartKeys = CartStore.getCart(ctx).keys
                ProductCatalog.orderedFavorites(cartKeys)
                FavoritesStore.getFavorites(ctx)
                CartStore.itemCount(ctx)
            },
            Milestone(R.string.loading_step_onboarding, 80) { ctx ->
                // Pre-read address book and any remaining prefs
                AddressBookStore.getAddresses(ctx)
                ctx.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).all
            },
            Milestone(R.string.loading_step_ready, 100) { ctx ->
                // Pre-warm ALL tab data so MainActivity starts with zero loading work
                ProductCatalog.all()
                FavoritesStore.getFavorites(ctx)
                val cartKeys = CartStore.getCart(ctx).keys
                ProductCatalog.orderedFavorites(cartKeys)
                CartStore.itemCount(ctx)
                ctx.getSharedPreferences("profile_prefs", Context.MODE_PRIVATE)
                    .getString("avatar_uri", null)
                ctx.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE).all
                NotificationStore.hasUnread(ctx)
                if (LocationHelper.hasPermission(ctx)) {
                    LocationHelper.resolveCurrentLocation(ctx) { /* fire-and-forget */ }
                }
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContentView(R.layout.activity_loading_screen)

        val rootView = findViewById<View>(R.id.layoutLoadingRoot)
        val initialPaddingLeft = rootView.paddingLeft
        val initialPaddingTop = rootView.paddingTop
        val initialPaddingRight = rootView.paddingRight
        val initialPaddingBottom = rootView.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                initialPaddingLeft + systemBars.left,
                initialPaddingTop + systemBars.top,
                initialPaddingRight + systemBars.right,
                initialPaddingBottom + systemBars.bottom
            )
            insets
        }

        revealViewsInOrder(
            R.id.ivAppLogo,
            R.id.tvAppName,
            R.id.tvTagline,
            R.id.layoutProgressContainer,
            R.id.layoutSecurityBadge
        )

        runRealMilestoneLoading()
    }

    /**
     * Runs each milestone's [Milestone.work] on a background thread.
     * Only after the work COMPLETES does the UI progress bar animate forward.
     * 100% is only reached when every real task is done — app is fully warm.
     */
    private fun runRealMilestoneLoading() {
        val progressBar = findViewById<LinearProgressIndicator>(R.id.progressBar) ?: return
        val progressText = findViewById<TextView>(R.id.tvProgressPercent) ?: return
        val stepLabel = findViewById<TextView>(R.id.tvProgressLabel) ?: return
        val ctx = applicationContext

        if (isReducedMotionEnabled()) {
            // Skip animation but still do real work on a single thread
            ioExecutor.execute {
                milestones.forEach { milestone ->
                    runCatching { milestone.work(ctx) }
                        .onFailure { Log.w(logTag, "Milestone failed: ${milestone.label}", it) }
                }
                handler.post {
                    progressBar.setProgressCompat(100, false)
                    progressText.text = getString(R.string.loading_progress_percent, 100)
                    handler.postDelayed({ navigateAway() }, 200)
                }
            }
            return
        }

        var currentIndex = 0

        fun runNext() {
            if (currentIndex >= milestones.size) {
                handler.postDelayed({ navigateAway() }, 120)
                return
            }

            val milestone = milestones[currentIndex]

            // 1. Update the label immediately so the user sees what's happening
            stepLabel.text = getString(milestone.label)

            // 2. Run the REAL work on a background thread
            ioExecutor.execute {
                runCatching { milestone.work(ctx) }
                    .onFailure { Log.w(logTag, "Milestone work failed (non-fatal)", it) }

                // 3. Only AFTER work is done, come back to the main thread and advance the bar
                handler.post {
                    if (isFinishing || isDestroyed) return@post

                    val animator = ObjectAnimator.ofInt(
                        progressBar, "progress",
                        progressBar.progress, milestone.progress
                    ).apply {
                        duration = 180L
                        interpolator = AccelerateDecelerateInterpolator()
                    }
                    animator.addUpdateListener {
                        val value = it.animatedValue as Int
                        progressText.text = getString(R.string.loading_progress_percent, value)
                    }
                    animator.doOnEnd {
                        currentIndex++
                        runNext()
                    }
                    animator.start()
                }
            }
        }

        runNext()
    }

    private fun revealViewsInOrder(
        vararg ids: Int,
        fromTranslationDp: Float = 18f,
        startDelayMs: Long = 0L,
        staggerMs: Long = 42L,
        durationMs: Long = 220L
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

    private fun navigateAway() {
        val root = findViewById<View>(R.id.layoutLoadingRoot)
        if (!isReducedMotionEnabled()) {
            root.animate()
                .alpha(0f)
                .setDuration(150L)
                .withEndAction { performNavigation() }
                .start()
        } else {
            performNavigation()
        }
    }

    private fun performNavigation() {
        if (isOnboardingCompleted()) {
            launchMainFromLoader(MainActivity.Tab.HOME)
        } else {
            navigateNoShift(Onboard1::class.java)
        }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        ioExecutor.shutdownNow()
        super.onDestroy()
    }
}
