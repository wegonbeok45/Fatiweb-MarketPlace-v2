package isim.ia2y.myapplication

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import java.util.Locale

private const val EXTRA_ADMIN_DASHBOARD_TAB = "extra_admin_dashboard_tab"
private const val EXTRA_ADMIN_TAB_SWITCH = "extra_admin_tab_switch"

class AdminDashboardActivity : AppCompatActivity() {

    private val viewModel: AdminViewModel by viewModels()
    private val logTag = "AdminDashboard"
    private val stateTabKey = "admin_dashboard_active_tab"
    private var commandesContent: View? = null
    private var clientsContent: View? = null
    private var activeTab: DashboardInlineTab = DashboardInlineTab.OVERVIEW

    private val ordersAdapter = AdminOrdersAdapter { uid, order -> showStatusDialogInline(uid, order) }
    private val clientsAdapter = AdminClientsAdapter { client ->
        startActivity(AdminClientDetailsActivity.createIntent(this, client.uid))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val uid = FirebaseAuthManager.currentUser?.uid
        AdminSession.clearIfDifferent(uid)
        if (uid == null) {
            finish()
            return
        }

        runCatching {
            setContentView(R.layout.activity_admin_dashboard)
            setupAdminWindowInsets(R.id.adminAppBar)
            setupTopBar()
            setupBottomNav()
            setupBackHandling()
            setupQuickActions()
            setupObservers()
            renderAdminStatsPlaceholder()

            if (viewModel.isVerified.value != true) {
                viewModel.verifyAdmin(uid)
            }

            val restoredTab = savedInstanceState?.getString(stateTabKey)
                ?.let { runCatching { DashboardInlineTab.valueOf(it) }.getOrNull() }
            val requestedTab = requestedInlineTabFromIntent(intent.getStringExtra(EXTRA_ADMIN_DASHBOARD_TAB))

            if (savedInstanceState == null) {
                if (!intent.getBooleanExtra(EXTRA_ADMIN_TAB_SWITCH, false)) {
                    revealViewsInOrder(
                        R.id.adminTopBar,
                        R.id.adminCardWelcome,
                        R.id.adminTvStatsHeader,
                        R.id.adminStatsRow1,
                        R.id.adminTvActionsHeader,
                        R.id.adminActionsRow,
                        R.id.adminTvOrdersHeader,
                        R.id.adminCardOrders,
                        R.id.adminBottomNav,
                        startDelayMs = 60L,
                        staggerMs = 48L
                    )
                }
                viewModel.setTab(requestedTab ?: DashboardInlineTab.OVERVIEW)
            } else if (restoredTab != null) {
                viewModel.setTab(restoredTab)
            }

            viewModel.loadDashboardData()
        }.onFailure { e ->
            Log.e(logTag, "Failed to init admin dashboard", e)
            showMotionSnackbar(getString(R.string.admin_dashboard_load_failed))
            finish()
        }
    }

