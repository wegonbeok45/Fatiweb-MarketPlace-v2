package isim.ia2y.myapplication

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.AutoCompleteTextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Locale

open class AdminProduitsActivity : AppCompatActivity() {
    private data class ProductFilterOption(val key: String, val label: String)

    private val allProducts = mutableListOf<Product>()
    private val productFilters by lazy {
        listOf(
            ProductFilterOption("all", getString(R.string.admin_filter_all)),
            ProductFilterOption("low_stock", getString(R.string.admin_filter_low_stock)),
            ProductFilterOption("inactive", getString(R.string.admin_filter_inactive)),
            ProductFilterOption("active", getString(R.string.admin_filter_active))
        )
    }
    private var lastVisible: com.google.firebase.firestore.DocumentSnapshot? = null
    private var isLastPage = false
    private var isLoading = false
    private val pageSize = 20L
    private var searchQuery = ""
    private var selectedFilter = "all"
    private var productLoadErrorMessage: String? = null
    private var hasRenderedCachePreview = false
    private val isSellerDashboard: Boolean
        get() = intent.getBooleanExtra(EXTRA_SELLER_MODE, false)
    private var activeRole: String = UserRoles.CLIENT
    private var activeSellerId: String? = null

    private val productEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                resetAndLoad()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_produits)
        setupAdminWindowInsets(R.id.adminProduitsAppBar)
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.PRODUITS)
        configureModeLabels()
        setupActions()
        setupFilters()

        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.adminProduitsList)
        recycler?.layoutManager = LinearLayoutManager(this)
        recycler?.adapter = AdminProductsAdapter(
            items = mutableListOf(),
            onEdit = { openProductEditor(it) },
            onDelete = { confirmDelete(it) },
            canEdit = { canEditProduct(it) },
            onEditBlocked = { showMotionSnackbar(getString(R.string.admin_product_edit_own_only)) }
        )
        
        recycler?.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val visibleItemCount = layoutManager.childCount
                val totalItemCount = layoutManager.itemCount
                val firstVisibleItemPosition = layoutManager.findFirstVisibleItemPosition()

                if (!isLoading && !isLastPage) {
                    if ((visibleItemCount + firstVisibleItemPosition) >= totalItemCount && firstVisibleItemPosition >= 0) {
                        loadNextPage()
                    }
                }
            }
        })

        lifecycleScope.launch {
            activeRole = if (isSellerDashboard) {
                requireAdminOrVendeurRole() ?: return@launch
            } else {
                if (!requireAdminRole()) return@launch
                UserRoles.ADMIN
            }
            activeSellerId = FirebaseAuthManager.currentUser?.uid
                ?.takeIf { isSellerDashboard && activeRole == UserRoles.VENDEUR }

            if (savedInstanceState == null) {
                revealViewsInOrder(
                    R.id.adminProduitsTopBar,
                    R.id.adminProduitsTvHeader,
                    R.id.adminProduitsTvSubheader,
                    R.id.adminProduitsStatsRow,
                    R.id.adminProduitsBtnAdd,
                    R.id.adminProduitsCard,
                    startDelayMs = 60L,
                    staggerMs = 44L
                )
            }
            loadNextPage()
            loadStats()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isSellerDashboard) {
            hideSellerBottomNav()
        } else {
            refreshAdminBottomNav(AdminNavTab.PRODUITS)
        }
    }

    private fun configureModeLabels() {
        if (!isSellerDashboard) return
        hideSellerBottomNav()
        findViewById<TextView>(R.id.adminProduitsTvTitle)?.text = getString(R.string.seller_dashboard_title)
        findViewById<TextView>(R.id.adminProduitsTvHeader)?.text = getString(R.string.seller_dashboard_hero_title)
        findViewById<TextView>(R.id.adminProduitsTvSubheader)?.text = getString(R.string.seller_dashboard_hero_subtitle)
        findViewById<com.google.android.material.button.MaterialButton>(R.id.adminProduitsBtnAdd)?.text =
            getString(R.string.seller_dashboard_add_action)
    }

    private fun hideSellerBottomNav() {
        findViewById<View?>(R.id.adminBottomNav)?.visibility = View.GONE
        findViewById<View?>(R.id.admin_nav_indicator)?.visibility = View.GONE
    }

    private fun setupActions() {
        findViewById<View>(R.id.adminProduitsBtnAdd)?.setOnClickListener {
            openProductEditor(existing = null)
        }
        findViewById<View>(R.id.adminProduitsBtnRefresh)?.setOnClickListener {
            resetAndLoad()
        }
        applyPressFeedback(R.id.adminProduitsBtnAdd, R.id.adminProduitsBtnRefresh)
    }

    private fun setupFilters() {
        val searchInput = findViewById<android.widget.EditText>(R.id.adminProduitsSearchInput)
        val filterInput = findViewById<AutoCompleteTextView>(R.id.adminProduitsFilterInput)

        searchInput?.doAfterTextChanged {
            searchQuery = it?.toString().orEmpty().trim()
            renderProducts()
        }

        filterInput?.setAdapter(
            android.widget.ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                productFilters.map { it.label }
            )
        )
        filterInput?.setText(productFilters.first().label, false)
        filterInput?.setOnClickListener { filterInput.showDropDown() }
        filterInput?.setOnItemClickListener { _, _, position, _ ->
            selectedFilter = productFilters[position].key
            renderProducts()
        }
    }

    private fun resetAndLoad() {
        allProducts.clear()
        lastVisible = null
        isLastPage = false
        productLoadErrorMessage = null
        hasRenderedCachePreview = false
        renderProducts()
        scrollProductsToTop()
        loadNextPage()
        loadStats()
    }

    private fun setupTopBar() {
        findViewById<View?>(R.id.adminProduitsIvBack)?.setOnClickListener {
            navigateBackToMain()
        }
        applyPressFeedback(R.id.adminProduitsIvBack)
    }

    private fun loadStats() {
        if (isSellerDashboard) {
            renderSellerStats()
            return
        }
        lifecycleScope.launch {
            runCatching { AdminService.fetchAdminStats() }
                .onSuccess { stats ->
                    findViewById<TextView>(R.id.adminProduitsTvCount)?.text = stats.totalProducts.toString()
                    findViewById<TextView>(R.id.adminProduitsTvLowStock)?.text = stats.lowStockProducts.toString()
                }
        }
    }

    private fun renderSellerStats() {
        findViewById<TextView>(R.id.adminProduitsTvCount)?.text = allProducts.size.toString()
        findViewById<TextView>(R.id.adminProduitsTvLowStock)?.text =
            allProducts.count { it.isActive && it.stock in 1..5 }.toString()
    }

    private fun loadNextPage() {
        if (isLoading || isLastPage) return
        renderCachedProductsPreview()
        isLoading = true
        productLoadErrorMessage = null
        findViewById<TextView>(R.id.adminProduitsEmpty)?.visibility = View.GONE
        findViewById<ProgressBar>(R.id.adminProduitsProgress)?.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = runCatching {
                withTimeout(PRODUCTS_PAGE_TIMEOUT_MS) {
                    ProductService.fetchProductsPaginated(
                        pageSize = pageSize,
                        lastDoc = lastVisible,
                        sellerIdFilter = activeSellerId
                    )
                }
            }

            result.onSuccess { (newItems, lastDoc) ->
                isLoading = false
                findViewById<ProgressBar>(R.id.adminProduitsProgress)?.visibility = View.GONE
                productLoadErrorMessage = null
                
                if (newItems.isEmpty()) {
                    isLastPage = true
                } else {
                    mergeProducts(newItems)
                    lastVisible = lastDoc
                    if (newItems.size < pageSize.toInt()) {
                        isLastPage = true
                    }
                }
                renderProducts()
                if (isSellerDashboard) renderSellerStats()
                return@launch
            }

            val error = result.exceptionOrNull() ?: return@launch
            Log.e(TAG, "Failed to load products for sellerId=$activeSellerId role=$activeRole", error)
            isLoading = false
            findViewById<ProgressBar>(R.id.adminProduitsProgress)?.visibility = View.GONE
            if (error is TimeoutCancellationException && renderCachedProductsFallback()) {
                scrollProductsToTop()
            } else {
                isLastPage = true
                if (allProducts.isEmpty()) {
                    productLoadErrorMessage = getString(R.string.admin_products_load_error)
                }
                renderProducts()
                showMotionSnackbar(getString(R.string.admin_products_load_error))
            }
        }
    }

    private fun renderCachedProductsPreview() {
        if (hasRenderedCachePreview || allProducts.isNotEmpty() || lastVisible != null) return
        val cached = cachedProductPreview(pageSize.toInt())
        if (cached.isEmpty()) return

        hasRenderedCachePreview = true
        mergeProducts(cached)
        renderProducts()
        if (isSellerDashboard) renderSellerStats()
    }

    private suspend fun renderCachedProductsFallback(): Boolean {
        val firestoreCached = runCatching {
            ProductService.fetchProductsPaginated(
                pageSize = pageSize,
                lastDoc = null,
                sellerIdFilter = activeSellerId,
                source = Source.CACHE
            ).first
        }.getOrDefault(emptyList())

        val cached = firestoreCached.ifEmpty {
            withContext(Dispatchers.Default) { cachedProductPreview(pageSize.toInt()) }
        }

        if (cached.isEmpty()) return false
        mergeProducts(cached)
        isLastPage = true
        renderProducts()
        if (isSellerDashboard) renderSellerStats()
        return true
    }

    private fun cachedProductPreview(limit: Int): List<Product> {
        return ProductCatalog.all(includeInactive = true)
            .asSequence()
            .filter { product ->
                activeSellerId.isNullOrBlank() || product.sellerId == activeSellerId
            }
            .sortedWith(compareByDescending<Product> { it.updatedAtMillis }.thenBy { it.title.lowercase(Locale.getDefault()) })
            .take(limit)
            .toList()
    }

    private fun mergeProducts(newItems: List<Product>) {
        if (newItems.isEmpty()) return
        val merged = linkedMapOf<String, Product>()
        allProducts.forEach { product -> merged[product.id] = product }
        newItems.forEach { product -> merged[product.id] = product }
        allProducts.clear()
        allProducts.addAll(
            merged.values.sortedWith(
                compareByDescending<Product> { it.updatedAtMillis }
                    .thenBy { it.title.lowercase(Locale.getDefault()) }
            )
        )
    }

    private fun scrollProductsToTop() {
        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.adminProduitsList) ?: return
        recycler.post {
            recycler.stopScroll()
            recycler.scrollToPosition(0)
        }
    }

    private fun renderProducts() {
        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.adminProduitsList) ?: return
        val emptyView = findViewById<TextView>(R.id.adminProduitsEmpty)
        val filteredProducts = filteredProducts()

        if (isLoading && allProducts.isEmpty()) {
            emptyView?.visibility = View.GONE
            (recycler.adapter as? AdminProductsAdapter)?.updateItems(emptyList())
            return
        }

        if (filteredProducts.isEmpty() && (isLastPage || productLoadErrorMessage != null)) {
            emptyView?.visibility = View.VISIBLE
            emptyView?.text = productLoadErrorMessage ?: getString(
                if (searchQuery.isNotBlank() || selectedFilter != "all") {
                    R.string.admin_products_empty_filtered
                } else {
                    R.string.auto_aucun_produit_disponible_2470
                }
            )
            (recycler.adapter as? AdminProductsAdapter)?.updateItems(emptyList())
            return
        }

        emptyView?.visibility = View.GONE
        
        val adapter = recycler.adapter as? AdminProductsAdapter
        if (adapter == null) {
            recycler.adapter = AdminProductsAdapter(
                items = filteredProducts.toMutableList(),
                onEdit = { openProductEditor(it) },
                onDelete = { confirmDelete(it) },
                canEdit = { canEditProduct(it) },
                onEditBlocked = { showMotionSnackbar(getString(R.string.admin_product_edit_own_only)) }
            )
        } else {
            adapter.updateItems(filteredProducts)
        }
    }

    private fun filteredProducts(): List<Product> {
        return allProducts.filter { product ->
            val matchesQuery = searchQuery.isBlank() || listOf(
                product.title,
                product.subtitle,
                product.origin,
                product.category
            ).any { it.contains(searchQuery, ignoreCase = true) }

            val matchesFilter = when (selectedFilter) {
                "low_stock" -> product.isActive && product.stock in 1..5
                "inactive" -> !product.isActive
                "active" -> product.isActive
                else -> true
            }

            matchesQuery && matchesFilter
        }
    }

    private fun openProductEditor(existing: Product?) {
        if (existing != null && !canEditProduct(existing)) {
            showMotionSnackbar(getString(R.string.admin_product_edit_own_only))
            return
        }
        productEditorLauncher.launch(
            AdminProductEditorActivity.createIntent(
                context = this,
                productId = existing?.id,
                sellerMode = isSellerDashboard
            )
        )
    }

    private fun canEditProduct(product: Product): Boolean {
        val uid = FirebaseAuthManager.currentUser?.uid ?: return false
        return activeRole == UserRoles.ADMIN || product.sellerId == uid
    }

    private fun confirmDelete(product: Product) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.admin_delete_product_title, product.title))
            .setMessage(getString(R.string.admin_delete_product_message))
            .setNegativeButton(getString(R.string.admin_delete_cancel), null)
            .setPositiveButton(getString(R.string.admin_delete_confirm)) { _, _ ->
                lifecycleScope.launch {
                    runCatching { FirestoreService.deleteProduct(product.id) }
                        .onSuccess {
                            allProducts.removeAll { it.id == product.id }
                            renderProducts()
                            showMotionSnackbar(getString(R.string.admin_product_deleted))
                            loadStats()
                        }
                        .onFailure {
                            showMotionSnackbar(getString(R.string.admin_product_delete_failed))
                        }
                }
            }
            .show()
    }

    companion object {
        const val EXTRA_SELLER_MODE = "extra_seller_mode"
        private const val PRODUCTS_PAGE_TIMEOUT_MS = 15_000L
        private const val TAG = "AdminProduitsActivity"
    }
}
