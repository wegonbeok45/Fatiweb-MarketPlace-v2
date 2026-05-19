package isim.ia2y.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class SellerDashboardActivity : AppCompatActivity() {
    private var roleVerified = false
    private val recentOrdersAdapter = SellerOrdersAdapter { _ ->
        navigateNoShift(SellerOrdersActivity::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_seller_dashboard)
        setupInsets()
        setupActions()
        setupStatCards()

        findViewById<RecyclerView>(R.id.sellerDashboardRecentOrders)?.apply {
            layoutManager = LinearLayoutManager(this@SellerDashboardActivity)
            adapter = recentOrdersAdapter
            isNestedScrollingEnabled = false
        }
        renderStats(AdminService.SellerOrderStats(), AdminService.SellerProductsSummary())
        renderRecentOrders(emptyList())

        lifecycleScope.launch {
            if (requireAdminOrVendeurRole() == null) return@launch
            roleVerified = true
            if (savedInstanceState == null) {
                revealViewsInOrder(
                    R.id.sellerDashboardTopBar,
                    R.id.sellerDashboardHero,
                    R.id.sellerDashboardStatsRow1,
                    R.id.sellerDashboardStatsRow2,
                    R.id.sellerDashboardQuickActions,
                    R.id.sellerDashboardRecentCard,
                    startDelayMs = 60L,
                    staggerMs = 46L
                )
            }
            loadDashboard()
        }
    }

    override fun onResume() {
        super.onResume()
        if (roleVerified) loadDashboard()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.sellerDashboardAppBar)) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = statusBars.top)
            insets
        }
    }

    private fun setupActions() {
        findViewById<View>(R.id.sellerDashboardIvBack)?.setOnClickListener { navigateBackToMain() }
        findViewById<View>(R.id.sellerDashboardBtnManageProducts)?.setOnClickListener { openProducts() }
        findViewById<View>(R.id.sellerDashboardBtnAddProduct)?.setOnClickListener { openAddProduct() }
        findViewById<View>(R.id.sellerDashboardBtnOrders)?.setOnClickListener {
            navigateNoShift(SellerOrdersActivity::class.java)
        }
        applyPressFeedback(
            R.id.sellerDashboardIvBack,
            R.id.sellerDashboardBtnManageProducts,
            R.id.sellerDashboardBtnAddProduct,
            R.id.sellerDashboardBtnOrders
        )
    }

    private fun setupStatCards() {
        bindStatCard(R.id.sellerDashboardClientsCard, R.string.seller_dashboard_clients_bought)
        bindStatCard(R.id.sellerDashboardRevenueCard, R.string.seller_dashboard_money_received)
        bindStatCard(R.id.sellerDashboardOrdersCard, R.string.seller_dashboard_orders_to_track)
        bindStatCard(R.id.sellerDashboardProductsCard, R.string.seller_dashboard_products_overview)
    }

    private fun bindStatCard(cardId: Int, labelRes: Int) {
        findViewById<View>(cardId)?.findViewById<TextView>(R.id.sellerStatLabel)?.text = getString(labelRes)
    }

    private fun openProducts() {
        startActivity(
            Intent(this, AdminProduitsActivity::class.java)
                .putExtra(AdminProduitsActivity.EXTRA_SELLER_MODE, true)
        )
    }

    private fun openAddProduct() {
        startActivity(
            AdminProductEditorActivity.createIntent(
                context = this,
                productId = null,
                sellerMode = true
            )
        )
    }

    private fun loadDashboard() {
        val uid = FirebaseAuthManager.currentUser?.uid ?: return
        findViewById<ProgressBar>(R.id.sellerDashboardProgress)?.visibility = View.VISIBLE
        lifecycleScope.launch {
            runCatching {
                withTimeout(DASHBOARD_LOAD_TIMEOUT_MS) {
                    AdminService.fetchSellerWorkspace(uid)
                }
            }.onSuccess { workspace ->
                val rows = workspace.orders.toSellerRows(uid)
                findViewById<ProgressBar>(R.id.sellerDashboardProgress)?.visibility = View.GONE
                renderStats(AdminService.sellerOrderStats(rows), workspace.products)
                renderRecentOrders(rows)
            }.onFailure {
                findViewById<ProgressBar>(R.id.sellerDashboardProgress)?.visibility = View.GONE
                renderStats(AdminService.SellerOrderStats(), AdminService.SellerProductsSummary())
                renderRecentOrders(emptyList())
                showMotionSnackbar(getString(R.string.seller_dashboard_load_failed))
            }
        }
    }

    private fun renderStats(
        stats: AdminService.SellerOrderStats,
        products: AdminService.SellerProductsSummary
    ) {
        statValue(R.id.sellerDashboardClientsCard)?.text = stats.uniqueClients.toString()
        statValue(R.id.sellerDashboardRevenueCard)?.text = formatDt(stats.totalRevenue)
        statValue(R.id.sellerDashboardOrdersCard)?.text = stats.ordersToTrack.toString()
        statValue(R.id.sellerDashboardProductsCard)?.text =
            getString(R.string.seller_dashboard_products_ratio, products.activeProducts, products.totalProducts)
        findViewById<TextView>(R.id.sellerDashboardTvHeroMeta)?.text =
            getString(R.string.seller_dashboard_hero_meta, stats.totalOrders, stats.totalItems)
        findViewById<TextView>(R.id.sellerDashboardTvStockMeta)?.text =
            getString(R.string.seller_dashboard_stock_meta, products.lowStockProducts)
    }

    private fun renderRecentOrders(rows: List<AdminService.SellerOrderRow>) {
        val latest = rows.take(3)
        recentOrdersAdapter.submitList(latest)
        findViewById<TextView>(R.id.sellerDashboardRecentEmpty)?.visibility =
            if (latest.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun statValue(cardId: Int): TextView? =
        findViewById<View>(cardId)?.findViewById(R.id.sellerStatValue)

    private fun List<AppOrder>.toSellerRows(sellerId: String): List<AdminService.SellerOrderRow> {
        return mapNotNull { order ->
            val sellerItems = order.items.filter { it.sellerId == sellerId }
            if (sellerItems.isEmpty()) return@mapNotNull null
            AdminService.SellerOrderRow(order.uid, order, sellerItems)
        }.sortedByDescending { it.order.createdAtMillis }
    }

    private companion object {
        private const val DASHBOARD_LOAD_TIMEOUT_MS = 15_000L
    }
}
