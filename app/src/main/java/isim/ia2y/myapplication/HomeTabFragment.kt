package isim.ia2y.myapplication

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.viewpager2.widget.ViewPager2
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class HomeTabFragment : Fragment(R.layout.fragment_home_tab), TabReselectionHandler {
    private var featuredEmptyMessage: String? = null
    private var hasPlayedEntrance = false
    private var latestEmptyMessage: String? = null
    private var discoverEmptyMessage: String? = null
    private var searchHintRunnable: Runnable? = null
    private var searchHintTargetView: TextView? = null
    private var messageUnreadListener: ListenerRegistration? = null
    private var renderJob: Job? = null
    private var heroAutoScrollJob: Job? = null
    private var heroPageCallback: ViewPager2.OnPageChangeCallback? = null
    private val sectionDisplayJobs = mutableMapOf<Int, Job>()

    private data class HomeRenderData(
        val catalogIsEmpty: Boolean,
        val featured: List<Product>,
        val latest: List<Product>,
        val discovery: List<Product>,
        val syncState: CatalogSyncState
    )

    private val latestProductsAdapter by lazy {
        HomeCatalogAdapter(
            onToggleFavorite = ::toggleFavorite,
            onOpenProduct = ::openProduct
        )
    }

    private val featuredProductsAdapter by lazy {
        HomeCatalogAdapter(
            onToggleFavorite = ::toggleFavorite,
            onOpenProduct = ::openProduct
        )
    }

    private val discoverProductsAdapter by lazy {
        HomeCatalogAdapter(
            onToggleFavorite = ::toggleFavorite,
            onOpenProduct = ::openProduct
        )
    }

    private val categoriesAdapter by lazy {
        HomeCategoryCarouselAdapter(homeCategoryItems(), ::openCuratedSearch)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View?>(R.id.layoutBottomNav)?.isGone = true
        view.findViewById<View?>(R.id.viewBottomDivider)?.isGone = true
        setupCatalogSections(view)
        setupCategoryCarousel(view)
        refreshMarketplaceCategories()
        renderCatalogSections(view)
        setupHomeHeroCarousel(view)
        bindCuratorEditorialImages(view)
        setupHeaderAndContentActions(view)
        setupHomeRefresh(view)
        listenForMessageUnread()
        setupCollapsingHeader(view)
        setupMotionPolish()
        startSearchHintTyping(view)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CatalogSyncManager.syncState.collect { state ->
                    featuredEmptyMessage = getString(R.string.home_section_empty)
                    latestEmptyMessage = getString(R.string.home_section_empty)
                    discoverEmptyMessage = getString(R.string.home_section_empty)
                    updateHomeRefreshState(state)
                    if (isAdded) {
                        renderCatalogSections(requireView())
                    }
                }
            }
        }

        val hasLocalCatalog = ProductCatalog.all(includeInactive = true).isNotEmpty()
        if (hasLocalCatalog) {
            CatalogSyncManager.refreshAsync(force = false)
        } else {
            setShimmering(true)
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching { CatalogSyncManager.ensureSynced(force = false) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.updateHostCartBadge()
        updateNotificationBadge()
        listenForMessageUnread()
        view?.let { renderCatalogSections(it) }
        if (!hasPlayedEntrance) {
            hasPlayedEntrance = true
            (activity as? AppCompatActivity)?.forceViewsFullyVisible(
                R.id.layoutTopSection,
                R.id.scrollHomeContent
            )
        }
    }

    override fun onStop() {
        messageUnreadListener?.remove()
        messageUnreadListener = null
        super.onStop()
    }

    override fun onDestroyView() {
        stopSearchHintTyping()
        renderJob?.cancel()
        renderJob = null
        sectionDisplayJobs.values.forEach { it.cancel() }
        sectionDisplayJobs.clear()
        heroAutoScrollJob?.cancel()
        heroAutoScrollJob = null
        heroPageCallback?.let { callback ->
            view?.findViewById<ViewPager2?>(R.id.viewPagerHomeHero)?.unregisterOnPageChangeCallback(callback)
        }
        heroPageCallback = null
        messageUnreadListener?.remove()
        messageUnreadListener = null
        super.onDestroyView()
    }

    private fun setupCatalogSections(root: View) {
        setupGrid(root.findViewById(R.id.rvFeaturedProducts), featuredProductsAdapter, spanCount = 2)
        setupGrid(root.findViewById(R.id.rvLatestProducts), latestProductsAdapter, spanCount = 2)
        setupGrid(root.findViewById(R.id.rvDiscoverProducts), discoverProductsAdapter, spanCount = 2)
    }

    private fun setupGrid(recycler: RecyclerView?, adapter: HomeCatalogAdapter, spanCount: Int) {
        recycler ?: return
        recycler.layoutManager = GridLayoutManager(requireContext(), spanCount)
        recycler.adapter = adapter
        recycler.isNestedScrollingEnabled = false
        if (recycler.itemDecorationCount == 0) {
            recycler.addItemDecoration(
                HomeCatalogSpacingDecoration(
                    horizontalSpacing = resources.getDimensionPixelSize(R.dimen.home_products_column_gap),
                    verticalSpacing = resources.getDimensionPixelSize(R.dimen.home_products_row_gap)
                )
            )
        }
    }

    private fun setupHorizontalRow(recycler: RecyclerView?, adapter: HomeCatalogAdapter) {
        recycler ?: return
        recycler.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        recycler.adapter = adapter
        recycler.isNestedScrollingEnabled = false
        recycler.clipToPadding = false
        recycler.overScrollMode = View.OVER_SCROLL_NEVER
        if (recycler.itemDecorationCount == 0) {
            recycler.addItemDecoration(HomeHorizontalSpacingDecoration(resources.getDimensionPixelSize(R.dimen.home_products_column_gap)))
        }
    }

    private fun setupCategoryCarousel(root: View) {
        val recycler = root.findViewById<RecyclerView>(R.id.rvCategories) ?: return
        recycler.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        recycler.adapter = categoriesAdapter
        recycler.isNestedScrollingEnabled = false
        recycler.clipToPadding = false
        recycler.overScrollMode = View.OVER_SCROLL_NEVER
        if (recycler.itemDecorationCount == 0) {
            recycler.addItemDecoration(HomeHorizontalSpacingDecoration(resources.getDimensionPixelSize(R.dimen.home_ref_category_gap)))
        }
        root.findViewById<View?>(R.id.tvCategoriesSeeAll)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.EXPLORE)
        }
    }

    private fun homeCategoryItems(): List<HomeCategoryItem> =
        MarketplaceCategories.featuredItems.map { category ->
            HomeCategoryItem(
                label = category.name,
                imageUrl = category.imageUrl,
                imageResId = MarketplaceCategories.imageResFor(category.id),
                categoryKey = category.id,
                badgeIconResId = badgeIconForCategory(category.id)
            )
        }

    private fun refreshMarketplaceCategories() {
        categoriesAdapter.submitList(homeCategoryItems())
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { MarketplaceCategories.refreshFromFirestore() }
                .onSuccess { categoriesAdapter.submitList(homeCategoryItems()) }
        }
    }

    private fun badgeIconForCategory(categoryKey: String): Int {
        val normalized = MarketplaceCategories.normalizeKey(categoryKey)
        return when (MarketplaceCategories.categoryFor(normalized)?.topLevelId ?: normalized) {
            "fashion", "baby-and-toys" -> R.drawable.ic_home_category_shirt
            "home-and-furniture", "real-estate", "business-and-industrial" -> R.drawable.ic_home_category_lamp
            "beauty-and-health", "food-and-grocery", "sports-and-outdoors", "pets" -> R.drawable.ic_home_category_leaf
            else -> R.drawable.ic_home_category_phone
        }
    }

    private fun bindCuratorEditorialImages(root: View) {
        root.findViewById<ImageView?>(R.id.ivFeaturedEditMain)?.setImageResource(R.drawable.curator_headphones)
        root.findViewById<ImageView?>(R.id.ivFeaturedEditLeft)?.setImageResource(R.drawable.curator_shirt_linen)
        root.findViewById<ImageView?>(R.id.ivFeaturedEditRight)?.setImageResource(R.drawable.curator_perfume)
    }

    private fun bindHomeTrustStrip(root: View) {
        bindHomeTrustBadge(
            root.findViewById(R.id.homeTrustShipping),
            R.drawable.ic_checkout_truck,
            R.string.home_trust_shipping_title,
            R.string.home_trust_shipping_body
        )
        bindHomeTrustBadge(
            root.findViewById(R.id.homeTrustSecure),
            R.drawable.ic_profile_shield_outline,
            R.string.home_trust_secure_title,
            R.string.home_trust_secure_body
        )
        bindHomeTrustBadge(
            root.findViewById(R.id.homeTrustPayment),
            R.drawable.ic_checkout_wallet,
            R.string.home_trust_payment_title,
            R.string.home_trust_payment_body
        )
        bindHomeTrustBadge(
            root.findViewById(R.id.homeTrustSupport),
            R.drawable.ic_profile_help_outline,
            R.string.home_trust_support_title,
            R.string.home_trust_support_body
        )
    }

    private fun bindHomeTrustBadge(host: View?, iconRes: Int, titleRes: Int, bodyRes: Int) {
        if (host == null) return
        host.findViewById<ImageView?>(R.id.ivHomeTrustIcon)?.setImageResource(iconRes)
        host.findViewById<TextView?>(R.id.tvHomeTrustTitle)?.setText(titleRes)
        host.findViewById<TextView?>(R.id.tvHomeTrustBody)?.setText(bodyRes)
    }

    private fun setupHomeHeroCarousel(root: View) {
        val card = root.findViewById<View>(R.id.cardBannerPrimary) ?: return
        val pager = root.findViewById<ViewPager2>(R.id.viewPagerHomeHero) ?: return
        val dots = root.findViewById<LinearLayout>(R.id.layoutBannerDots) ?: return
        val slides = homeHeroSlides()
        val adapter = HomeHeroCarouselAdapter(slides) { slide ->
            openCuratedSearch(slide.categoryKey)
        }

        fitHomeHeroCardToSquare(card)
        pager.adapter = adapter
        pager.offscreenPageLimit = 1
        setupHeroDots(dots, slides.size, selectedIndex = 0)
        heroPageCallback?.let(pager::unregisterOnPageChangeCallback)
        heroPageCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                setupHeroDots(dots, slides.size, position)
            }
        }.also(pager::registerOnPageChangeCallback)

        heroAutoScrollJob?.cancel()
        heroAutoScrollJob = viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var direction = 1
                while (true) {
                    delay(HOME_HERO_AUTO_SCROLL_MS)
                    if (adapter.itemCount < 2) continue
                    var next = pager.currentItem + direction
                    if (next >= adapter.itemCount) {
                        direction = -1
                        next = adapter.itemCount - 2
                    } else if (next < 0) {
                        direction = 1
                        next = 1
                    }
                    pager.setCurrentItem(next.coerceIn(0, adapter.itemCount - 1), true)
                }
            }
        }
    }

    private fun fitHomeHeroCardToSquare(card: View) {
        card.post {
            val width = card.width.takeIf { it > 0 } ?: return@post
            if (card.layoutParams.height != width) {
                card.layoutParams = card.layoutParams.apply {
                    height = width
                }
            }
        }
    }

    private fun setupHeroDots(container: LinearLayout, count: Int, selectedIndex: Int) {
        if (container.childCount != count) {
            container.removeAllViews()
            repeat(count) { index ->
                val dot = View(container.context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                        if (index > 0) marginStart = dp(8)
                    }
                    background = heroDotBackground(isActive = index == selectedIndex)
                }
                container.addView(dot)
            }
        }

        repeat(container.childCount) { index ->
            val isActive = index == selectedIndex
            container.getChildAt(index).apply {
                background = heroDotBackground(isActive)
                animate()
                    .scaleX(if (isActive) 1.2f else 1f)
                    .scaleY(if (isActive) 1.2f else 1f)
                    .alpha(if (isActive) 1f else 0.72f)
                    .setDuration(MotionTokens.QUICK)
                    .start()
            }
        }
    }

    private fun heroDotBackground(isActive: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                ContextCompat.getColor(
                    requireContext(),
                    if (isActive) R.color.home_ref_gold else R.color.colorBorderLight
                )
            )
        }

    private fun homeHeroSlides(): List<HomeHeroSlide> = listOf(
        HomeHeroSlide(
            imageUrl = homeHeroCloudUrl("home-hero-clothes.webp"),
            fallbackRes = R.drawable.category_fashion,
            categoryKey = "fashion",
            contentDescription = "Fashion"
        ),
        HomeHeroSlide(
            imageUrl = homeHeroCloudUrl("home-hero-clothes1.webp"),
            fallbackRes = R.drawable.category_fashion,
            categoryKey = "fashion",
            contentDescription = "Fashion"
        ),
        HomeHeroSlide(
            imageUrl = homeHeroCloudUrl("home-hero-toys.webp"),
            fallbackRes = R.drawable.category_baby_toys,
            categoryKey = "baby-and-toys",
            contentDescription = "Toys"
        ),
        HomeHeroSlide(
            imageUrl = homeHeroCloudUrl("home-hero-food.webp"),
            fallbackRes = R.drawable.category_food_grocery,
            categoryKey = "food-and-grocery",
            contentDescription = "Food"
        )
    )

    private fun homeHeroCloudUrl(fileName: String): String = when (fileName) {
        "home-hero-clothes.webp" -> "$HOME_HERO_STORAGE_PREFIX$fileName?alt=media&token=b518603d-32d5-4602-ab6f-43c9a3e52ca5"
        "home-hero-clothes1.webp" -> "$HOME_HERO_STORAGE_PREFIX$fileName?alt=media&token=9cfa8b09-c110-4b1b-bce8-27700c84643f"
        "home-hero-toys.webp" -> "$HOME_HERO_STORAGE_PREFIX$fileName?alt=media&token=b4bae960-0afd-458a-bcc5-cdd9e571e741"
        "home-hero-food.webp" -> "$HOME_HERO_STORAGE_PREFIX$fileName?alt=media&token=8d538ba1-e346-4b58-96f8-21510b297e20"
        else -> "$HOME_HERO_STORAGE_PREFIX$fileName?alt=media"
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun setupHomeRefresh(root: View) {
        root.findViewById<SwipeRefreshLayout?>(R.id.homeSwipeRefresh)?.apply {
            setColorSchemeResources(R.color.colorAccentDeep, R.color.colorPrimary)
            setProgressBackgroundColorSchemeResource(R.color.colorSurface)
            setOnRefreshListener {
                CatalogSyncManager.refreshAsync(force = true)
            }
        }
    }

    private fun updateHomeRefreshState(state: CatalogSyncState = CatalogSyncManager.syncState.value) {
        val hasRealProducts = ProductCatalog.all(includeInactive = false).any { it.isDisplayReady }
        view?.findViewById<SwipeRefreshLayout?>(R.id.homeSwipeRefresh)?.isRefreshing =
            state.isRefreshing && hasRealProducts
    }

    private fun renderCatalogSections(root: View) {
        val syncState = CatalogSyncManager.syncState.value
        val appContext = root.context.applicationContext

        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val renderData = withContext(Dispatchers.Default) {
                val catalog = homeCatalogProducts()
                val favorites = FavoritesStore.getFavorites(appContext)
                val cart = CartStore.getCart(appContext)
                val featured = rankedHomeProducts(catalog, favorites, cart).take(4)
                val latest = latestHomeProducts(catalog).take(6)
                val discovery = discoveryHomeProducts(catalog, featured.map { it.id }.toSet()).take(4)
                HomeRenderData(
                    catalogIsEmpty = catalog.isEmpty(),
                    featured = featured,
                    latest = latest,
                    discovery = discovery,
                    syncState = syncState
                )
            }

            if (view !== root || !isAdded) return@launch
            applyCatalogRender(root, renderData)
        }
    }

    private fun applyCatalogRender(root: View, renderData: HomeRenderData) {
        (root.findViewById<RecyclerView>(R.id.rvFeaturedProducts)?.layoutManager as? GridLayoutManager)?.spanCount = 2
        (root.findViewById<RecyclerView>(R.id.rvLatestProducts)?.layoutManager as? GridLayoutManager)?.spanCount = 2
        (root.findViewById<RecyclerView>(R.id.rvDiscoverProducts)?.layoutManager as? GridLayoutManager)?.spanCount = 2

        val featured = renderData.featured
        val latest = renderData.latest
        val discovery = renderData.discovery
        val syncState = renderData.syncState
        val isInitialLoading = renderData.catalogIsEmpty && syncState.isRefreshing
        val isOfflineWithoutCache = renderData.catalogIsEmpty && syncState.status == CatalogSyncStatus.ERROR
        val isShowingSavedProducts = syncState.status == CatalogSyncStatus.ERROR && syncState.fromCache

        setShimmering(isInitialLoading)
        updateHomeRefreshState(syncState)

        renderHomeProductSection(
            root = root,
            recyclerId = R.id.rvFeaturedProducts,
            emptyCardId = R.id.tvFeaturedProductsEmpty,
            emptyTitleId = R.id.tvFeaturedProductsEmptyTitle,
            emptyBodyId = R.id.tvFeaturedProductsEmptyBody,
            retryButtonId = R.id.btnFeaturedProductsRefresh,
            cacheMessageId = R.id.tvFeaturedProductsCacheMessage,
            adapter = featuredProductsAdapter,
            items = featured,
            isInitialLoading = isInitialLoading,
            isOfflineWithoutCache = isOfflineWithoutCache,
            isShowingSavedProducts = isShowingSavedProducts,
            emptyMessage = featuredEmptyMessage ?: root.context.getString(R.string.home_section_empty),
            progressive = !syncState.fromCache && syncState.status == CatalogSyncStatus.SUCCESS
        )
        renderHomeProductSection(
            root = root,
            recyclerId = R.id.rvLatestProducts,
            emptyCardId = R.id.tvLatestProductsEmpty,
            emptyTitleId = R.id.tvLatestProductsEmptyTitle,
            emptyBodyId = R.id.tvLatestProductsEmptyBody,
            retryButtonId = R.id.btnLatestProductsRefresh,
            cacheMessageId = R.id.tvLatestProductsCacheMessage,
            adapter = latestProductsAdapter,
            items = latest,
            isInitialLoading = isInitialLoading,
            isOfflineWithoutCache = isOfflineWithoutCache,
            isShowingSavedProducts = isShowingSavedProducts,
            emptyMessage = latestEmptyMessage ?: root.context.getString(R.string.home_section_empty),
            progressive = !syncState.fromCache && syncState.status == CatalogSyncStatus.SUCCESS
        )
        renderHomeProductSection(
            root = root,
            recyclerId = R.id.rvDiscoverProducts,
            emptyCardId = R.id.tvDiscoverProductsEmpty,
            emptyTitleId = R.id.tvDiscoverProductsEmptyTitle,
            emptyBodyId = R.id.tvDiscoverProductsEmptyBody,
            retryButtonId = R.id.btnDiscoverProductsRefresh,
            cacheMessageId = R.id.tvDiscoverProductsCacheMessage,
            adapter = discoverProductsAdapter,
            items = discovery,
            isInitialLoading = isInitialLoading,
            isOfflineWithoutCache = isOfflineWithoutCache,
            isShowingSavedProducts = isShowingSavedProducts,
            emptyMessage = discoverEmptyMessage ?: root.context.getString(R.string.home_section_empty),
            progressive = !syncState.fromCache && syncState.status == CatalogSyncStatus.SUCCESS
        )
    }

    private fun renderHomeProductSection(
        root: View,
        recyclerId: Int,
        emptyCardId: Int,
        emptyTitleId: Int,
        emptyBodyId: Int,
        retryButtonId: Int,
        cacheMessageId: Int,
        adapter: HomeCatalogAdapter,
        items: List<Product>,
        isInitialLoading: Boolean,
        isOfflineWithoutCache: Boolean,
        isShowingSavedProducts: Boolean,
        emptyMessage: String,
        progressive: Boolean
    ) {
        updateCatalogSection(root, recyclerId, adapter, items, progressive)
        root.findViewById<RecyclerView>(recyclerId)?.visibility =
            if (items.isEmpty()) View.GONE else View.VISIBLE
        root.findViewById<View>(cacheMessageId)?.visibility =
            if (items.isNotEmpty() && isShowingSavedProducts) View.VISIBLE else View.GONE
        root.findViewById<View>(emptyCardId)?.visibility =
            if (items.isEmpty() && !isInitialLoading) View.VISIBLE else View.GONE

        val title = root.findViewById<TextView>(emptyTitleId)
        val body = root.findViewById<TextView>(emptyBodyId)
        val retry = root.findViewById<View>(retryButtonId)
        if (isOfflineWithoutCache) {
            title?.text = root.context.getString(R.string.home_offline_title)
            body?.text = root.context.getString(R.string.home_offline_subtitle)
            body?.visibility = View.VISIBLE
            retry?.visibility = View.VISIBLE
        } else {
            title?.text = emptyMessage
            body?.visibility = View.GONE
            retry?.visibility = View.GONE
        }
    }

    private fun updateCatalogSection(
        root: View,
        recyclerId: Int,
        adapter: HomeCatalogAdapter,
        items: List<Product>,
        progressive: Boolean
    ) {
        root.findViewById<RecyclerView>(recyclerId) ?: return
        val currentIds = adapter.currentList.map { it.id }
        val nextIds = items.map { it.id }
        if (currentIds == nextIds) return

        sectionDisplayJobs.remove(recyclerId)?.cancel()
        if (!progressive || items.size < 2 || adapter.currentList.isNotEmpty()) {
            adapter.submitList(items)
            return
        }

        sectionDisplayJobs[recyclerId] = viewLifecycleOwner.lifecycleScope.launch {
            adapter.submitList(emptyList())
            items.indices.forEach { index ->
                adapter.submitList(items.take(index + 1))
                delay(HOME_SECTION_PROGRESSIVE_DELAY_MS)
            }
            sectionDisplayJobs.remove(recyclerId)
        }
    }

    private fun homeCatalogProducts(): List<Product> {
        return ProductCatalog.all(includeInactive = false)
            .filter { it.isDisplayReady }
    }

    private fun rankedHomeProducts(
        products: List<Product>,
        favorites: Set<String>,
        cart: Map<String, Int>
    ): List<Product> {
        return diversifyByCategory(
            products.sortedWith(
                compareByDescending<Product> { product -> product.homeRelevanceScore(favorites, cart) }
                    .thenByDescending { it.updatedAtMillis.takeIf { value -> value > 0L } ?: it.createdAtMillis }
                    .thenBy { it.title.lowercase(Locale.getDefault()) }
            )
        )
    }

    private fun latestHomeProducts(products: List<Product>): List<Product> {
        return products.sortedWith(
            compareByDescending<Product> { product ->
                product.updatedAtMillis.takeIf { it > 0L } ?: product.createdAtMillis
            }.thenByDescending { it.homeQualityScore() }
                .thenBy { it.title.lowercase(Locale.getDefault()) }
        )
    }

    private fun discoveryHomeProducts(products: List<Product>, excludedIds: Set<String>): List<Product> {
        val candidates = products.filterNot { it.id in excludedIds }.ifEmpty { products }
        return diversifyByCategory(
            candidates.sortedWith(
                compareByDescending<Product> { it.homeDiscoveryScore() }
                    .thenBy { it.category }
                    .thenBy { it.title.lowercase(Locale.getDefault()) }
            )
        )
    }

    private fun diversifyByCategory(products: List<Product>): List<Product> {
        if (products.size <= 2) return products
        val buckets = products.groupBy { MarketplaceCategories.normalizeKey(it.category) }
            .mapValues { (_, values) -> values.toMutableList() }
            .toMutableMap()
        val result = mutableListOf<Product>()
        while (result.size < products.size && buckets.isNotEmpty()) {
            buckets.keys.sorted().forEach { key ->
                val bucket = buckets[key] ?: return@forEach
                val product = bucket.removeFirstOrNull()
                if (product != null) result += product
                if (bucket.isEmpty()) buckets.remove(key)
            }
        }
        return result
    }

    private fun Product.homeRelevanceScore(favorites: Set<String>, cart: Map<String, Int>): Double {
        val favoriteSignal = if (id in favorites) 80.0 else 0.0
        val cartSignal = if ((cart[id] ?: 0) > 0) 36.0 else 0.0
        val scarcitySignal = if (stock in 1..5) 8.0 else 0.0
        return homeQualityScore() + favoriteSignal + cartSignal + scarcitySignal
    }

    private fun Product.homeQualityScore(): Double {
        val reviewSignal = kotlin.math.ln((reviewsCount + 1).toDouble()) * 8.0
        val stockSignal = when {
            stock <= 0 -> -100.0
            stock <= 5 -> 8.0
            else -> 18.0
        }
        val freshnessSignal = if ((updatedAtMillis.takeIf { it > 0L } ?: createdAtMillis) > 0L) 10.0 else 0.0
        return rating * 12.0 + reviewSignal + stockSignal + freshnessSignal + if (isBio) 6.0 else 0.0
    }

    private fun Product.homeDiscoveryScore(): Double {
        val marginForScanning = when {
            price in 15.0..90.0 -> 10.0
            price in 90.0..180.0 -> 5.0
            else -> 0.0
        }
        return homeQualityScore() + marginForScanning + tags.size.coerceAtMost(5) * 2.0
    }

    private fun setShimmering(isShimmering: Boolean) {
        val root = view ?: return
        val containers = listOfNotNull(
            root.findViewById<View>(R.id.layoutFeaturedShimmer),
            root.findViewById<View>(R.id.layoutLatestShimmer),
            root.findViewById<View>(R.id.layoutDiscoverShimmer)
        )
        containers.forEach { container ->
            if (isShimmering) {
                container.visibility = View.VISIBLE
                container.startShimmerPulse()
            } else {
                container.stopShimmerPulse()
                container.visibility = View.GONE
            }
        }
    }

    private fun toggleFavorite(product: Product) {
        val host = activity as? AppCompatActivity ?: return
        val nextState = FavoritesStore.toggleFavorite(requireContext(), product.id)
        host.showMotionSnackbar(
            if (nextState) getString(R.string.product_added_to_favorites, product.title)
            else getString(R.string.product_removed_from_favorites, product.title)
        )
    }

    private fun addToCart(product: Product) {
        val host = activity as? AppCompatActivity ?: return
        if (product.stock <= 0) {
            host.showMotionSnackbar(getString(R.string.product_state_out_of_stock))
            return
        }
        val beforeCount = CartStore.itemCount(requireContext())
        CartStore.addOne(requireContext(), product.id)
        (activity as? MainActivity)?.updateHostCartBadge()
        val afterCount = CartStore.itemCount(requireContext())
        val message = if (afterCount == beforeCount) {
            getString(R.string.product_stock_limit_reached)
        } else {
            getString(R.string.product_added_to_cart, product.title)
        }
        host.showMotionSnackbar(message)
    }

    private fun openProduct(product: Product) {
        val host = activity as? AppCompatActivity ?: return
        if (ProductCatalog.byId(product.id) != null) {
            host.navigateToProductDetails(product.id)
        } else {
            openCuratedSearch(product.category)
        }
    }

    private fun setupHeaderAndContentActions(root: View) {
        root.findViewById<View>(R.id.tvBrand)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.HOME)
        }
        root.findViewById<View>(R.id.ivTopCart)?.setOnClickListener { source ->
            openMessagingInbox(source)
        }
        root.findViewById<View>(R.id.ivTopFavorites)?.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateNoShift(FavoritesActivity::class.java)
        }
        root.findViewById<View>(R.id.chatContainer)?.setOnClickListener(::openMessagingInbox)
        root.findViewById<View?>(R.id.tvCategoriesSeeAll)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.EXPLORE)
        }
        root.findViewById<View?>(R.id.tvFeaturedProductsSeeAll)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.EXPLORE)
        }
        root.findViewById<View?>(R.id.tvLatestProductsSeeAll)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.EXPLORE)
        }
        root.findViewById<View?>(R.id.tvDiscoverProductsSeeAll)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.EXPLORE)
        }
        root.findViewById<View?>(R.id.btnFeaturedProductsRefresh)?.setOnClickListener {
            setShimmering(true)
            CatalogSyncManager.refreshAsync(force = true)
        }
        root.findViewById<View?>(R.id.btnLatestProductsRefresh)?.setOnClickListener {
            setShimmering(true)
            CatalogSyncManager.refreshAsync(force = true)
        }
        root.findViewById<View?>(R.id.btnDiscoverProductsRefresh)?.setOnClickListener {
            setShimmering(true)
            CatalogSyncManager.refreshAsync(force = true)
        }

        (activity as? AppCompatActivity)?.bindNotificationEntry(R.id.ivTopNotifications)

        fun openSearch(source: View) {
            val host = activity as? AppCompatActivity ?: return
            source.animate()
                .scaleX(1.01f)
                .scaleY(1.01f)
                .setDuration(MotionTokens.QUICK)
                .withEndAction {
                    source.scaleX = 1f
                    source.scaleY = 1f
                    startActivity(SearchActivity.createIntent(host))
                    if (host.isReducedMotionEnabled()) {
                        host.overridePendingTransition(0, 0)
                    } else {
                        host.overridePendingTransition(
                            R.anim.motion_activity_enter_from_top,
                            R.anim.motion_activity_exit_stay
                        )
                    }
                }
                .start()
        }

        root.findViewById<View>(R.id.cardBannerPrimary)?.setOnClickListener {
            openCuratedSearch("electronics")
        }
        root.findViewById<View>(R.id.cardBannerSecondary)?.setOnClickListener {
            openCuratedSearch("food-and-grocery")
        }
        bindHomeTrustStrip(root)
        root.findViewById<View?>(R.id.cardHomePromo)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.EXPLORE)
        }
        root.findViewById<View?>(R.id.cardHomeEditorial)?.setOnClickListener {
            openCuratedSearch("collectibles-and-hobbies")
        }
        root.findViewById<View?>(R.id.cardHomeCommunity)?.setOnClickListener { source ->
            (activity as? AppCompatActivity)?.showMotionSnackbar(
                getString(R.string.home_community_thanks)
            ) ?: source.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
        root.findViewById<View?>(R.id.cardShopCategoryCraft)?.setOnClickListener { openCuratedSearch("electronics") }
        root.findViewById<View?>(R.id.cardShopCategoryDecor)?.setOnClickListener { openCuratedSearch("home-and-furniture") }
        root.findViewById<View?>(R.id.cardShopCategoryFood)?.setOnClickListener { openCuratedSearch("food-and-grocery") }
        root.findViewById<View?>(R.id.cardShopCategoryFashion)?.setOnClickListener { openCuratedSearch("fashion") }
        root.findViewById<View?>(R.id.cardShopCategoryBeauty)?.setOnClickListener { openCuratedSearch("beauty-and-health") }
        root.findViewById<View>(R.id.layoutSearchBar)?.setOnClickListener { openSearch(it) }
        root.findViewById<View>(R.id.ivSearch)?.setOnClickListener { openSearch(root.findViewById(R.id.layoutSearchBar) ?: it) }
        root.findViewById<View>(R.id.tvSearchHint)?.setOnClickListener { openSearch(root.findViewById(R.id.layoutSearchBar) ?: it) }
    }

    private fun openMessagingInbox(source: View) {
        val host = activity as? AppCompatActivity ?: return
        source.animate()
            .scaleX(0.96f)
            .scaleY(0.96f)
            .setDuration(MotionTokens.QUICK)
            .withEndAction {
                source.scaleX = 1f
                source.scaleY = 1f
                val intent = if (FirebaseAuthManager.isLoggedIn) {
                    MessagingInboxActivity.createIntent(host)
                } else {
                    Intent(host, LoginActivity::class.java)
                }
                startActivity(intent)
                if (host.isReducedMotionEnabled()) {
                    host.overridePendingTransition(0, 0)
                } else {
                    host.overridePendingTransition(
                        R.anim.motion_activity_enter_forward,
                        R.anim.motion_activity_exit_stay
                    )
                }
            }
            .start()
    }

    private fun listenForMessageUnread() {
        val badge = view?.findViewById<View>(R.id.messageBadge) ?: return
        val uid = FirebaseAuthManager.currentUser?.uid
        if (uid.isNullOrBlank()) {
            messageUnreadListener?.remove()
            messageUnreadListener = null
            badge.visibility = View.GONE
            return
        }
        if (messageUnreadListener != null) return
        messageUnreadListener = MessagingRepository.listenUnreadTotal(
            uid = uid,
            onChange = {
                view?.findViewById<View>(R.id.messageBadge)?.visibility =
                    if (it > 0) View.VISIBLE else View.GONE
            },
            onError = {
                view?.findViewById<View>(R.id.messageBadge)?.visibility = View.GONE
            }
        )
    }

    private fun setupCollapsingHeader(root: View) {
        val topSection = root.findViewById<View>(R.id.layoutTopSection) ?: return
        val topBar = root.findViewById<View>(R.id.layoutTopBar) ?: return
        val searchBar = root.findViewById<View>(R.id.layoutSearchBar) ?: return
        val scrollView = root.findViewById<NestedScrollView>(R.id.scrollHomeContent) ?: return

        topSection.post {
            val collapseDistance = searchBar.top.coerceAtLeast(1)
            scrollView.setPadding(
                scrollView.paddingLeft,
                topSection.height,
                scrollView.paddingRight,
                scrollView.paddingBottom
            )
            topSection.bringToFront()
            updateCollapsingHeaderState(topSection, topBar, scrollView.scrollY, collapseDistance)
            scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
                updateCollapsingHeaderState(topSection, topBar, scrollY, collapseDistance)
            }
        }
    }

    private fun updateCollapsingHeaderState(
        topSection: View,
        topBar: View,
        scrollY: Int,
        collapseDistance: Int
    ) {
        val clampedOffset = scrollY.coerceIn(0, collapseDistance)
        topSection.translationY = 0f
        topBar.alpha = 1f
        topBar.translationY = 0f
        topSection.elevation = if (clampedOffset > 0) resources.getDimension(R.dimen.home_header_elevation) else 0f
    }

    private fun setupMotionPolish() {
        (activity as? AppCompatActivity)?.applyPressFeedback(
            R.id.tvBrand,
            R.id.ivTopCart,
            R.id.ivTopFavorites,
            R.id.chatContainer,
            R.id.ivTopNotifications,
            R.id.layoutSearchBar,
            R.id.ivSearch,
            R.id.cardBannerPrimary,
            R.id.cardBannerSecondary
        )
    }

    private fun startSearchHintTyping(root: View) {
        val searchHint = root.findViewById<TextView>(R.id.tvSearchHint) ?: return
        stopSearchHintTyping()
        searchHint.text = getString(R.string.home_ref_search_hint)
        searchHint.alpha = 1f
    }

    private fun stopSearchHintTyping() {
        searchHintRunnable?.let { runnable ->
            searchHintTargetView?.removeCallbacks(runnable)
        }
        searchHintRunnable = null
        searchHintTargetView = null
    }

    private fun openCuratedSearch(item: HomeCategoryItem) {
        openCuratedSearch(item.categoryKey)
    }

    private fun openCuratedSearch(category: String) {
        val host = activity as? AppCompatActivity ?: return
        startActivity(CategoryProductsActivity.createIntent(host, category))
        host.overridePendingTransition(0, 0)
    }

    private fun updateNotificationBadge() {
        val badge = view?.findViewById<View>(R.id.notificationBadge) ?: return
        badge.visibility = if (NotificationStore.hasUnread(requireContext())) View.VISIBLE else View.GONE
    }

    override fun onTabReselected() {
        view?.findViewById<NestedScrollView>(R.id.scrollHomeContent)?.smoothScrollTo(0, 0)
    }

    private companion object {
        const val HOME_HERO_AUTO_SCROLL_MS = 6_000L
        const val HOME_SECTION_PROGRESSIVE_DELAY_MS = 80L
        const val HOME_HERO_STORAGE_PREFIX =
            "https://firebasestorage.googleapis.com/v0/b/fatiweb-marketplace.firebasestorage.app/o/category-images%2F"
    }



    private class HomeCatalogSpacingDecoration(
        private val horizontalSpacing: Int,
        private val verticalSpacing: Int
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return

            val spanCount = (parent.layoutManager as? GridLayoutManager)?.spanCount ?: 1
            val column = position % spanCount
            outRect.left = if (column == 0) 0 else horizontalSpacing / 2
            outRect.right = if (column == spanCount - 1) 0 else horizontalSpacing / 2
            if (position >= spanCount) {
                outRect.top = verticalSpacing
            }
        }
    }
    private class HomeHorizontalSpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            if (position > 0) {
                outRect.left = spacing
            }
        }
    }

}
