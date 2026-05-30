package isim.ia2y.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import isim.ia2y.myapplication.ui.base.BaseListAdapter
import isim.ia2y.myapplication.ui.base.MsStatusPill
import isim.ia2y.myapplication.ui.base.idDiff
import isim.ia2y.myapplication.ui.state.StateRenderer
import isim.ia2y.myapplication.ui.state.UiState
import kotlinx.coroutines.launch

/**
 * Rebuilt admin home dashboard. Supersedes [AdminDashboardActivity] as the
 * primary admin entry point after Phase 5 wiring.
 *
 * Layout: hero KPIs (revenue + orders) → pending-approvals banner →
 * 3×2 platform KPI grid → quick-action tiles → recent orders feed.
 */
class AdminHomeActivity : AppCompatActivity() {

    private val viewModel: AdminHomeViewModel by viewModels()
    private var roleVerified = false

    private val ordersAdapter = BaseListAdapter<Pair<String, AppOrder>>(
        layoutRes = R.layout.ms_component_list_row,
        diff = idDiff { "${it.first}:${it.second.id}" },
        bind = { view, row -> bindOrderRow(view, row) },
        onClick = { row -> openOrderDetail(row.first, row.second) },
    )

    private lateinit var activityState: StateRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_home)
        applyInsets()
        setupSectionHeaders()
        setupQuickActions()
        setupOrdersList()
        setupBottomNav()

        activityState = StateRenderer(
            loadingView = findViewById(R.id.adminHomeActivityLoading),
            emptyView = findViewById(R.id.adminHomeActivityEmpty),
            errorView = findViewById(R.id.adminHomeActivityError),
            dataView = findViewById(R.id.adminHomeActivityList),
        ).also { renderer ->
            renderer.bindEmpty(
                titleRes = R.string.vendor_home_activity_empty_title,
                subtitleRes = R.string.vendor_home_activity_empty_subtitle,
            )
            renderer.bindError(
                titleRes = R.string.ms_error_default_title,
                subtitleRes = R.string.admin_home_load_failed,
                onRetry = { viewModel.refresh() },
            )
        }

        observeState()
        lifecycleScope.launch {
            if (!requireAdminRole()) return@launch
            roleVerified = true
            viewModel.load()
        }
    }

    override fun onResume() {
        super.onResume()
        if (roleVerified) viewModel.refresh()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminHomeAppBar)) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminHomeScroll)) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }
    }

    private fun setupSectionHeaders() {
        findViewById<View>(R.id.adminHomeOverviewHeader)
            ?.findViewById<TextView>(R.id.msSectionTitle)
            ?.setText(R.string.ms_section_overview)
        findViewById<View>(R.id.adminHomeActionsHeader)
            ?.findViewById<TextView>(R.id.msSectionTitle)
            ?.setText(R.string.ms_section_quick_actions)
        findViewById<View>(R.id.adminHomeActivityHeader)
            ?.findViewById<TextView>(R.id.msSectionTitle)
            ?.setText(R.string.admin_home_recent_orders_title)
    }

    private fun setupQuickActions() {
        bindTile(
            R.id.adminHomeActionVendors,
            R.drawable.ic_admin_shield,
            R.string.admin_home_action_vendors_title,
            R.string.admin_home_action_vendors_subtitle,
        ) { openVendors() }
        bindTile(
            R.id.adminHomeActionUsers,
            R.drawable.ic_admin_nav_clients,
            R.string.admin_home_action_users_title,
            R.string.admin_home_action_users_subtitle,
        ) { openUsers() }
        bindTile(
            R.id.adminHomeActionOrders,
            R.drawable.ic_admin_nav_commandes,
            R.string.admin_home_action_orders_title,
            R.string.admin_home_action_orders_subtitle,
        ) { openOrders() }
        bindTile(
            R.id.adminHomeActionAnalytics,
            R.drawable.ic_feedback_info,
            R.string.admin_home_action_analytics_title,
            R.string.admin_home_action_analytics_subtitle,
        ) { openAnalytics() }
        bindTile(
            R.id.adminHomeActionSettings,
            R.drawable.ic_admin_nav_settings,
            R.string.admin_home_action_settings_title,
            R.string.admin_home_action_settings_subtitle,
        ) { openSettings() }
    }

    private fun bindTile(
        containerId: Int,
        iconRes: Int,
        titleRes: Int,
        subtitleRes: Int,
        onClick: () -> Unit,
    ) {
        val root = findViewById<View>(containerId) ?: return
        root.findViewById<ImageView>(R.id.msTileIcon)?.setImageResource(iconRes)
        root.findViewById<TextView>(R.id.msTileTitle)?.setText(titleRes)
        root.findViewById<TextView>(R.id.msTileSubtitle)?.apply {
            setText(subtitleRes)
            visibility = View.VISIBLE
        }
        root.setOnClickListener { onClick() }
        root.applyPressFeedback()
    }

    private fun setupOrdersList() {
        findViewById<RecyclerView>(R.id.adminHomeActivityList)?.apply {
            layoutManager = LinearLayoutManager(this@AdminHomeActivity)
            adapter = ordersAdapter
            isNestedScrollingEnabled = false
            setHasFixedSize(false)
        }
    }

    private fun setupBottomNav() {
        bindAdminBack(AdminNavTab.DASHBOARD)
        // Home tab active — other tabs navigate away.
        findViewById<View>(R.id.adminNavCommandes)?.setOnClickListener { openOrders() }
        findViewById<View>(R.id.adminNavProduits)?.setOnClickListener { openProducts() }
        findViewById<View>(R.id.adminNavClients)?.setOnClickListener { openUsers() }
        findViewById<View>(R.id.adminNavNotifications)?.setOnClickListener {
            openNotificationsScreenWithPermissionCheck()
        }
        findViewById<View>(R.id.adminNavSettings)?.setOnClickListener { openSettings() }
        // Bell in top bar
        findViewById<View>(R.id.adminHomeBell)?.setOnClickListener {
            openNotificationsScreenWithPermissionCheck()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: UiState<AdminHomeViewModel.Data>) {
        when (state) {
            UiState.Loading -> {
                activityState.render(UiState.Loading)
                bindKpis(empty = true)
                bindHero(null)
                hidePendingBanner()
            }
            UiState.Empty -> {
                activityState.render(UiState.Empty)
                bindKpis(empty = true)
                bindHero(null)
                hidePendingBanner()
            }
            is UiState.Error -> {
                activityState.render(state)
                bindKpis(empty = true)
                bindHero(null)
                hidePendingBanner()
            }
            is UiState.Data -> {
                val data = state.value
                bindHero(data)
                bindKpis(empty = false, data = data)
                bindPendingBanner(data.pendingVendors)
                if (data.recentOrders.isEmpty()) {
                    activityState.render(UiState.Empty)
                    ordersAdapter.submitList(emptyList())
                } else {
                    activityState.render(state)
                    ordersAdapter.submitList(data.recentOrders)
                }
            }
        }
    }

    // ===== Hero =====

    private fun bindHero(data: AdminHomeViewModel.Data?) {
        val revenue = findViewById<TextView>(R.id.adminHomeHeroRevenue)
        val orders = findViewById<TextView>(R.id.adminHomeHeroOrders)
        revenue?.text = formatCurrency(data?.stats?.totalRevenue ?: 0.0)
        orders?.text = (data?.stats?.totalOrders ?: 0).toString()
    }

    // ===== Pending approvals banner =====

    private fun bindPendingBanner(count: Int) {
        val card = findViewById<View>(R.id.adminHomePendingCard) ?: return
        if (count <= 0) {
            card.visibility = View.GONE
            return
        }
        card.visibility = View.VISIBLE
        val tv = card.findViewById<TextView>(R.id.adminHomePendingCount)
        tv?.text = getString(R.string.admin_home_pending_approvals_count, count)
        card.findViewById<View>(R.id.adminHomePendingCta)?.setOnClickListener { openVendors() }
    }

    private fun hidePendingBanner() {
        findViewById<View>(R.id.adminHomePendingCard)?.visibility = View.GONE
    }

    // ===== KPI grid =====

    private fun bindKpis(empty: Boolean, data: AdminHomeViewModel.Data? = null) {
        val stats = data?.stats ?: FirestoreService.AdminStats()
        bindKpiCard(
            R.id.adminHomeKpiUsers,
            iconRes = R.drawable.ic_admin_nav_clients,
            valueText = (if (empty) 0 else stats.totalClients).toString(),
            labelRes = R.string.admin_home_kpi_users_label,
        )
        bindKpiCard(
            R.id.adminHomeKpiVendors,
            iconRes = R.drawable.ic_admin_shield,
            valueText = (if (empty) 0 else (data?.totalVendors ?: 0)).toString(),
            labelRes = R.string.admin_home_kpi_vendors_label,
            statusKind = if (!empty && (data?.pendingVendors ?: 0) > 0) MsStatusPill.Kind.Pending else null,
        )
        bindKpiCard(
            R.id.adminHomeKpiProducts,
            iconRes = R.drawable.ic_admin_nav_produits,
            valueText = (if (empty) 0 else stats.activeProducts).toString(),
            labelRes = R.string.admin_home_kpi_products_label,
        )
        bindKpiCard(
            R.id.adminHomeKpiOrders,
            iconRes = R.drawable.ic_admin_nav_commandes,
            valueText = (if (empty) 0 else stats.totalOrders).toString(),
            labelRes = R.string.admin_home_kpi_orders_label,
        )
        bindKpiCard(
            R.id.adminHomeKpiPendingVendors,
            iconRes = R.drawable.ic_feedback_warning,
            valueText = (if (empty) 0 else (data?.pendingVendors ?: 0)).toString(),
            labelRes = R.string.vendor_home_kpi_pending_label,
            statusKind = if (!empty && (data?.pendingVendors ?: 0) > 0) MsStatusPill.Kind.Pending else null,
        )
        bindKpiCard(
            R.id.adminHomeKpiLowStock,
            iconRes = R.drawable.ic_feedback_warning,
            valueText = (if (empty) 0 else stats.lowStockProducts).toString(),
            labelRes = R.string.vendor_home_kpi_low_stock_label,
            statusKind = if (!empty && stats.lowStockProducts > 0) MsStatusPill.Kind.Rejected else null,
        )
    }

    private fun bindKpiCard(
        containerId: Int,
        iconRes: Int,
        valueText: String,
        labelRes: Int,
        statusKind: MsStatusPill.Kind? = null,
    ) {
        val root = findViewById<View>(containerId) ?: return
        root.findViewById<ImageView>(R.id.msKpiIcon)?.setImageResource(iconRes)
        root.findViewById<TextView>(R.id.msKpiValue)?.text = valueText
        root.findViewById<TextView>(R.id.msKpiLabel)?.setText(labelRes)
        root.findViewById<TextView>(R.id.msKpiMeta)?.visibility = View.GONE
        val delta = root.findViewById<TextView>(R.id.msKpiDelta)
        if (statusKind == null) {
            delta?.visibility = View.GONE
        } else {
            val labelRes2 = when (statusKind) {
                MsStatusPill.Kind.Pending -> R.string.ms_status_pending
                MsStatusPill.Kind.Rejected -> R.string.ms_status_low_stock_short
                else -> R.string.ms_status_active
            }
            MsStatusPill.bind(delta, statusKind, labelRes2)
            delta?.visibility = View.VISIBLE
        }
    }

    // ===== Order feed row =====

    private fun bindOrderRow(view: View, row: Pair<String, AppOrder>) {
        val (_, order) = row
        val title = view.findViewById<TextView>(R.id.msRowTitle)
        val subtitle = view.findViewById<TextView>(R.id.msRowSubtitle)
        val meta = view.findViewById<TextView>(R.id.msRowMeta)
        val status = view.findViewById<TextView>(R.id.msRowStatus)
        val avatar = view.findViewById<ImageView>(R.id.msRowAvatar)

        avatar?.setImageResource(R.drawable.ic_admin_nav_commandes)
        avatar?.setColorFilter(getColor(R.color.ms_accent_gold))

        title?.text = order.displayId
        subtitle?.text = order.uid.takeLast(8)
        meta?.text = formatCurrency(order.total)
        meta?.visibility = View.VISIBLE

        val (kind, labelRes) = when (OrderStatuses.normalize(order.status)) {
            OrderStatuses.PENDING -> MsStatusPill.Kind.Pending to R.string.ms_order_status_pending
            OrderStatuses.CONFIRMED -> MsStatusPill.Kind.Info to R.string.ms_order_status_confirmed
            OrderStatuses.PREPARING -> MsStatusPill.Kind.Info to R.string.ms_order_status_preparing
            OrderStatuses.SHIPPED -> MsStatusPill.Kind.Info to R.string.ms_order_status_shipped
            OrderStatuses.DELIVERED -> MsStatusPill.Kind.Approved to R.string.ms_order_status_delivered
            else -> MsStatusPill.Kind.Archived to R.string.ms_order_status_cancelled
        }
        MsStatusPill.bind(status, kind, labelRes)
        status?.visibility = View.VISIBLE
    }

    // ===== Navigation =====

    private fun openVendors() {
        startActivity(Intent(this, AdminVendorsActivity::class.java))
    }

    private fun openUsers() {
        navigateNoShift(AdminClientsActivity::class.java)
    }

    private fun openOrders() {
        navigateNoShift(AdminCommandesActivity::class.java)
    }

    private fun openProducts() {
        navigateNoShift(AdminProduitsActivity::class.java)
    }

    private fun openAnalytics() {
        navigateNoShift(AdminAnalyticsActivity::class.java)
    }

    private fun openSettings() {
        navigateNoShift(AdminParametresActivity::class.java)
    }

    private fun openOrderDetail(uid: String, order: AppOrder) {
        val orderId = order.id
        if (uid.isBlank() || orderId.isBlank()) return
        startActivity(AdminOrderDetailsActivity.createIntent(this, uid = uid, orderId = orderId))
    }

    private fun formatCurrency(value: Double): String =
        formatDt(value)
}
