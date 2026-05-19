package isim.ia2y.myapplication

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class CategoryProductsActivity : AppCompatActivity() {
    private enum class SortOption(val labelResId: Int) {
        POPULAR(R.string.category_sort_popular),
        NEWEST(R.string.category_sort_newest),
        PRICE_LOW(R.string.category_sort_price_low),
        PRICE_HIGH(R.string.category_sort_price_high)
    }

    private lateinit var adapter: HomeCatalogAdapter
    private var selectedCategory = MarketplaceCategories.items.firstOrNull()?.key ?: "electronics"
    private var selectedSort = SortOption.POPULAR
    private var inStockOnly = false
    private var bioOnly = false
    private var renderJob: Job? = null
    private var lastCatalogSignature: Int = 0
    private var scrollToTopAfterRender = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_category_products)

        selectedCategory = topLevelCategoryKey(MarketplaceCategories.normalizeKey(
            savedInstanceState?.getString(KEY_CATEGORY)
                ?: intent.getStringExtra(EXTRA_CATEGORY)
        ))
        selectedSort = SortOption.values().getOrElse(
            savedInstanceState?.getInt(KEY_SORT) ?: SortOption.POPULAR.ordinal
        ) { SortOption.POPULAR }
        inStockOnly = savedInstanceState?.getBoolean(KEY_IN_STOCK) ?: false
        bioOnly = savedInstanceState?.getBoolean(KEY_BIO) ?: false

        setupWindowInsets()
        setupTopBar()
        setupProductsGrid()
        setupSortChips()
        setupFilterChips()
        lastCatalogSignature = ProductCatalog.all(includeInactive = true)
            .asSequence()
            .map { it.id to it.updatedAtMillis }
            .hashCode()
        renderProducts()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CatalogSyncManager.syncState.collect { state ->
                    val signature = state.products.asSequence()
                        .map { it.id to it.updatedAtMillis }
                        .hashCode()
                    if (signature != lastCatalogSignature) {
                        lastCatalogSignature = signature
                        renderProducts()
                    }
                }
            }
        }
        lifecycleScope.launch {
            runCatching { CatalogSyncManager.ensureSynced(force = false) }
        }

        onBackPressedDispatcher.addCallback(this) {
            finishCategoryScreen()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_CATEGORY, selectedCategory)
        outState.putInt(KEY_SORT, selectedSort.ordinal)
        outState.putBoolean(KEY_IN_STOCK, inStockOnly)
        outState.putBoolean(KEY_BIO, bioOnly)
    }

    override fun onDestroy() {
        renderJob?.cancel()
        super.onDestroy()
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.categoryProductsRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun setupTopBar() {
        findViewById<ImageButton>(R.id.btnCategoryBack).setOnClickListener {
            finishCategoryScreen()
        }
    }

    private fun setupProductsGrid() {
        adapter = HomeCatalogAdapter(
            onToggleFavorite = { product ->
                val isFavorite = FavoritesStore.toggleFavorite(this, product.id)
                showMotionSnackbar(
                    if (isFavorite) getString(R.string.product_added_to_favorites, product.title)
                    else getString(R.string.product_removed_from_favorites, product.title)
                )
            },
            onOpenProduct = { product ->
                navigateToProductDetails(product.id)
            }
        )
        findViewById<RecyclerView>(R.id.rvCategoryProducts).apply {
            layoutManager = GridLayoutManager(
                this@CategoryProductsActivity,
                marketplaceGridSpanCount(maxSpanCount = 4)
            )
            adapter = this@CategoryProductsActivity.adapter
            clipToPadding = false
            if (itemDecorationCount == 0) {
                addItemDecoration(CategoryProductSpacingDecoration(resources.getDimensionPixelSize(R.dimen.home_products_row_gap)))
            }
        }
    }

    private fun setupSortChips() {
        val group = findViewById<ChipGroup>(R.id.chipGroupCategorySort)
        group.removeAllViews()
        SortOption.values().forEach { option ->
            group.addView(buildChip(option.name, getString(option.labelResId)).apply {
                isChecked = option == selectedSort
                setOnClickListener {
                    selectedSort = option
                    requestProductsTopAfterRender()
                    renderProducts()
                }
            })
        }
    }

    private fun setupFilterChips() {
        findViewById<Chip>(R.id.chipCategoryInStock).apply {
            isChecked = inStockOnly
            setOnClickListener {
                inStockOnly = isChecked
                requestProductsTopAfterRender()
                renderProducts()
            }
        }
        findViewById<Chip>(R.id.chipCategoryBio).apply {
            isChecked = bioOnly
            setOnClickListener {
                bioOnly = isChecked
                requestProductsTopAfterRender()
                renderProducts()
            }
        }
        findViewById<MaterialButton>(R.id.btnCategoryClearFilters).setOnClickListener {
            selectedSort = SortOption.POPULAR
            inStockOnly = false
            bioOnly = false
            setupSortChips()
            setupFilterChips()
            requestProductsTopAfterRender()
            renderProducts()
        }
    }

    private fun renderProducts() {
        renderJob?.cancel()
        val category = MarketplaceCategories.categoryFor(selectedCategory)
        val displayName = MarketplaceCategories.displayNameFor(selectedCategory)
        findViewById<TextView>(R.id.tvCategoryProductsTitle).text = displayName
        findViewById<TextView>(R.id.tvCategoryBreadcrumb)?.text =
            getString(R.string.category_hero_eyebrow, displayName)
        findViewById<ImageView>(R.id.ivCategoryHeroImage)?.loadCatalogImage(
            category?.imageUrl,
            MarketplaceCategories.imageResFor(selectedCategory),
            requestedSizePx = 720,
            crossfadeMillis = 220
        )

        val categoryKey = selectedCategory
        val sort = selectedSort
        val stockFilter = inStockOnly
        val bioFilter = bioOnly
        val allProducts = ProductCatalog.all(includeInactive = false)
        renderJob = lifecycleScope.launch {
            val products = withContext(Dispatchers.Default) {
                allProducts
                    .asSequence()
                    .filter { it.isDisplayReady }
                    .filter { MarketplaceCategories.matches(it, categoryKey) }
                    .filter { !stockFilter || it.stock > 0 }
                    .filter { !bioFilter || it.isBio }
                    .let { sequence ->
                        when (sort) {
                            SortOption.POPULAR -> sequence.sortedWith(
                                compareByDescending<Product> { it.categoryRelevanceScore() }
                                    .thenBy { it.title.lowercase(Locale.getDefault()) }
                            )
                            SortOption.NEWEST -> sequence.sortedByDescending { it.updatedAtMillis }
                            SortOption.PRICE_LOW -> sequence.sortedBy { it.price }
                            SortOption.PRICE_HIGH -> sequence.sortedByDescending { it.price }
                        }
                    }
                    .toList()
            }

            findViewById<TextView>(R.id.tvCategoryProductsCount).text =
                resources.getQuantityString(R.plurals.category_products_count, products.size, products.size)

            val shouldScrollToTop = scrollToTopAfterRender
            scrollToTopAfterRender = false
            adapter.submitList(products) {
                if (shouldScrollToTop && products.isNotEmpty()) {
                    scrollProductsToTop()
                }
            }
            val isEmpty = products.isEmpty()
            findViewById<View>(R.id.layoutCategoryEmptyState).visibility = if (isEmpty) View.VISIBLE else View.GONE
            findViewById<RecyclerView>(R.id.rvCategoryProducts).visibility = if (isEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun requestProductsTopAfterRender() {
        scrollToTopAfterRender = true
    }

    private fun scrollProductsToTop() {
        findViewById<RecyclerView>(R.id.rvCategoryProducts)?.apply {
            stopScroll()
            post {
                (layoutManager as? GridLayoutManager)?.scrollToPositionWithOffset(0, 0)
                    ?: scrollToPosition(0)
            }
        }
    }

    private fun Product.categoryRelevanceScore(): Double {
        val reviewSignal = kotlin.math.ln((reviewsCount + 1).toDouble()) * 10.0
        val stockSignal = when {
            stock <= 0 -> -120.0
            stock <= 5 -> 14.0
            else -> 24.0
        }
        val completenessSignal = listOf(title, subtitle, description)
            .sumOf { value -> if (value.trim().length >= 12) 4.0 else 0.0 }
        val freshnessSignal = if ((updatedAtMillis.takeIf { it > 0L } ?: createdAtMillis) > 0L) 8.0 else 0.0
        return rating * 14.0 + reviewSignal + stockSignal + completenessSignal + freshnessSignal + if (isBio) 5.0 else 0.0
    }

    private fun buildChip(key: String, label: String): Chip {
        return Chip(this).apply {
            id = View.generateViewId()
            tag = key
            text = label
            isCheckable = true
            isClickable = true
            chipMinHeight = resources.getDimension(R.dimen.category_filter_chip_height)
            chipBackgroundColor = ContextCompat.getColorStateList(context, R.color.category_chip_background)
            setTextColor(ContextCompat.getColorStateList(context, R.color.category_chip_text))
            chipStrokeColor = ContextCompat.getColorStateList(context, R.color.category_chip_stroke)
            chipStrokeWidth = resources.getDimension(R.dimen.category_filter_chip_stroke)
            checkedIcon = null
            isCheckedIconVisible = false
            typeface = resources.getFont(R.font.manrope_medium)
            textSize = 12f
            chipCornerRadius = resources.getDimension(R.dimen.home_search_radius)
        }
    }

    private fun finishCategoryScreen() {
        finish()
        overridePendingTransition(0, 0)
    }

    private class CategoryProductSpacingDecoration(
        private val spacing: Int
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
            outRect.left = if (column == 0) 0 else spacing / 2
            outRect.right = if (column == spanCount - 1) 0 else spacing / 2
            if (position >= spanCount) outRect.top = spacing
        }
    }

    companion object {
        private const val EXTRA_CATEGORY = "extra_category"
        private const val KEY_CATEGORY = "key_category"
        private const val KEY_SORT = "key_sort"
        private const val KEY_IN_STOCK = "key_in_stock"
        private const val KEY_BIO = "key_bio"

        fun createIntent(context: Context, category: String): Intent {
            return Intent(context, CategoryProductsActivity::class.java)
                .putExtra(EXTRA_CATEGORY, MarketplaceCategories.categoryFor(category)?.topLevelId ?: MarketplaceCategories.normalizeKey(category))
        }
    }

    private fun topLevelCategoryKey(category: String): String =
        MarketplaceCategories.categoryFor(category)?.topLevelId ?: MarketplaceCategories.normalizeKey(category)
}
