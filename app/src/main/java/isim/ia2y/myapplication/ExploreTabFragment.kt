package isim.ia2y.myapplication

import android.os.Bundle
import android.graphics.Rect
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class ExploreTabFragment : Fragment(R.layout.fragment_explore_tab) {
    private val featuredAdapter by lazy {
        CategoryCardAdapter(CategoryCardAdapter.Mode.COMPACT, ::openCategory)
    }
    private val allAdapter by lazy {
        CategoryCardAdapter(CategoryCardAdapter.Mode.GRID, ::openCategory)
    }
    private var searchQuery: String = ""

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View?>(R.id.layoutBottomNav)?.isGone = true
        view.findViewById<View?>(R.id.viewBottomDivider)?.isGone = true
        setupCategoryLists(view)
        setupSearch(view)
        renderCategories(view)
        CatalogSyncManager.refreshAsync(force = false)
        refreshMarketplaceCategories(view)
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.updateHostCartBadge()
    }

    private fun setupCategoryLists(root: View) {
        val ctx = requireContext()
        val featuredGap = resources.getDimensionPixelSize(R.dimen.marketplace_category_featured_gap)
        root.findViewById<RecyclerView>(R.id.rvFeaturedCategories)?.apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            adapter = featuredAdapter
            isNestedScrollingEnabled = false
            clipToPadding = false
            if (itemDecorationCount == 0) {
                addItemDecoration(FeaturedSpacingDecoration(featuredGap))
            }
        }
        root.findViewById<RecyclerView>(R.id.rvAllCategories)?.setupGrid(allAdapter)
    }

    private fun RecyclerView.setupGrid(categoryAdapter: CategoryCardAdapter) {
        layoutManager = GridLayoutManager(requireContext(), 2)
        adapter = categoryAdapter
        isNestedScrollingEnabled = false
        clipToPadding = false
        if (itemDecorationCount == 0) {
            addItemDecoration(CategoryGridSpacingDecoration(resources.getDimensionPixelSize(R.dimen.home_products_column_gap)))
        }
    }

    private fun setupSearch(root: View) {
        root.findViewById<EditText>(R.id.etCategorySearch)?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString().orEmpty()
                renderCategories(root)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun refreshMarketplaceCategories(root: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            runCatching { MarketplaceCategories.refreshFromFirestore() }
                .onSuccess { renderCategories(root) }
        }
    }

    private fun renderCategories(root: View) {
        val topCategories = MarketplaceCategories.items
            .filter { MarketplaceCategories.searchMatches(it, searchQuery) }
        val featured = topCategories.filter { it.featured }

        featuredAdapter.submitList(featured)
        allAdapter.submitList(topCategories)

        val featuredVisible = featured.isNotEmpty()
        root.findViewById<View>(R.id.layoutFeaturedHeader)?.visibility =
            if (featuredVisible) View.VISIBLE else View.GONE
        root.findViewById<RecyclerView>(R.id.rvFeaturedCategories)?.visibility =
            if (featuredVisible) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.layoutCategoriesEmpty)?.visibility =
            if (topCategories.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openCategory(category: MarketplaceCategory) {
        val host = requireActivity()
        startActivity(CategoryProductsActivity.createIntent(host, category.id))
        host.overridePendingTransition(R.anim.motion_activity_enter_forward, R.anim.motion_activity_exit_forward)
    }

    private class CategoryGridSpacingDecoration(
        private val spacing: Int
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            val column = position % 2
            outRect.left = if (column == 0) 0 else spacing / 2
            outRect.right = if (column == 0) spacing / 2 else 0
            if (position >= 2) outRect.top = spacing
        }
    }

    private class FeaturedSpacingDecoration(
        private val spacing: Int
    ) : RecyclerView.ItemDecoration() {
        override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
            val position = parent.getChildAdapterPosition(view)
            if (position == RecyclerView.NO_POSITION) return
            if (position > 0) outRect.left = spacing
        }
    }
}
