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
 * Phase 1 — rebuilt vendor home dashboard. Replaces [SellerDashboardActivity].
 */
class VendorHomeActivity : AppCompatActivity() {

    private val viewModel: VendorHomeViewModel by viewModels()
    private var roleVerified = false

    private val activityAdapter = BaseListAdapter<AdminService.SellerOrderRow>(
        layoutRes = R.layout.ms_component_list_row,
        diff = idDiff { it.order.uid },
        bind = { view, row -> bindActivityRow(view, row) },
        onClick = { row -> openOrderDetail(row) },
    )

    private lateinit var activityState: StateRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_vendor_home)
        applyInsets()
        setupSectionHeaders()
        setupQuickActions()
        setupActivityList()
        setupBottomNav()

        activityState = StateRenderer(
            loadingView = findViewById(R.id.vendorHomeActivityLoading),
            emptyView = findViewById(R.id.vendorHomeActivityEmpty),
            errorView = findViewById(R.id.vendorHomeActivityError),
            dataView = findViewById(R.id.vendorHomeActivityList),
        ).also { renderer ->
            renderer.bindEmpty(
                titleRes = R.string.vendor_home_activity_empty_title,
                subtitleRes = R.string.vendor_home_activity_empty_subtitle,
            )
            renderer.bindError(
                titleRes = R.string.ms_error_default_title,
                subtitleRes = R.string.vendor_home_load_failed,
                onRetry = { reload() },
            )
        }

        observeState()
        lifecycleScope.launch {
            if (requireAdminOrVendeurRole() == null) return@launch
            roleVerified = true
            reload()
        }
    }

    override fun onResume() {
        super.onResume()
        if (roleVerified) reload()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.vendorHomeAppBar)) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.vendorHomeScroll)) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }
    }

    private fun setupSectionHeaders() {
        findViewById<View>(R.id.vendorHomeOverviewHeader)
            ?.findViewById<TextView>(R.id.msSectionTitle)?.setText(R.string.ms_section_overview)
        findViewById<View>(R.id.vendorHomeActionsHeader)
            ?.findViewById<TextView>(R.id.msSectionTitle)?.setText(R.string.ms_section_quick_actions)
        findViewById<View>(R.id.vendorHomeActivityHeader)
            ?.findViewById<TextView>(R.id.msSectionTitle)?.setText(R.string.ms_section_recent_orders)
    }

    private fun setupQuickActions() {
        bindTile(
            R.id.vendorHomeActionAdd,
            R.drawable.ic_home_add_cart,
            R.string.vendor_home_action_add_title,
            R.string.vendor_home_action_add_subtitle,
        ) { openAddProduct() }
        bindTile(
            R.id.vendorHomeActionProducts,
            R.drawable.ic_admin_nav_produits,
            R.string.vendor_home_action_products_title,
            R.string.vendor_home_action_products_subtitle,
        ) { openProducts() }
        bindTile(
            R.id.vendorHomeActionOrders,
            R.drawable.ic_admin_nav_commandes,
            R.string.vendor_home_action_orders_title,
            R.string.vendor_home_action_orders_subtitle,
        ) { openOrders() }
        bindTile(
            R.id.vendorHomeActionMessages,
            R.drawable.ic_chat_bubble,
            R.string.vendor_home_action_messages_title,
            R.string.vendor_home_action_messages_subtitle,
        ) { openMessages() }
        bindTile(
            R.id.vendorHomeActionAnalytics,
            R.drawable.ic_home_filter,
            R.string.vendor_home_action_analytics_title,
            R.string.vendor_home_action_analytics_subtitle,
        ) { openAnalytics() }
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

    private fun setupActivityList() {
        findViewById<RecyclerView>(R.id.vendorHomeActivityList)?.apply {
            layoutManager = LinearLayoutManager(this@VendorHomeActivity)
            adapter = activityAdapter
            isNestedScrollingEnabled = false
            setHasFixedSize(false)
        }
    }

    private fun setupBottomNav() {
        // Home tab is already on — visual highlight is set by the layout's default tint.
        findViewById<View>(R.id.vendorNavOrders)?.setOnClickListener { openOrders() }
        findViewById<View>(R.id.vendorNavProducts)?.setOnClickListener { openProducts() }
        findViewById<View>(R.id.vendorNavMessages)?.setOnClickListener { openMessages() }
        findViewById<View>(R.id.vendorNavProfile)?.setOnClickListener { openShopProfile() }
        findViewById<View>(R.id.vendorHomeBell)?.setOnClickListener {
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

    private fun reload() {
        val uid = FirebaseAuthManager.currentUser?.uid
        val displayName = FirebaseAuthManager.currentUser?.displayName?.takeIf { it.isNotBlank() }
        viewModel.load(sellerId = uid, shopName = displayName)
    }

    private fun render(state: UiState<VendorHomeViewModel.Data>) {
        // Hero + KPIs always visible; activity feed swaps via StateRenderer.
        when (state) {
            UiState.Loading -> {
                activityState.render(UiState.Loading)
                bindKpis(empty = true)
                bindHero(null)
            }
            UiState.Empty -> {
                activityState.render(UiState.Empty)
                bindKpis(empty = true)
                bindHero(null)
            }
            is UiState.Error -> {
                activityState.render(state)
                bindKpis(empty = true)
                bindHero(null)
            }
            is UiState.Data -> {
                bindHero(state.value)
                bindKpis(empty = false, data = state.value)
                if (state.value.recentRows.isEmpty()) {
                    activityState.render(UiState.Empty)
                    activityAdapter.submitList(emptyList())
                } else {
                    activityState.render(state)
                    activityAdapter.submitList(state.value.recentRows)
                }
            }
        }
    }

    private fun bindHero(data: VendorHomeViewModel.Data?) {
        val greeting = findViewById<TextView>(R.id.vendorHomeHeroGreeting)
        val summary = findViewById<TextView>(R.id.vendorHomeHeroSummary)
        val revenue = findViewById<TextView>(R.id.vendorHomeHeroRevenue)
        val orders = findViewById<TextView>(R.id.vendorHomeHeroOrders)

        val name = data?.shopName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.vendor_home_hero_greeting_default)
        greeting?.text = if (data?.shopName.isNullOrBlank()) {
            getString(R.string.vendor_home_hero_greeting_default)
        } else {
            getString(R.string.vendor_home_hero_greeting, name)
        }

        if (data == null) {
            summary?.setText(R.string.vendor_home_hero_summary_empty)
            revenue?.text = formatCurrency(0.0)
            orders?.text = "0"
            return
        }
        summary?.text = if (data.pendingOrders == 0 && data.lowStockCount == 0) {
            getString(R.string.vendor_home_hero_summary_empty)
        } else {
            getString(
                R.string.vendor_home_hero_summary_pending,
                data.pendingOrders, data.lowStockCount,
            )
        }
        revenue?.text = formatCurrency(data.monthRevenue)
        orders?.text = data.monthOrders.toString()
    }

    private fun bindKpis(empty: Boolean, data: VendorHomeViewModel.Data? = null) {
        bindKpiCard(
            R.id.vendorHomeKpiRevenue,
            iconRes = R.drawable.ic_checkout_wallet,
            valueText = formatCurrency(if (empty) 0.0 else data!!.totalRevenue),
            labelRes = R.string.vendor_home_kpi_revenue_label,
            metaText = if (empty) null else getString(
                R.string.vendor_home_kpi_revenue_meta,
                formatCurrency(data!!.avgBasket),
            ),
        )
        bindKpiCard(
            R.id.vendorHomeKpiPending,
            iconRes = R.drawable.ic_admin_nav_commandes,
            valueText = (if (empty) 0 else data!!.pendingOrders).toString(),
            labelRes = R.string.vendor_home_kpi_pending_label,
            metaText = if (empty) null else getString(
                R.string.vendor_home_kpi_pending_meta,
                data!!.totalOrders,
            ),
            statusKind = if (!empty && data!!.pendingOrders > 0) {
                MsStatusPill.Kind.Pending
            } else null,
        )
        bindKpiCard(
            R.id.vendorHomeKpiLowStock,
            iconRes = R.drawable.ic_feedback_warning,
            valueText = (if (empty) 0 else data!!.lowStockCount).toString(),
            labelRes = R.string.vendor_home_kpi_low_stock_label,
            metaText = if (empty) null else getString(R.string.vendor_home_kpi_low_stock_meta),
            statusKind = if (!empty && data!!.lowStockCount > 0) {
                MsStatusPill.Kind.Rejected
            } else null,
        )
        bindKpiCard(
            R.id.vendorHomeKpiProducts,
            iconRes = R.drawable.ic_admin_nav_produits,
            valueText = (if (empty) 0 else data!!.activeProducts).toString(),
            labelRes = R.string.vendor_home_kpi_products_label,
            metaText = if (empty) null else getString(
                R.string.vendor_home_kpi_products_meta,
                data!!.activeProducts, data.totalProducts,
            ),
        )
    }

    private fun bindKpiCard(
        containerId: Int,
        iconRes: Int,
        valueText: String,
        labelRes: Int,
        metaText: String?,
        statusKind: MsStatusPill.Kind? = null,
    ) {
        val root = findViewById<View>(containerId) ?: return
        root.findViewById<ImageView>(R.id.msKpiIcon)?.setImageResource(iconRes)
        root.findViewById<TextView>(R.id.msKpiValue)?.text = valueText
        root.findViewById<TextView>(R.id.msKpiLabel)?.setText(labelRes)
        val meta = root.findViewById<TextView>(R.id.msKpiMeta)
        if (metaText.isNullOrBlank()) {
            meta?.visibility = View.GONE
        } else {
            meta?.text = metaText
            meta?.visibility = View.VISIBLE
        }
        val delta = root.findViewById<TextView>(R.id.msKpiDelta)
        if (statusKind == null) {
            delta?.visibility = View.GONE
        } else {
            val labelRes2 = when (statusKind) {
                MsStatusPill.Kind.Pending -> R.string.ms_status_pending
                MsStatusPill.Kind.Approved -> R.string.ms_status_approved
                MsStatusPill.Kind.Rejected -> R.string.ms_status_low_stock_short
                MsStatusPill.Kind.Draft -> R.string.ms_status_draft
                MsStatusPill.Kind.Archived -> R.string.ms_status_archived
                MsStatusPill.Kind.Info -> R.string.ms_status_active
            }
            MsStatusPill.bind(delta, statusKind, labelRes2)
            delta?.visibility = View.VISIBLE
        }
    }

    private fun bindActivityRow(view: View, row: AdminService.SellerOrderRow) {
        val title = view.findViewById<TextView>(R.id.msRowTitle)
        val subtitle = view.findViewById<TextView>(R.id.msRowSubtitle)
        val meta = view.findViewById<TextView>(R.id.msRowMeta)
        val status = view.findViewById<TextView>(R.id.msRowStatus)
        val avatar = view.findViewById<ImageView>(R.id.msRowAvatar)

        avatar?.setImageResource(R.drawable.ic_admin_nav_commandes)
        avatar?.setColorFilter(getColor(R.color.ms_accent_gold))

        val orderId = row.order.uid.takeLast(6).uppercase()
        title?.text = "FW-$orderId"
        subtitle?.text = resources.getQuantityString(
            R.plurals.cart_count_chip,
            row.itemCount, row.itemCount,
        )
        meta?.text = formatCurrency(row.sellerTotal)
        meta?.visibility = View.VISIBLE

        val (kind, labelRes) = when (OrderStatuses.normalize(row.order.status)) {
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

    private fun openProducts() {
        startActivity(Intent(this, VendorProductsActivity::class.java))
    }

    private fun openAddProduct() {
        startActivity(
            VendorProductEditorActivity.createIntent(
                context = this,
                productId = null,
            )
        )
    }

    private fun openOrders() {
        navigateNoShift(VendorOrdersActivity::class.java)
    }

    private fun openMessages() {
        navigateNoShift(MessagingInboxActivity::class.java)
    }

    private fun openAnalytics() {
        navigateNoShift(VendorAnalyticsActivity::class.java)
    }

    private fun openShopProfile() {
        navigateNoShift(VendorShopProfileActivity::class.java)
    }

    private fun openOrderDetail(row: AdminService.SellerOrderRow) {
        // Detail screen wired in Phase 3 — fall back to orders list for now.
        openOrders()
    }

    private fun formatCurrency(value: Double): String =
        getString(R.string.vendor_home_currency_dt, formatDt(value))
}
