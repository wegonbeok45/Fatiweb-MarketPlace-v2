package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import androidx.core.widget.NestedScrollView
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
import kotlinx.coroutines.launch
import java.util.Locale

class AdminProduitsActivity : AppCompatActivity() {
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
        setupActions()
        setupFilters()

        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.adminProduitsList)
        recycler?.layoutManager = LinearLayoutManager(this)
        
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
            if (!requireAdminRole()) return@launch

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
            keepPrimaryActionsVisible()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.PRODUITS)
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
        keepPrimaryActionsVisible()
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
        lifecycleScope.launch {
            runCatching { AdminService.fetchAdminStats() }
                .onSuccess { stats ->
                    findViewById<TextView>(R.id.adminProduitsTvCount)?.text = stats.totalProducts.toString()
                    findViewById<TextView>(R.id.adminProduitsTvLowStock)?.text = stats.lowStockProducts.toString()
                }
        }
    }

    private fun loadNextPage() {
        if (isLoading || isLastPage) return
        isLoading = true
        findViewById<ProgressBar>(R.id.adminProduitsProgress)?.visibility = View.VISIBLE

        lifecycleScope.launch {
            runCatching { 
                ProductService.fetchProductsPaginated(pageSize, lastVisible)
            }.onSuccess { (newItems, lastDoc) ->
                isLoading = false
                findViewById<ProgressBar>(R.id.adminProduitsProgress)?.visibility = View.GONE
                
                if (newItems.isEmpty()) {
                    isLastPage = true
                } else {
                    allProducts.addAll(newItems)
                    lastVisible = lastDoc
                    if (newItems.size < pageSize.toInt()) isLastPage = true
                }
                renderProducts()
                keepPrimaryActionsVisible()
            }.onFailure {
                isLoading = false
                findViewById<ProgressBar>(R.id.adminProduitsProgress)?.visibility = View.GONE
                showMotionSnackbar(getString(R.string.admin_products_load_error))
            }
        }
    }

    private fun keepPrimaryActionsVisible() {
        val scrollView = findViewById<NestedScrollView>(R.id.adminProduitsScroll) ?: return
        scrollView.post {
            scrollView.scrollTo(0, 0)
            findViewById<View>(R.id.adminProduitsBtnAdd)?.requestFocus()
        }
    }

    private fun renderProducts() {
        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.adminProduitsList) ?: return
        val emptyView = findViewById<TextView>(R.id.adminProduitsEmpty)
        val filteredProducts = filteredProducts()

        if (filteredProducts.isEmpty() && isLastPage) {
            emptyView?.visibility = View.VISIBLE
            emptyView?.text = getString(
                if (searchQuery.isNotBlank() || selectedFilter != "all") {
                    R.string.admin_products_empty_filtered
                } else {
                    R.string.auto_aucun_produit_disponible_2470
                }
            )
            recycler.adapter = AdminProductsAdapter(mutableListOf(), {}, {})
            return
        }

        emptyView?.visibility = View.GONE
        
        val adapter = recycler.adapter as? AdminProductsAdapter
        if (adapter == null) {
            recycler.adapter = AdminProductsAdapter(
                items = filteredProducts.toMutableList(),
                onEdit = { openProductEditor(it) },
                onDelete = { confirmDelete(it) }
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
        productEditorLauncher.launch(
            AdminProductEditorActivity.createIntent(
                context = this,
                productId = existing?.id
            )
        )
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
                            showToast(getString(R.string.admin_product_deleted))
                            loadStats()
                        }
                        .onFailure {
                            showMotionSnackbar(getString(R.string.admin_product_delete_failed))
                        }
                }
            }
            .show()
    }
}
