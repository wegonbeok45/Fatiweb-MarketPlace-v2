package isim.ia2y.myapplication

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import kotlin.math.roundToInt

// Cette classe organise cette partie de l'app.
class HomeTabFragment : Fragment(R.layout.fragment_home_tab) {
    private val favoriteState = mutableMapOf<Int, Boolean>()
    private val sliderHandler = Handler(Looper.getMainLooper())
    private var categorySliderRunnable: Runnable? = null
    private var announcementSliderRunnable: Runnable? = null
    private var isCategoryAutoSliding = false
    private var isAnnouncementAutoSliding = false
    private var categoryScrollListener: android.view.ViewTreeObserver.OnScrollChangedListener? = null
    private var announcementScrollListener: android.view.ViewTreeObserver.OnScrollChangedListener? = null
    private var categoryCycleWidthPx: Int = 0
    private var categoryScrollOffsetPx: Float = 0f
    private var announcementScrollOffsetPx: Float = 0f
    private var announcementMaxScrollPx: Int = 0
    private var announcementDirection: Int = 1
    private var categoryLastFrameTimeMs: Long = 0L
    private var announcementLastFrameTimeMs: Long = 0L
    private val sliderSpeedPxPerSec: Float = 56f
    private val announcementSpeedPxPerSec: Float = 46f
    private val sliderFrameDelayMs: Long = 16L
    private var hasPlayedEntrance = false

