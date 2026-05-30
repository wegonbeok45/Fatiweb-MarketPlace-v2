package isim.ia2y.myapplication

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
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
import isim.ia2y.myapplication.ui.base.idDiff
import isim.ia2y.myapplication.ui.state.StateRenderer
import isim.ia2y.myapplication.ui.state.UiState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class VendorAnalyticsActivity : AppCompatActivity() {

    private val viewModel: VendorAnalyticsViewModel by viewModels()

    private val topAdapter = BaseListAdapter<VendorAnalyticsViewModel.TopProduct>(
        layoutRes = R.layout.ms_component_list_row,
        diff = idDiff { it.productId },
        bind = { view, p -> bindTopProduct(view, p) },
    )

    private val lowStockAdapter = BaseListAdapter<VendorAnalyticsViewModel.LowStockProduct>(
        layoutRes = R.layout.ms_component_list_row,
        diff = idDiff { it.productId },
        bind = { view, p -> bindLowStock(view, p) },
    )

    private lateinit var topState: StateRenderer
    private lateinit var lowStockState: StateRenderer
    private lateinit var globalState: StateRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_vendor_analytics)
        applyInsets()
        findViewById<View>(R.id.vendorAnalyticsBack)?.setOnClickListener { finish() }

        findViewById<RecyclerView>(R.id.vendorAnalyticsTopProducts)?.apply {
            layoutManager = LinearLayoutManager(this@VendorAnalyticsActivity)
            adapter = topAdapter
            isNestedScrollingEnabled = false
        }
        findViewById<RecyclerView>(R.id.vendorAnalyticsLowStock)?.apply {
            layoutManager = LinearLayoutManager(this@VendorAnalyticsActivity)
            adapter = lowStockAdapter
            isNestedScrollingEnabled = false
        }

        topState = StateRenderer(
            loadingView = null,
            emptyView = findViewById(R.id.vendorAnalyticsTopEmpty),
            errorView = null,
            dataView = findViewById(R.id.vendorAnalyticsTopProducts),
        ).also { it.bindEmpty(R.string.vendor_analytics_section_top_products, R.string.ms_empty_default_subtitle) }

        lowStockState = StateRenderer(
            loadingView = null,
            emptyView = findViewById(R.id.vendorAnalyticsLowStockEmpty),
            errorView = null,
            dataView = findViewById(R.id.vendorAnalyticsLowStock),
        ).also { it.bindEmpty(R.string.vendor_analytics_section_low_stock, R.string.ms_empty_default_subtitle) }

        globalState = StateRenderer(
            loadingView = null,
            emptyView = null,
            errorView = findViewById(R.id.vendorAnalyticsError),
            dataView = findViewById(R.id.vendorAnalyticsContent),
        ).also {
            it.bindError(
                titleRes = R.string.ms_error_default_title,
                subtitleRes = R.string.vendor_analytics_load_failed,
                onRetry = { loadData() },
            )
        }

        observe()
        lifecycleScope.launch {
            if (requireAdminOrVendeurRole() == null) return@launch
            loadData()
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.vendorAnalyticsAppBar)) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
    }

    private fun loadData() {
        viewModel.load(FirebaseAuthManager.currentUser?.uid)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: UiState<VendorAnalyticsViewModel.Data>) {
        when (state) {
            UiState.Loading -> {
                // Show empty placeholders briefly; chart + lists are tolerant.
                bindKpis(null)
                renderChart(emptyList())
                topAdapter.submitList(emptyList())
                lowStockAdapter.submitList(emptyList())
                globalState.render(UiState.Data(Unit))
            }
            UiState.Empty -> globalState.render(UiState.Data(Unit))
            is UiState.Error -> globalState.render(state)
            is UiState.Data -> {
                globalState.render(UiState.Data(Unit))
                bindKpis(state.value)
                renderChart(state.value.dayPoints)
                topAdapter.submitList(state.value.topProducts)
                topState.render(if (state.value.topProducts.isEmpty()) UiState.Empty else UiState.Data(state.value.topProducts))
                lowStockAdapter.submitList(state.value.lowStock)
                lowStockState.render(if (state.value.lowStock.isEmpty()) UiState.Empty else UiState.Data(state.value.lowStock))
            }
        }
    }

    private fun bindKpis(d: VendorAnalyticsViewModel.Data?) {
        bindKpi(
            R.id.vendorAnalyticsKpiWeekRevenue,
            iconRes = R.drawable.ic_checkout_wallet,
            valueText = if (d == null) formatCurrency(0.0) else formatCurrency(d.weekRevenue),
            labelRes = R.string.vendor_analytics_kpi_week_revenue,
        )
        bindKpi(
            R.id.vendorAnalyticsKpiWeekOrders,
            iconRes = R.drawable.ic_admin_nav_commandes,
            valueText = (d?.weekOrders ?: 0).toString(),
            labelRes = R.string.vendor_analytics_kpi_week_orders,
        )
        bindKpi(
            R.id.vendorAnalyticsKpiAvgBasket,
            iconRes = R.drawable.ic_admin_nav_produits,
            valueText = formatCurrency(d?.avgBasket ?: 0.0),
            labelRes = R.string.vendor_analytics_kpi_avg_basket,
        )
        val rate = d?.deliveryRate ?: 0.0
        bindKpi(
            R.id.vendorAnalyticsKpiDeliveryRate,
            iconRes = R.drawable.ic_feedback_check,
            valueText = "${(rate * 100).roundToInt()}%",
            labelRes = R.string.vendor_analytics_kpi_delivery_rate,
        )
    }

    private fun bindKpi(containerId: Int, iconRes: Int, valueText: String, labelRes: Int) {
        val root = findViewById<View>(containerId) ?: return
        root.findViewById<ImageView>(R.id.msKpiIcon)?.setImageResource(iconRes)
        root.findViewById<TextView>(R.id.msKpiValue)?.text = valueText
        root.findViewById<TextView>(R.id.msKpiLabel)?.setText(labelRes)
        root.findViewById<TextView>(R.id.msKpiMeta)?.visibility = View.GONE
        root.findViewById<TextView>(R.id.msKpiDelta)?.visibility = View.GONE
    }

    private fun renderChart(points: List<VendorAnalyticsViewModel.DayPoint>) {
        val bars = findViewById<LinearLayout>(R.id.vendorAnalyticsChartBars) ?: return
        val labels = findViewById<LinearLayout>(R.id.vendorAnalyticsChartLabels) ?: return
        val empty = findViewById<TextView>(R.id.vendorAnalyticsChartEmpty)
        bars.removeAllViews()
        labels.removeAllViews()

        if (points.isEmpty() || points.all { it.orderCount == 0 }) {
            bars.visibility = View.GONE
            labels.visibility = View.GONE
            empty?.visibility = View.VISIBLE
            return
        }
        bars.visibility = View.VISIBLE
        labels.visibility = View.VISIBLE
        empty?.visibility = View.GONE

        val maxValue = points.maxOf { it.orderCount }.coerceAtLeast(1)
        val inflater = LayoutInflater.from(this)
        points.forEach { point ->
            val column = inflater.inflate(R.layout.ms_component_chart_column, bars, false)
            val barFill = column.findViewById<View>(R.id.msChartBarFill)
            val valueLabel = column.findViewById<TextView>(R.id.msChartValueLabel)
            valueLabel.text = if (point.orderCount > 0) point.orderCount.toString() else ""
            // Adjust the bar fill's weight inside the column so its height scales with value.
            val fillWeight = point.orderCount.toFloat() / maxValue.toFloat()
            val lp = barFill.layoutParams as LinearLayout.LayoutParams
            lp.weight = fillWeight.coerceAtLeast(0.04f)
            barFill.layoutParams = lp
            (column.findViewById<View>(R.id.msChartBarSpacer).layoutParams as LinearLayout.LayoutParams)
                .also { it.weight = (1f - fillWeight).coerceAtLeast(0f) }
            bars.addView(column)

            val labelView = TextView(this).apply {
                text = point.dayLabel
                setTextColor(getColor(R.color.ms_text_tertiary))
                textSize = 11f
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            labels.addView(labelView)
        }
    }

    private fun bindTopProduct(view: View, p: VendorAnalyticsViewModel.TopProduct) {
        view.findViewById<TextView>(R.id.msRowTitle)?.text = p.name.ifBlank {
            getString(R.string.vendor_products_empty_all_title)
        }
        view.findViewById<TextView>(R.id.msRowSubtitle)?.text = getString(
            R.string.vendor_analytics_top_product_meta,
            p.quantitySold,
            formatCurrency(p.totalRevenue),
        )
        view.findViewById<TextView>(R.id.msRowMeta)?.visibility = View.GONE
        view.findViewById<TextView>(R.id.msRowStatus)?.visibility = View.GONE
        view.findViewById<ImageView>(R.id.msRowAvatar)?.loadCatalogImage(
            imageUrl = p.thumbnailUrl,
            fallbackRes = R.drawable.placeholder,
            requestedSizePx = 240,
        )
    }

    private fun bindLowStock(view: View, p: VendorAnalyticsViewModel.LowStockProduct) {
        view.findViewById<TextView>(R.id.msRowTitle)?.text = p.name.ifBlank {
            getString(R.string.vendor_products_empty_all_title)
        }
        view.findViewById<TextView>(R.id.msRowSubtitle)?.text = getString(
            R.string.vendor_analytics_low_stock_meta,
            p.stock,
            formatCurrency(p.price),
        )
        view.findViewById<TextView>(R.id.msRowMeta)?.visibility = View.GONE
        view.findViewById<TextView>(R.id.msRowStatus)?.visibility = View.GONE
        view.findViewById<ImageView>(R.id.msRowAvatar)?.loadCatalogImage(
            imageUrl = p.thumbnailUrl,
            fallbackRes = R.drawable.placeholder,
            requestedSizePx = 240,
        )
    }

    private fun formatCurrency(value: Double): String =
        getString(R.string.vendor_home_currency_dt, formatDt(value))
}
