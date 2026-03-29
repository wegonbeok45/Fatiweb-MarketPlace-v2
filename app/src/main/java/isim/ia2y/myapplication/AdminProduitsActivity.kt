package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import java.util.Locale

class AdminProduitsActivity : AppCompatActivity() {

    private var loadedProducts: List<Product> = emptyList()
    private val productEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                loadProducts(forceSync = true)
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
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.adminProduitsList)?.layoutManager =
            LinearLayoutManager(this)

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
            loadProducts(forceSync = false)
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
            loadProducts(forceSync = true)
        }
        applyPressFeedback(R.id.adminProduitsBtnAdd, R.id.adminProduitsBtnRefresh)
    }

    private fun setupTopBar() {
        findViewById<View?>(R.id.adminProduitsIvBack)?.setOnClickListener {
            navigateBackToMain()
        }
        applyPressFeedback(R.id.adminProduitsIvBack)
    }

    private fun loadProducts(forceSync: Boolean) {
        findViewById<ProgressBar>(R.id.adminProduitsProgress)?.visibility = View.VISIBLE
        findViewById<View>(R.id.adminProduitsEmpty)?.visibility = View.GONE
        lifecycleScope.launch {
            runCatching { CatalogSyncManager.ensureSynced(force = forceSync) }
                .onFailure {
                    showMotionSnackbar(getString(R.string.admin_products_load_error))
                }
            loadedProducts = ProductCatalog.all(includeInactive = true)
                .sortedBy { it.title.lowercase(Locale.getDefault()) }
            findViewById<ProgressBar>(R.id.adminProduitsProgress)?.visibility = View.GONE
            renderProducts()
        }
    }

    private fun renderProducts() {
        val activeCount = loadedProducts.count { it.isActive }
        val lowStockCount = loadedProducts.count { it.isActive && it.stock in 0..5 }
        findViewById<TextView>(R.id.adminProduitsTvCount)?.text = activeCount.toString()
        findViewById<TextView>(R.id.adminProduitsTvLowStock)?.text = lowStockCount.toString()

        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.adminProduitsList) ?: return
        val emptyView = findViewById<TextView>(R.id.adminProduitsEmpty)

        if (loadedProducts.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            recycler.adapter = AdminProductsAdapter(emptyList(), {}, {})
            return
        }

        emptyView?.visibility = View.GONE
        recycler.adapter = AdminProductsAdapter(
            items = loadedProducts,
            onEdit = { openProductEditor(existing = it) },
            onDelete = { confirmDelete(it) }
        )
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
                            loadedProducts = loadedProducts.filterNot { it.id == product.id }
                            renderProducts()
                            showToast(getString(R.string.admin_product_deleted))
                        }
                        .onFailure {
                            showMotionSnackbar(getString(R.string.admin_product_delete_failed))
                        }
                }
            }
            .show()
    }
}