    // Cette fonction fait une action de cette partie de l'app.
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View?>(R.id.layoutBottomNav)?.isGone = true
        view.findViewById<View?>(R.id.viewBottomDivider)?.isGone = true
        view.findViewById<View?>(R.id.layoutTopBar)?.isGone = true
        setupHeaderAndContentActions(view)
        setupFavoriteActions(view)
        setupCategoryAutoSlide(view)
        setupAnnouncementAutoSlide(view)
        setupMotionPolish()
    }

    // Cette fonction fait une action de cette partie de l'app.
    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.updateHostCartBadge()
        updateNotificationBadge()
        startCategoryAutoSlide()
        startAnnouncementAutoSlide()
        if (!hasPlayedEntrance) {
            hasPlayedEntrance = true
            (activity as? AppCompatActivity)?.window?.decorView?.post {
                val host = activity as? AppCompatActivity ?: return@post
                host.animateExploreEntrance(
                    topSectionId = R.id.layoutTopSection,
                    scrollId = R.id.scrollHomeContent,
                    bottomNavId = R.id.layoutBottomNav,
                    cardIds = intArrayOf(
                        R.id.cardBannerPrimary,
                        R.id.cardBannerSecondary,
                        R.id.itemCategoryArtisanat,
                        R.id.itemCategoryEpices,
                        R.id.itemCategoryVetements,
                        R.id.itemCategoryDeco,
                        R.id.itemCategoryHuiles,
                        R.id.cardProductChechia,
                        R.id.cardProductBijoux,
                        R.id.cardProductMarqoum,
                        R.id.cardProductBalgha
                    )
                )
                host.startTypingHintAnimation(
                    hintViewId = R.id.tvSearchHint,
                    fullText = getString(R.string.auto_text_005),
                    stepDelayMs = 65L,
                    R.id.layoutSearchBar, R.id.ivSearch, R.id.tvSearchHint, R.id.ivFilter
                )
            }
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    override fun onPause() {
        stopCategoryAutoSlide()
        stopAnnouncementAutoSlide()
        super.onPause()
    }

    // Cette fonction fait une action de cette partie de l'app.
    override fun onDestroyView() {
        stopCategoryAutoSlide()
        stopAnnouncementAutoSlide()
        sliderHandler.removeCallbacksAndMessages(null)
        
        view?.findViewById<HorizontalScrollView>(R.id.hsvCategories)?.viewTreeObserver?.let { obs ->
            categoryScrollListener?.let { if (obs.isAlive) obs.removeOnScrollChangedListener(it) }
        }
        view?.findViewById<HorizontalScrollView>(R.id.hsvAnnouncements)?.viewTreeObserver?.let { obs ->
            announcementScrollListener?.let { if (obs.isAlive) obs.removeOnScrollChangedListener(it) }
        }
        categoryScrollListener = null
        announcementScrollListener = null
        super.onDestroyView()
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun setupHeaderAndContentActions(root: View) {
        root.findViewById<View>(R.id.ivHomeLogo)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.HOME)
        }
        root.findViewById<View>(R.id.tvBrand)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.HOME)
        }
        root.findViewById<View>(R.id.ivTopCart)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.CART)
        }
        listOf(
            R.id.itemCategoryArtisanat,
            R.id.itemCategoryEpices,
            R.id.itemCategoryVetements,
            R.id.itemCategoryDeco,
            R.id.itemCategoryHuiles,
            R.id.tvCategoriesSeeAll
        ).forEach { categoryId ->
            root.findViewById<View?>(categoryId)?.setOnClickListener {
                (activity as? MainActivity)?.selectTab(MainActivity.Tab.EXPLORE)
            }
        }

        (activity as? AppCompatActivity)?.bindNotificationEntry(R.id.ivTopNotifications)
        (activity as? AppCompatActivity)?.bindComingSoon(
            R.id.cardBannerPrimary,
            R.id.cardBannerSecondary,
            R.id.btnAddCartBalgha
        )
        // Cette fonction fait une action de cette partie de l'app.
        fun openSearch(source: View) {
            val host = activity as? AppCompatActivity ?: return
            source.animate()
                .scaleX(1.02f)
                .scaleY(1.02f)
                .setDuration(120L)
                .withEndAction {
                    source.scaleX = 1f
                    source.scaleY = 1f
                    host.navigateFromTop(SearchActivity::class.java)
                }
                .start()
        }
        root.findViewById<View>(R.id.layoutSearchBar)?.setOnClickListener { openSearch(it) }
        root.findViewById<View>(R.id.ivSearch)?.setOnClickListener { openSearch(root.findViewById(R.id.layoutSearchBar) ?: it) }
        root.findViewById<View>(R.id.tvSearchHint)?.setOnClickListener { openSearch(root.findViewById(R.id.layoutSearchBar) ?: it) }
        root.findViewById<View>(R.id.ivFilter)?.setOnClickListener { openSearch(root.findViewById(R.id.layoutSearchBar) ?: it) }
        bindAddToCart(root, R.id.btnAddCartChechia, "chechia")
        bindAddToCart(root, R.id.btnAddCartBijoux, "bijoux")
        bindAddToCart(root, R.id.btnAddCartMarqoum, "marqoum")
        bindAddToCart(root, R.id.btnAddCartBalgha, "balgha")

        bindProductDetailsNavigation(root, R.id.cardProductChechia, "chechia")
        bindProductDetailsNavigation(root, R.id.cardProductBijoux, "bijoux")
        bindProductDetailsNavigation(root, R.id.cardProductMarqoum, "marqoum")
        bindProductDetailsNavigation(root, R.id.cardProductBalgha, "balgha")
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun setupFavoriteActions(root: View) {
        val host = activity as? AppCompatActivity ?: return
        val favorites = listOf(
            FavoriteBinding(R.id.btnFavoriteChechia, R.id.ivFavoriteChechia, "chechia"),
            FavoriteBinding(R.id.btnFavoriteBijoux, R.id.ivFavoriteBijoux, "bijoux"),
            FavoriteBinding(R.id.btnFavoriteMarqoum, R.id.ivFavoriteMarqoum, "marqoum"),
            FavoriteBinding(R.id.btnFavoriteBalgha, R.id.ivFavoriteBalgha, "balgha")
        )

        favorites.forEach { (buttonId, iconId, productId) ->
            val product = ProductCatalog.byId(productId) ?: return@forEach
            val initial = FavoritesStore.isFavorite(requireContext(), productId)
            favoriteState[buttonId] = initial
            
            val lottieIcon = root.findViewById<com.airbnb.lottie.LottieAnimationView>(iconId)
            lottieIcon?.progress = if (initial) 1f else 0f
            
            root.findViewById<View>(buttonId)?.setOnClickListener { view ->
                view.performLightHapticFeedback()
                val nextState = FavoritesStore.toggleFavorite(requireContext(), productId)
                favoriteState[buttonId] = nextState
                
                if (nextState) {
                    lottieIcon?.apply {
                        speed = 1f
                        playAnimation()
                    }
                } else {
                    lottieIcon?.apply {
                        speed = -2f
                        playAnimation()
                    }
                }
                
                host.showToast(
                    if (nextState) getString(R.string.product_added_to_favorites, product.title)
                    else getString(R.string.product_removed_from_favorites, product.title)
                )
            }
        }
    }

    // Cette classe organise cette partie de l'app.
    private data class FavoriteBinding(
        val buttonId: Int,
        val iconId: Int,
        val productId: String
    )

    // Cette fonction fait une action de cette partie de l'app.
    private fun bindAddToCart(root: View, buttonId: Int, productId: String) {
        root.findViewById<View>(buttonId)?.setOnClickListener {
            val product = ProductCatalog.byId(productId) ?: return@setOnClickListener
            CartStore.addOne(requireContext(), productId)
            (activity as? MainActivity)?.updateHostCartBadge()
            (activity as? AppCompatActivity)?.showMotionSnackbar(
                getString(R.string.product_added_to_cart, product.title)
            )
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun bindProductDetailsNavigation(root: View, cardId: Int, productId: String) {
        root.findViewById<View>(cardId)?.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateToProductDetails(productId)
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun setupMotionPolish() {
        (activity as? AppCompatActivity)?.applyPressFeedback(
            R.id.ivHomeLogo,
            R.id.tvBrand,
            R.id.ivTopCart,
            R.id.ivTopNotifications,
            R.id.layoutSearchBar,
            R.id.ivSearch,
            R.id.ivFilter,
            R.id.cardBannerPrimary,
            R.id.cardBannerSecondary,
            R.id.itemCategoryArtisanat,
            R.id.itemCategoryEpices,
            R.id.itemCategoryVetements,
            R.id.itemCategoryDeco,
            R.id.itemCategoryHuiles,
            R.id.cardProductChechia,
            R.id.cardProductBijoux,
            R.id.cardProductMarqoum,
            R.id.cardProductBalgha,
            R.id.btnAddCartChechia,
            R.id.btnAddCartBijoux,
            R.id.btnAddCartMarqoum,
            R.id.btnAddCartBalgha,
            R.id.btnFavoriteChechia,
            R.id.btnFavoriteBijoux,
            R.id.btnFavoriteMarqoum,
            R.id.btnFavoriteBalgha
        )
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun setupCategoryAutoSlide(root: View) {
        val categoriesScroll = root.findViewById<HorizontalScrollView>(R.id.hsvCategories) ?: return
        categoriesScroll.post {
            categoryCycleWidthPx = computeLoopCycleWidth(categoriesScroll)
            categoryScrollOffsetPx = categoriesScroll.scrollX.toFloat()
            categorySliderRunnable = object : Runnable {
                // Cette fonction fait une action de cette partie de l'app.
                override fun run() {
                    val content = categoriesScroll.getChildAt(0) ?: return
                    val cycleWidth = if (categoryCycleWidthPx > 0) {
                        categoryCycleWidthPx
                    } else {
                        (content.width / 2).coerceAtLeast(0)
                    }
                    if (cycleWidth == 0) {
                        sliderHandler.postDelayed(this, 120L)
                        return
                    }

                    val now = SystemClock.uptimeMillis()
                    if (categoryLastFrameTimeMs == 0L) categoryLastFrameTimeMs = now
                    val deltaMs = (now - categoryLastFrameTimeMs).coerceIn(1L, 48L)
                    categoryLastFrameTimeMs = now

                    categoryScrollOffsetPx += (sliderSpeedPxPerSec * deltaMs) / 1000f
                    while (categoryScrollOffsetPx >= cycleWidth) {
                        categoryScrollOffsetPx -= cycleWidth
                    }

                    categoriesScroll.scrollTo(categoryScrollOffsetPx.roundToInt(), 0)
                    sliderHandler.postDelayed(this, sliderFrameDelayMs)
                }
            }
            categoriesScroll.overScrollMode = View.OVER_SCROLL_NEVER
            categoryScrollListener = android.view.ViewTreeObserver.OnScrollChangedListener {
                val cycleWidth = if (categoryCycleWidthPx > 0) categoryCycleWidthPx else 1
                val currentX = categoriesScroll.scrollX
                
                // Live Looping: Keep the scroll position in the "Goldilocks Zone" (middle of the buffer)
                // If we get too far right, teleport left by one cycleWidth.
                // If we get too far left, teleport right by one cycleWidth.
                if (currentX > cycleWidth * 1.5) {
                    categoriesScroll.scrollX = (currentX - cycleWidth)
                } else if (currentX < cycleWidth * 0.5) {
                    categoriesScroll.scrollX = (currentX + cycleWidth)
                }

                if (!isCategoryAutoSliding) {
                     // Sync offset while user is dragging or flinging so resumption is smooth
                     categoryScrollOffsetPx = categoriesScroll.scrollX.toFloat()
                }
            }
            categoriesScroll.viewTreeObserver.addOnScrollChangedListener(categoryScrollListener)
            categoriesScroll.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                        stopCategoryAutoSlide()
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        waitForCategoryIdle(categoriesScroll)
                    }
                }
                false
            }
            startCategoryAutoSlide()
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun setupAnnouncementAutoSlide(root: View) {
        val announcementsScroll = root.findViewById<HorizontalScrollView>(R.id.hsvAnnouncements) ?: return
        announcementsScroll.post {
            val content = announcementsScroll.getChildAt(0)
            announcementMaxScrollPx = ((content?.width ?: 0) - announcementsScroll.width).coerceAtLeast(0)
            announcementScrollOffsetPx = 0f
            announcementDirection = 1
            announcementsScroll.scrollTo(0, 0)
            announcementSliderRunnable = object : Runnable {
                // Cette fonction fait une action de cette partie de l'app.
                override fun run() {
                    if (announcementMaxScrollPx <= 0) {
                        val c = announcementsScroll.getChildAt(0)
                        announcementMaxScrollPx = ((c?.width ?: 0) - announcementsScroll.width).coerceAtLeast(0)
                        sliderHandler.postDelayed(this, 120L)
                        return
                    }

                    val now = SystemClock.uptimeMillis()
                    if (announcementLastFrameTimeMs == 0L) announcementLastFrameTimeMs = now
                    val deltaMs = (now - announcementLastFrameTimeMs).coerceIn(1L, 48L)
                    announcementLastFrameTimeMs = now

                    announcementScrollOffsetPx += announcementDirection * (announcementSpeedPxPerSec * deltaMs) / 1000f
                    if (announcementScrollOffsetPx >= announcementMaxScrollPx) {
                        announcementScrollOffsetPx = announcementMaxScrollPx.toFloat()
                        announcementDirection = -1
                    } else if (announcementScrollOffsetPx <= 0f) {
                        announcementScrollOffsetPx = 0f
                        announcementDirection = 1
                    }

                    announcementsScroll.scrollTo(announcementScrollOffsetPx.roundToInt(), 0)
                    sliderHandler.postDelayed(this, sliderFrameDelayMs)
                }
            }
            announcementScrollListener = android.view.ViewTreeObserver.OnScrollChangedListener {
                if (!isAnnouncementAutoSliding) {
                    announcementScrollOffsetPx = announcementsScroll.scrollX.toFloat()
                }
            }
            announcementsScroll.viewTreeObserver.addOnScrollChangedListener(announcementScrollListener)
            announcementsScroll.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE -> {
                        stopAnnouncementAutoSlide()
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        waitForAnnouncementIdle(announcementsScroll)
                    }
                }
                false
            }
            startAnnouncementAutoSlide()
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun startCategoryAutoSlide() {
        val runnable = categorySliderRunnable ?: return
        sliderHandler.removeCallbacks(runnable)
        isCategoryAutoSliding = true
        
        // Sync offset with current scroll position to prevent "jumping"
        val hsv = view?.findViewById<HorizontalScrollView>(R.id.hsvCategories)
        if (hsv != null) {
            categoryScrollOffsetPx = hsv.scrollX.toFloat()
        }
        
        categoryLastFrameTimeMs = 0L
        sliderHandler.postDelayed(runnable, 100)
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun stopCategoryAutoSlide() {
        categorySliderRunnable?.let { sliderHandler.removeCallbacks(it) }
        isCategoryAutoSliding = false
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun startAnnouncementAutoSlide() {
        val runnable = announcementSliderRunnable ?: return
        sliderHandler.removeCallbacks(runnable)
        isAnnouncementAutoSliding = true

        // Sync offset with current scroll position to prevent "jumping"
        val hsv = view?.findViewById<HorizontalScrollView>(R.id.hsvAnnouncements)
        if (hsv != null) {
            announcementScrollOffsetPx = hsv.scrollX.toFloat()
        }

        announcementLastFrameTimeMs = 0L
        sliderHandler.postDelayed(runnable, 100)
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun stopAnnouncementAutoSlide() {
        announcementSliderRunnable?.let { sliderHandler.removeCallbacks(it) }
        isAnnouncementAutoSliding = false
    }

    private var categoryIdleRunnable: Runnable? = null
    private var announcementIdleRunnable: Runnable? = null

    // Cette fonction fait une action de cette partie de l'app.
    private fun waitForCategoryIdle(hsv: HorizontalScrollView) {
        categoryIdleRunnable?.let { sliderHandler.removeCallbacks(it) }
        var lastX = hsv.scrollX
        categoryIdleRunnable = object : Runnable {
            // Cette fonction fait une action de cette partie de l'app.
            override fun run() {
                val currentX = hsv.scrollX
                if (currentX == lastX) {
                    startCategoryAutoSlide()
                    categoryIdleRunnable = null
                } else {
                    lastX = currentX
                    sliderHandler.postDelayed(this, 100)
                }
            }
        }
        sliderHandler.postDelayed(categoryIdleRunnable!!, 100)
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun waitForAnnouncementIdle(hsv: HorizontalScrollView) {
        announcementIdleRunnable?.let { sliderHandler.removeCallbacks(it) }
        var lastX = hsv.scrollX
        announcementIdleRunnable = object : Runnable {
            // Cette fonction fait une action de cette partie de l'app.
            override fun run() {
                val currentX = hsv.scrollX
                if (currentX == lastX) {
                    startAnnouncementAutoSlide()
                    announcementIdleRunnable = null
                } else {
                    lastX = currentX
                    sliderHandler.postDelayed(this, 100)
                }
            }
        }
        sliderHandler.postDelayed(announcementIdleRunnable!!, 100)
    }


    // Cette fonction fait une action de cette partie de l'app.
    private fun computeLoopCycleWidth(scroll: HorizontalScrollView): Int {
        val track = scroll.getChildAt(0) as? ViewGroup ?: return 0
        val childCount = track.childCount
        if (childCount <= 1) return 0
        val halfCount = childCount / 2
        if (halfCount == 0) return 0

        var widthPx = 0
        for (i in 0 until halfCount) {
            val child = track.getChildAt(i)
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
            widthPx += child.width
            widthPx += lp?.leftMargin ?: 0
            widthPx += lp?.rightMargin ?: 0
        }
        return widthPx.coerceAtLeast(0)
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun updateNotificationBadge() {
        val badge = view?.findViewById<View>(R.id.notificationBadge) ?: return
        badge.visibility = if (NotificationStore.hasUnread(requireContext())) View.VISIBLE else View.GONE
    }
}
