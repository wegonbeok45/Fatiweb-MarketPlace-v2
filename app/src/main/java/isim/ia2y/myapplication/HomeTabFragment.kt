package isim.ia2y.myapplication

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class HomeTabFragment : Fragment(R.layout.fragment_home_tab), TabReselectionHandler {
    private var featuredEmptyMessage: String? = null
    private var hasPlayedEntrance = false
    private var latestEmptyMessage: String? = null
    private var discoverEmptyMessage: String? = null
    private var searchHintRunnable: Runnable? = null
    private var searchHintTargetView: TextView? = null

    private val categoryItems = listOf(
        HomeCategoryItem(R.string.auto_text_013, R.drawable.ic_home_cat_artisanat, "craft"),
        HomeCategoryItem(R.string.auto_text_052, R.drawable.ic_home_cat_epices, "food"),
        HomeCategoryItem(R.string.auto_text_053, R.drawable.ic_home_cat_vetements, "fashion"),
        HomeCategoryItem(R.string.auto_text_054, R.drawable.ic_home_cat_deco, "decor"),
        HomeCategoryItem(R.string.auto_text_023, R.drawable.ic_home_cat_huiles, "food")
    )

    private val latestProductsAdapter by lazy {
        HomeCatalogAdapter(
            onToggleFavorite = ::toggleFavorite,
            onOpenProduct = ::openProduct,
            fixedItemWidthRes = R.dimen.home_product_carousel_card_width
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
        HomeCategoryCarouselAdapter(categoryItems, ::openCuratedSearch)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View?>(R.id.layoutBottomNav)?.isGone = true
        view.findViewById<View?>(R.id.viewBottomDivider)?.isGone = true
        setupCatalogSections(view)
        setupCategoryCarousel(view)
        renderCatalogSections(view)
        setupHeaderAndContentActions(view)
        setupCollapsingHeader(view)
        setupMotionPolish()
        startSearchHintTyping(view)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CatalogSyncManager.syncState.collect { state ->
                    val emptyMessage = when {
                        state.status == CatalogSyncStatus.ERROR && state.products.isEmpty() ->
                            getString(R.string.home_catalog_error)
                        else -> getString(R.string.home_section_empty)
                    }
                    featuredEmptyMessage = emptyMessage
                    latestEmptyMessage = emptyMessage
                    discoverEmptyMessage = emptyMessage
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
        view?.let { renderCatalogSections(it) }
        if (!hasPlayedEntrance) {
            hasPlayedEntrance = true
            (activity as? AppCompatActivity)?.forceViewsFullyVisible(
                R.id.layoutTopSection,
                R.id.scrollHomeContent
            )
        }
    }

    override fun onDestroyView() {
        stopSearchHintTyping()
        super.onDestroyView()
    }

    private fun setupCatalogSections(root: View) {
        setupGrid(root.findViewById(R.id.rvFeaturedProducts), featuredProductsAdapter, spanCount = 2)
        setupHorizontalRow(root.findViewById(R.id.rvLatestProducts), latestProductsAdapter)
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
        if (recycler.itemDecorationCount == 0) {
            recycler.addItemDecoration(HomeCategorySpacingDecoration(resources.getDimensionPixelSize(R.dimen.space_12)))
        }

        val middleStart = (Int.MAX_VALUE / 2).let { midpoint ->
            midpoint - (midpoint % categoryItems.size)
        }
        recycler.scrollToPosition(middleStart)
    }

    private fun renderCatalogSections(root: View) {
        (root.findViewById<RecyclerView>(R.id.rvFeaturedProducts)?.layoutManager as? GridLayoutManager)?.spanCount = 2
        (root.findViewById<RecyclerView>(R.id.rvDiscoverProducts)?.layoutManager as? GridLayoutManager)?.spanCount = 2

        val sections = HomeCatalogSectionsBuilder.build(ProductCatalog.all())
        updateCatalogSection(root, R.id.rvFeaturedProducts, featuredProductsAdapter, sections.featured)
        updateCatalogSection(root, R.id.rvLatestProducts, latestProductsAdapter, sections.latest)
        updateCatalogSection(root, R.id.rvDiscoverProducts, discoverProductsAdapter, sections.discover)

        val hasData = sections.featured.isNotEmpty() || sections.latest.isNotEmpty() || sections.discover.isNotEmpty()
        val syncState = CatalogSyncManager.syncState.value
        setShimmering(!hasData && syncState.isRefreshing)

        root.findViewById<RecyclerView>(R.id.rvFeaturedProducts)?.visibility =
            if (sections.featured.isEmpty()) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.tvFeaturedProductsEmpty)?.apply {
            text = featuredEmptyMessage ?: context.getString(R.string.home_section_empty)
            visibility = if (sections.featured.isEmpty()) View.VISIBLE else View.GONE
        }
        root.findViewById<RecyclerView>(R.id.rvLatestProducts)?.visibility =
            if (sections.latest.isEmpty()) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.tvLatestProductsEmpty)?.apply {
            text = latestEmptyMessage ?: context.getString(R.string.home_section_empty)
            visibility = if (sections.latest.isEmpty()) View.VISIBLE else View.GONE
        }
        root.findViewById<RecyclerView>(R.id.rvDiscoverProducts)?.visibility =
            if (sections.discover.isEmpty()) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.tvDiscoverProductsEmpty)?.apply {
            text = discoverEmptyMessage ?: context.getString(R.string.home_section_empty)
            visibility = if (sections.discover.isEmpty()) View.VISIBLE else View.GONE
        }
        root.findViewById<View>(R.id.btnLatestProductsRefresh)?.visibility =
            if (sections.latest.isEmpty()) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.btnDiscoverProductsRefresh)?.visibility =
            if (sections.discover.isEmpty()) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.btnFeaturedProductsRefresh)?.visibility =
            if (sections.featured.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateCatalogSection(
        root: View,
        recyclerId: Int,
        adapter: HomeCatalogAdapter,
        items: List<Product>
    ) {
        val recycler = root.findViewById<RecyclerView>(recyclerId) ?: return
        adapter.submitList(items) {
            recycler.post {
                recycler.requestLayout()
                recycler.invalidateItemDecorations()
                root.findViewById<NestedScrollView>(R.id.scrollHomeContent)?.requestLayout()
            }
        }
    }

    private fun setShimmering(isShimmering: Boolean) {
        val root = view ?: return
        val featuredShimmer = root.findViewById<View>(R.id.layoutFeaturedShimmer)
        val latestShimmer = root.findViewById<View>(R.id.layoutLatestShimmer)
        val discoverShimmer = root.findViewById<View>(R.id.layoutDiscoverShimmer)

        featuredShimmer?.visibility = if (isShimmering) View.VISIBLE else View.GONE
        latestShimmer?.visibility = if (isShimmering) View.VISIBLE else View.GONE
        discoverShimmer?.visibility = if (isShimmering) View.VISIBLE else View.GONE
        
        if (isShimmering) {
            startPulsingAnimation(featuredShimmer as? android.view.ViewGroup)
            startPulsingAnimation(latestShimmer as? android.view.ViewGroup)
            startPulsingAnimation(discoverShimmer as? android.view.ViewGroup)
        }
    }

    private fun startPulsingAnimation(group: android.view.ViewGroup?) {
        group ?: return
        val pulse = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.pulse)
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            child.startAnimation(pulse)
            if (child is android.view.ViewGroup) {
                for (j in 0 until child.childCount) {
                    child.getChildAt(j).startAnimation(pulse)
                }
            }
        }
    }

    private fun toggleFavorite(product: Product) {
        val host = activity as? AppCompatActivity ?: return
        val nextState = FavoritesStore.toggleFavorite(requireContext(), product.id)
        host.showToast(
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
        (activity as? AppCompatActivity)?.navigateToProductDetails(product.id)
    }

    private fun setupHeaderAndContentActions(root: View) {
        root.findViewById<View>(R.id.tvBrand)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.HOME)
        }
        root.findViewById<View>(R.id.ivTopCart)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.CART)
        }
        root.findViewById<View>(R.id.ivTopFavorites)?.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateNoShift(FavoritesActivity::class.java)
        }
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
            openCuratedSearch("craft")
        }
        root.findViewById<View>(R.id.cardBannerSecondary)?.setOnClickListener {
            openCuratedSearch("food")
        }
        root.findViewById<View>(R.id.layoutSearchBar)?.setOnClickListener { openSearch(it) }
        root.findViewById<View>(R.id.ivSearch)?.setOnClickListener { openSearch(root.findViewById(R.id.layoutSearchBar) ?: it) }
        root.findViewById<View>(R.id.tvSearchHint)?.setOnClickListener { openSearch(root.findViewById(R.id.layoutSearchBar) ?: it) }
        root.findViewById<View>(R.id.ivFilter)?.setOnClickListener { openSearch(root.findViewById(R.id.layoutSearchBar) ?: it) }
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
        val progress = clampedOffset / collapseDistance.toFloat()
        topSection.translationY = -clampedOffset.toFloat()
        topBar.alpha = 1f - progress
        topBar.translationY = -(clampedOffset * 0.2f)
        topSection.elevation = if (progress > 0.92f) {
            resources.getDimension(R.dimen.home_header_elevation) * 1.5f
        } else {
            resources.getDimension(R.dimen.home_header_elevation)
        }
    }

    private fun setupMotionPolish() {
        (activity as? AppCompatActivity)?.applyPressFeedback(
            R.id.tvBrand,
            R.id.ivTopCart,
            R.id.ivTopFavorites,
            R.id.ivTopNotifications,
            R.id.layoutSearchBar,
            R.id.ivSearch,
            R.id.ivFilter,
            R.id.cardBannerPrimary,
            R.id.cardBannerSecondary
        )
    }

    private fun startSearchHintTyping(root: View) {
        val searchHint = root.findViewById<TextView>(R.id.tvSearchHint) ?: return
        stopSearchHintTyping()
        searchHint.text = getString(R.string.search_hint_products)
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
        startActivity(SearchActivity.createIntent(host, initialCategory = category))
        if (host.isReducedMotionEnabled()) {
            host.overridePendingTransition(0, 0)
        } else {
            host.overridePendingTransition(
                R.anim.motion_activity_enter_from_top,
                R.anim.motion_activity_exit_stay
            )
        }
    }

    private fun updateNotificationBadge() {
        val badge = view?.findViewById<View>(R.id.notificationBadge) ?: return
        badge.visibility = if (NotificationStore.hasUnread(requireContext())) View.VISIBLE else View.GONE
    }

    override fun onTabReselected() {
        view?.findViewById<NestedScrollView>(R.id.scrollHomeContent)?.smoothScrollTo(0, 0)
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

    private class HomeCategorySpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(
            outRect: Rect,
            view: View,
            parent: RecyclerView,
            state: RecyclerView.State
        ) {
            outRect.right = spacing
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