    private fun setupObservers() {
        viewModel.isVerified.observe(this) { verified ->
            // Access denial is handled asynchronously by the viewModel.error observer.
            // Do not call finish() here to avoid premature immediate exit on initial false state.
            if (verified == true) {
                // Retry the gated dashboard load once verification flips to true.
                viewModel.loadDashboardData()
            }
        }

        viewModel.activeTab.observe(this) { tab ->
            activeTab = tab
            renderSelectedTab(tab, animate = false)
        }

        viewModel.stats.observe(this) { stats ->
            renderAdminStats(stats, viewModel.orders.value.orEmpty())
        }

        viewModel.orders.observe(this) { orders ->
            renderInlineOrders(orders)
            renderDashboardOrdersPreview(orders)
            renderAdminStats(viewModel.stats.value ?: FirestoreService.AdminStats(), orders)
        }

        viewModel.clients.observe(this) { clients ->
            renderInlineClients(clients)
        }

        viewModel.error.observe(this) { error ->
            error?.let {
                showMotionSnackbar(it)
                if (it == getString(R.string.admin_access_denied)) {
                    finish()
                }
                viewModel.clearError()
            }
        }

        viewModel.statsLoadFailed.observe(this) { failed ->
            // When the dashboard fetch fails, swap the "..." placeholder to a retry hint and
            // make the stats row tappable to force-reload. On success, restore default state.
            val statsRow = findViewById<View>(R.id.adminStatsRow1)
            if (failed == true) {
                val retryHint = getString(R.string.admin_dashboard_stats_retry_hint)
                listOf(
                    R.id.adminTvCommandesVal,
                    R.id.adminTvRevenueVal,
                    R.id.adminTvClientsVal,
                    R.id.adminTvStockVal
                ).forEach { id ->
                    findViewById<TextView>(id)?.text = "↻"
                }
                findViewById<TextView>(R.id.adminTvWelcomeChipSync)?.text = retryHint
                statsRow?.setOnClickListener {
                    viewModel.loadDashboardData(force = true)
                }
            } else {
                statsRow?.setOnClickListener(null)
                statsRow?.isClickable = false
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestedTab = requestedInlineTabFromIntent(intent.getStringExtra(EXTRA_ADMIN_DASHBOARD_TAB)) ?: return
        switchTab(requestedTab, animate = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(stateTabKey, activeTab.name)
    }

    private fun setupTopBar() {
        setupAdminTopBar(getString(R.string.admin_title_dashboard))
    }

    private fun setupBottomNav() {
        setupAdminBottomNav(AdminNavTab.DASHBOARD)

        findViewById<View>(R.id.adminNavDashboard)?.setOnClickListener {
            switchTab(DashboardInlineTab.OVERVIEW, animate = true)
        }
        findViewById<View>(R.id.adminNavCommandes)?.setOnClickListener {
            switchTab(DashboardInlineTab.COMMANDES, animate = true)
        }
        findViewById<View>(R.id.adminNavClients)?.setOnClickListener {
            switchTab(DashboardInlineTab.CLIENTS, animate = true)
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this) {
            if (activeTab == DashboardInlineTab.OVERVIEW) {
                navigateAdminBack(AdminNavTab.DASHBOARD)
            } else {
                switchTab(DashboardInlineTab.OVERVIEW, animate = true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(activeTab.navTab)
    }

    private fun setupQuickActions() {
        applyPressFeedback(
            R.id.adminCardCommandes,
            R.id.adminCardClients,
            R.id.adminBtnAddProduct,
            R.id.adminBtnViewOrders,
            R.id.adminBtnSettings
        )

        findViewById<View>(R.id.adminCardCommandes)?.setOnClickListener {
            switchTab(DashboardInlineTab.COMMANDES, animate = true)
        }
        findViewById<View>(R.id.adminCardClients)?.setOnClickListener {
            switchTab(DashboardInlineTab.CLIENTS, animate = true)
        }
        findViewById<View>(R.id.adminBtnViewOrders)?.setOnClickListener {
            switchTab(DashboardInlineTab.COMMANDES, animate = true)
        }
        findViewById<View>(R.id.adminBtnAddProduct)?.setOnClickListener {
            navigateNoShift(AdminProduitsActivity::class.java)
        }

        findViewById<View>(R.id.adminBtnSettings)?.setOnClickListener {
            navigateNoShift(AdminParametresActivity::class.java)
        }

        listOf(R.id.adminOrderRow1, R.id.adminOrderRow2, R.id.adminOrderRow3).forEach { id ->
            findViewById<View>(id)?.visibility = View.GONE
        }
    }

    private fun switchTab(tab: DashboardInlineTab, animate: Boolean) {
        if (tab == activeTab && tab != DashboardInlineTab.OVERVIEW) {
            viewModel.loadDashboardData(force = true)
            return
        }

        if (viewModel.activeTab.value != tab) {
            viewModel.setTab(tab)
        } else {
            renderSelectedTab(tab, animate)
        }
    }

    private fun renderSelectedTab(tab: DashboardInlineTab, animate: Boolean) {
        setupAdminTopBar(getString(tab.titleRes))
        findViewById<View?>(R.id.adminIvBack)?.setOnClickListener {
            if (tab == DashboardInlineTab.OVERVIEW) {
                navigateAdminBack(AdminNavTab.DASHBOARD)
            } else {
                switchTab(DashboardInlineTab.OVERVIEW, animate = true)
            }
        }
        selectAdminBottomNav(tab.navTab, animate = animate)

        when (tab) {
            DashboardInlineTab.OVERVIEW -> showOverviewContent(animate)
            DashboardInlineTab.COMMANDES -> {
                val content = ensureInlineCommandesView()
                showInlineContent(content, animate)
                loadInlineOrders()
            }
            DashboardInlineTab.CLIENTS -> {
                val content = ensureInlineClientsView()
                showInlineContent(content, animate)
                loadInlineClients()
            }
        }
    }

    private fun loadInlineOrders() {
        val content = ensureInlineCommandesView()
        val recycler = content.findViewById<RecyclerView>(R.id.adminInlineOrdersRecyclerView)
        if (recycler.layoutManager == null) {
            recycler.layoutManager = LinearLayoutManager(this)
        }
        recycler.adapter = ordersAdapter
        renderInlineOrders(viewModel.orders.value.orEmpty())
        if (viewModel.orders.value == null) viewModel.loadDashboardData()
    }

    private fun loadInlineClients() {
        val content = ensureInlineClientsView()
        val recycler = content.findViewById<RecyclerView>(R.id.adminInlineClientsRecyclerView)
        if (recycler.layoutManager == null) {
            recycler.layoutManager = LinearLayoutManager(this)
        }
        recycler.adapter = clientsAdapter
        renderInlineClients(viewModel.clients.value.orEmpty())
        if (viewModel.clients.value == null) viewModel.loadDashboardData()
    }

    private fun renderInlineOrders(orders: List<Pair<String, AppOrder>>) {
        ordersAdapter.submitList(orders)
        val content = commandesContent ?: return
        content.findViewById<TextView>(R.id.adminInlineCommandesTvTotal)?.text = orders.size.toString()
        content.findViewById<TextView>(R.id.adminInlineCommandesTvPending)?.text =
            orders.count { it.second.status == "pending" }.toString()
        content.findViewById<TextView>(R.id.adminInlineCommandesTvDelivered)?.text =
            orders.count { it.second.status == "delivered" }.toString()
        content.findViewById<RecyclerView>(R.id.adminInlineOrdersRecyclerView)?.adapter = ordersAdapter
    }

    private fun renderInlineClients(clients: List<FirestoreService.ClientInfo>) {
        clientsAdapter.submitList(clients)
        val content = clientsContent ?: return
        content.findViewById<TextView>(R.id.adminInlineClientsTvTotal)?.text = clients.size.toString()
        content.findViewById<RecyclerView>(R.id.adminInlineClientsRecyclerView)?.adapter = clientsAdapter
    }

    private fun ensureInlineCommandesView(): View {
        commandesContent?.let { return it }
        val view = LayoutInflater.from(this)
            .inflate(R.layout.layout_admin_inline_commandes, findViewById<FrameLayout>(R.id.adminInlineContentContainer), false)
        commandesContent = view
        return view
    }

    private fun ensureInlineClientsView(): View {
        clientsContent?.let { return it }
        val view = LayoutInflater.from(this)
            .inflate(R.layout.layout_admin_inline_clients, findViewById<FrameLayout>(R.id.adminInlineContentContainer), false)
        clientsContent = view
        return view
    }

    private fun showOverviewContent(animate: Boolean) {
        val overview = findViewById<NestedScrollView>(R.id.adminScrollContent)
        val inline = findViewById<FrameLayout>(R.id.adminInlineContentContainer)

        if (!animate || isReducedMotionEnabled()) {
            inline.visibility = View.GONE
            overview.visibility = View.VISIBLE
            overview.alpha = 1f
            overview.translationY = 0f
            return
        }

        val distance = 10f * resources.displayMetrics.density
        inline.animate().cancel()
        overview.animate().cancel()

        if (inline.visibility == View.VISIBLE) {
            inline.animate()
                .alpha(0f)
                .translationY(-distance)
                .setDuration(MotionTokens.STANDARD)
                .withEndAction {
                    inline.visibility = View.GONE
                    inline.alpha = 1f
                    inline.translationY = 0f
                }
                .start()
        }

        overview.alpha = 0f
        overview.translationY = distance
        overview.visibility = View.VISIBLE
        overview.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(MotionTokens.STANDARD)
            .start()
    }

    private fun showInlineContent(content: View, animate: Boolean) {
        val overview = findViewById<NestedScrollView>(R.id.adminScrollContent)
        val inline = findViewById<FrameLayout>(R.id.adminInlineContentContainer)

        if (content.parent != inline) {
            inline.removeAllViews()
            inline.addView(content)
        }

        if (!animate || isReducedMotionEnabled()) {
            overview.visibility = View.GONE
            inline.visibility = View.VISIBLE
            inline.alpha = 1f
            inline.translationY = 0f
            return
        }

        val distance = 10f * resources.displayMetrics.density
        overview.animate().cancel()
        inline.animate().cancel()

        if (overview.visibility == View.VISIBLE) {
            overview.animate()
                .alpha(0f)
                .translationY(-distance)
                .setDuration(MotionTokens.STANDARD)
                .withEndAction {
                    overview.visibility = View.GONE
                    overview.alpha = 1f
                    overview.translationY = 0f
                }
                .start()
        }

        inline.alpha = 0f
        inline.translationY = distance
        inline.visibility = View.VISIBLE
        inline.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(MotionTokens.STANDARD)
            .start()
    }

    private fun renderAdminStats(
        stats: FirestoreService.AdminStats,
        orders: List<Pair<String, AppOrder>>
    ) {
        val pendingOrders = orders.count { it.second.status == "pending" }
        val averageBasket = if (stats.totalOrders > 0) stats.totalRevenue / stats.totalOrders else 0.0
        val inactiveProducts = (stats.totalProducts - stats.activeProducts).coerceAtLeast(0)

        findViewById<TextView>(R.id.adminTvCommandesVal)?.text = stats.totalOrders.toString()
        findViewById<TextView>(R.id.adminTvRevenueVal)?.text =
            formatDt(stats.totalRevenue)
        findViewById<TextView>(R.id.adminTvClientsVal)?.text = stats.totalClients.toString()
        findViewById<TextView>(R.id.adminTvStockVal)?.text = stats.lowStockProducts.toString()
        findViewById<TextView>(R.id.adminTvWelcomeChipSync)?.text =
            getString(R.string.admin_dashboard_chip_sync)
        findViewById<TextView>(R.id.adminTvWelcomeChipInventory)?.text =
            getString(R.string.admin_dashboard_chip_inventory, stats.activeProducts)
        findViewById<TextView>(R.id.adminTvRevenueDelta)?.visibility = View.GONE
        findViewById<TextView>(R.id.adminTvCommandesDelta)?.apply {
            text = getString(R.string.admin_dashboard_chip_attention, pendingOrders)
            visibility = if (pendingOrders > 0) View.VISIBLE else View.GONE
        }
        findViewById<TextView>(R.id.adminTvClientsDelta)?.visibility = View.GONE
        findViewById<TextView>(R.id.adminTvStockDelta)?.visibility = View.GONE
        findViewById<TextView>(R.id.adminTvCommandesMeta)?.text =
            getString(R.string.admin_dashboard_orders_meta, pendingOrders)
        findViewById<TextView>(R.id.adminTvRevenueMeta)?.text =
            getString(R.string.admin_dashboard_revenue_meta, formatDt(averageBasket))
        findViewById<TextView>(R.id.adminTvClientsMeta)?.text =
            getString(R.string.admin_dashboard_clients_meta, stats.totalClients)
        findViewById<TextView>(R.id.adminTvStockMeta)?.text =
            getString(R.string.admin_dashboard_stock_meta, inactiveProducts)
    }

    private fun renderAdminStatsPlaceholder() {
        val placeholder = getString(R.string.str_2f43b4)
        listOf(
            R.id.adminTvCommandesVal,
            R.id.adminTvRevenueVal,
            R.id.adminTvClientsVal,
            R.id.adminTvStockVal
        ).forEach { id ->
            findViewById<TextView>(id)?.text = placeholder
        }
        findViewById<TextView>(R.id.adminTvWelcomeChipSync)?.text =
            getString(R.string.admin_dashboard_chip_sync)
        findViewById<TextView>(R.id.adminTvWelcomeChipInventory)?.text =
            getString(R.string.admin_dashboard_chip_inventory, 0)
    }

    private fun renderDashboardOrdersPreview(orders: List<Pair<String, AppOrder>>) {
        val rows = listOf(
            intArrayOf(R.id.adminOrderRow1, R.id.adminOrderId1, R.id.adminOrderName1, R.id.adminOrderPrice1),
            intArrayOf(R.id.adminOrderRow2, R.id.adminOrderId2, R.id.adminOrderName2, R.id.adminOrderPrice2),
            intArrayOf(R.id.adminOrderRow3, R.id.adminOrderId3, R.id.adminOrderName3, R.id.adminOrderPrice3)
        )

        rows.forEachIndexed { index, ids ->
            val row = findViewById<View>(ids[0]) ?: return@forEachIndexed
            val item = orders.getOrNull(index)
            if (item == null) {
                row.visibility = View.GONE
                row.setOnClickListener(null)
                return@forEachIndexed
            }

            val (uid, order) = item
            findViewById<TextView>(ids[1])?.text = order.displayId
            findViewById<TextView>(ids[2])?.text = getString(
                R.string.admin_dashboard_recent_order_subtitle,
                order.shippingAddress?.recipientName?.takeIf { it.isNotBlank() }
                    ?: getString(R.string.admin_client_fallback_name),
                order.statusLabel(this)
            )
            findViewById<TextView>(ids[3])?.text = formatDt(order.total)
            row.visibility = View.VISIBLE
            row.setOnClickListener { showStatusDialogInline(uid, order) }
        }
    }

    private fun showStatusDialogInline(uid: String, order: AppOrder) {
        val keys = OrderStatuses.supported.toTypedArray()
        val statuses = keys.map { orderStatusLabel(this, it) }.toTypedArray()
        val currentIndex = keys.indexOf(OrderStatuses.normalize(order.status)).coerceAtLeast(0)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.admin_change_status_title, order.displayId))
            .setMessage(order.statusLabel(this))
            .setSingleChoiceItems(statuses, currentIndex) { dialog, which ->
                val newStatus = keys[which]
                dialog.dismiss()
                viewModel.updateOrderStatus(uid, order.id, newStatus)
            }
            .setNegativeButton(getString(R.string.admin_delete_cancel), null)
            .show()
    }



    private fun requestedInlineTabFromIntent(tabName: String?): DashboardInlineTab? {
        val nav = tabName?.let {
            runCatching { AdminNavTab.valueOf(it) }.getOrNull()
        } ?: return null
        return when (nav) {
            AdminNavTab.COMMANDES -> DashboardInlineTab.COMMANDES
            AdminNavTab.CLIENTS -> DashboardInlineTab.CLIENTS
            else -> DashboardInlineTab.OVERVIEW
        }
    }




}
