package isim.ia2y.myapplication

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class HomeTabFragment : Fragment(R.layout.fragment_home_tab) {
    private var hasPlayedEntrance = false

    private val latestProductsAdapter by lazy {
        HomeCatalogAdapter(
            onToggleFavorite = ::toggleFavorite,
            onAddToCart = ::addToCart,
            onOpenProduct = ::openProduct
        )
    }

    private val discoverProductsAdapter by lazy {
        HomeCatalogAdapter(
            onToggleFavorite = ::toggleFavorite,
            onAddToCart = ::addToCart,
            onOpenProduct = ::openProduct
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.applyStatusBarInset()
        view.findViewById<View?>(R.id.layoutBottomNav)?.isGone = true
        view.findViewById<View?>(R.id.viewBottomDivider)?.isGone = true
        setupCatalogSections(view)
        renderCatalogSections(view)
        setupHeaderAndContentActions(view)
        setupMotionPolish()
        val hasLocalCatalog = ProductCatalog.all(includeInactive = true).isNotEmpty()
        if (hasLocalCatalog) {
            CatalogSyncManager.refreshAsync(force = false)
        } else {
            viewLifecycleOwner.lifecycleScope.launch {
                CatalogSyncManager.ensureSynced(force = false)
                if (isAdded) {
                    renderCatalogSections(requireView())
                }
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

    private fun setupCatalogSections(root: View) {
        setupGrid(root.findViewById(R.id.rvLatestProducts), latestProductsAdapter)
        setupGrid(root.findViewById(R.id.rvDiscoverProducts), discoverProductsAdapter)
    }

    private fun setupGrid(recycler: RecyclerView?, adapter: HomeCatalogAdapter) {
        recycler ?: return
        recycler.layoutManager = GridLayoutManager(requireContext(), calculateHomeSpanCount())
        recycler.adapter = adapter
        recycler.isNestedScrollingEnabled = false
        if (recycler.itemDecorationCount == 0) {
            recycler.addItemDecoration(HomeCatalogSpacingDecoration(resources.getDimensionPixelSize(R.dimen.space_12)))
        }
    }

    private fun renderCatalogSections(root: View) {
        val spanCount = calculateHomeSpanCount()
        (root.findViewById<RecyclerView>(R.id.rvLatestProducts)?.layoutManager as? GridLayoutManager)?.spanCount = spanCount
        (root.findViewById<RecyclerView>(R.id.rvDiscoverProducts)?.layoutManager as? GridLayoutManager)?.spanCount = spanCount

        val sections = HomeCatalogSectionsBuilder.build(ProductCatalog.all())
        latestProductsAdapter.submitList(sections.latest)
        discoverProductsAdapter.submitList(sections.discover)

        root.findViewById<RecyclerView>(R.id.rvLatestProducts)?.visibility =
            if (sections.latest.isEmpty()) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.tvLatestProductsEmpty)?.visibility =
            if (sections.latest.isEmpty()) View.VISIBLE else View.GONE
        root.findViewById<RecyclerView>(R.id.rvDiscoverProducts)?.visibility =
            if (sections.discover.isEmpty()) View.GONE else View.VISIBLE
        root.findViewById<TextView>(R.id.tvDiscoverProductsEmpty)?.visibility =
            if (sections.discover.isEmpty()) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.btnLatestProductsRefresh)?.visibility =
            if (sections.latest.isEmpty()) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.btnDiscoverProductsRefresh)?.visibility =
            if (sections.discover.isEmpty()) View.VISIBLE else View.GONE
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
        root.findViewById<View>(R.id.ivHomeLogo)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.HOME)
        }
        root.findViewById<View>(R.id.tvBrand)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.HOME)
        }
        root.findViewById<View>(R.id.ivTopCart)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.CART)
        }
        root.findViewById<View>(R.id.ivTopFavorites)?.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateNoShift(FavoritesActivity::class.java)
        }
        bindCategorySearch(root, R.id.itemCategoryArtisanat, "craft")
        bindCategorySearch(root, R.id.itemCategoryEpices, "food")
        bindCategorySearch(root, R.id.itemCategoryVetements, "fashion")
        bindCategorySearch(root, R.id.itemCategoryDeco, "decor")
        bindCategorySearch(root, R.id.itemCategoryHuiles, "food")
        root.findViewById<View?>(R.id.tvCategoriesSeeAll)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.EXPLORE)
        }
        root.findViewById<View?>(R.id.btnLatestProductsRefresh)?.setOnClickListener {
            CatalogSyncManager.refreshAsync(force = false)
        }
        root.findViewById<View?>(R.id.btnDiscoverProductsRefresh)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.EXPLORE)
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

        fun openCuratedSearch(category: String) {
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

    private fun bindCategorySearch(root: View, viewId: Int, category: String) {
        root.findViewById<View?>(viewId)?.setOnClickListener {
            val host = activity as? AppCompatActivity ?: return@setOnClickListener
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
    }

    private fun setupMotionPolish() {
        (activity as? AppCompatActivity)?.applyPressFeedback(
            R.id.ivHomeLogo,
            R.id.tvBrand,
            R.id.ivTopCart,
            R.id.ivTopFavorites,
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
            R.id.itemCategoryHuiles
        )
    }

    private fun updateNotificationBadge() {
        val badge = view?.findViewById<View>(R.id.notificationBadge) ?: return
        badge.visibility = if (NotificationStore.hasUnread(requireContext())) View.VISIBLE else View.GONE
    }

    private fun calculateHomeSpanCount(): Int = requireContext().marketplaceGridSpanCount()



    private class HomeCatalogSpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {
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
            outRect.left = if (column == 0) 0 else spacing / 2
            outRect.right = if (column == spanCount - 1) 0 else spacing / 2
            if (position >= spanCount) {
                outRect.top = spacing / 2
            }
        }
    }
}
