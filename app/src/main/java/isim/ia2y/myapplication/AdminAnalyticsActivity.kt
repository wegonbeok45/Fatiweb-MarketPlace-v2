package isim.ia2y.myapplication

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import isim.ia2y.myapplication.ui.state.StateRenderer
import isim.ia2y.myapplication.ui.state.UiState
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class AdminAnalyticsActivity : AppCompatActivity() {

    private val viewModel: AdminAnalyticsViewModel by viewModels()
    private lateinit var screenState: StateRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_analytics)

        applyInsets()
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.ANALYTICS)

        screenState = StateRenderer(
            loadingView = findViewById(R.id.adminAnalyticsLoading),
            emptyView = findViewById(R.id.adminAnalyticsEmpty),
            errorView = findViewById(R.id.adminAnalyticsError),
            dataView = findViewById(R.id.adminAnalyticsContent),
        ).also { renderer ->
            renderer.bindEmpty(
                titleRes = R.string.admin_analytics_empty_title,
                subtitleRes = R.string.admin_analytics_empty_subtitle,
            )
            renderer.bindError(
                titleRes = R.string.ms_error_default_title,
                subtitleRes = R.string.admin_analytics_load_failed,
                onRetry = { viewModel.load() },
            )
            findViewById<View>(R.id.adminAnalyticsError)
                ?.findViewById<com.google.android.material.button.MaterialButton>(R.id.msErrorRetry)
                ?.setText(R.string.ms_action_retry)
        }

        observe()
        lifecycleScope.launch {
            if (!requireAdminRole()) return@launch
            viewModel.load()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.ANALYTICS)
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminAnalyticsAppBar)) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminAnalyticsFrame)) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom + 96)
            insets
        }
    }

    private fun setupTopBar() {
        findViewById<View>(R.id.adminAnalyticsBell)?.setOnClickListener {
            openNotificationsScreenWithPermissionCheck()
        }
        applyPressFeedback(R.id.adminAnalyticsBell)
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
    }

    private fun render(state: UiState<AdminService.AnalyticsSnapshot>) {
        screenState.render(state)
        if (state is UiState.Data) {
            bindKpis(state.value)
            renderRevenueChart(state.value.revenuePoints)
            renderFunnel(state.value.funnel)
            renderRankings(
                containerId = R.id.adminAnalyticsTopProductsList,
                rows = state.value.topProducts,
                emptyText = getString(R.string.admin_analytics_no_top_products),
                meta = { row -> getString(R.string.admin_analytics_product_rank_meta, row.count, formatCurrency(row.revenue)) },
            )
            renderRankings(
                containerId = R.id.adminAnalyticsTopVendorsList,
                rows = state.value.topVendors,
                emptyText = getString(R.string.admin_analytics_no_top_vendors),
                meta = { row -> getString(R.string.admin_analytics_vendor_rank_meta, row.count, formatCurrency(row.revenue)) },
            )
        }
    }

    private fun bindKpis(snapshot: AdminService.AnalyticsSnapshot) {
        bindKpi(
            R.id.adminAnalyticsKpiRevenue,
            iconRes = R.drawable.ic_checkout_wallet,
            valueText = formatCurrency(snapshot.totalRevenue),
            labelRes = R.string.admin_analytics_kpi_revenue,
        )
        bindKpi(
            R.id.adminAnalyticsKpiOrders,
            iconRes = R.drawable.ic_admin_nav_commandes,
            valueText = snapshot.totalOrders.toString(),
            labelRes = R.string.admin_analytics_kpi_orders,
        )
        bindKpi(
            R.id.adminAnalyticsKpiAverage,
            iconRes = R.drawable.ic_admin_nav_produits,
            valueText = formatCurrency(snapshot.averageOrderValue),
            labelRes = R.string.admin_analytics_kpi_average,
        )
        bindKpi(
            R.id.adminAnalyticsKpiDelivered,
            iconRes = R.drawable.ic_feedback_check,
            valueText = "${(snapshot.deliveredRate * 100).roundToInt()}%",
            labelRes = R.string.admin_analytics_kpi_delivered,
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

    private fun renderRevenueChart(points: List<AdminService.AnalyticsDayPoint>) {
        val bars = findViewById<LinearLayout>(R.id.adminAnalyticsChartBars) ?: return
        val labels = findViewById<LinearLayout>(R.id.adminAnalyticsChartLabels) ?: return
        val empty = findViewById<TextView>(R.id.adminAnalyticsChartEmpty)
        bars.removeAllViews()
        labels.removeAllViews()

        if (points.isEmpty() || points.all { it.revenue <= 0.0 }) {
            bars.visibility = View.GONE
            labels.visibility = View.GONE
            empty?.visibility = View.VISIBLE
            return
        }

        bars.visibility = View.VISIBLE
        labels.visibility = View.VISIBLE
        empty?.visibility = View.GONE

        val maxRevenue = points.maxOf { it.revenue }.coerceAtLeast(1.0)
        val inflater = LayoutInflater.from(this)
        points.forEach { point ->
            val column = inflater.inflate(R.layout.ms_component_chart_column, bars, false)
            val ratio = (point.revenue / maxRevenue).toFloat()
            val fillWeight = if (point.revenue > 0.0) ratio.coerceAtLeast(0.04f) else 0f
            val fill = column.findViewById<View>(R.id.msChartBarFill)
            val spacer = column.findViewById<View>(R.id.msChartBarSpacer)
            (fill.layoutParams as LinearLayout.LayoutParams).also { it.weight = fillWeight }
            (spacer.layoutParams as LinearLayout.LayoutParams).also {
                it.weight = (1f - fillWeight).coerceAtLeast(0f)
            }
            column.findViewById<TextView>(R.id.msChartValueLabel)?.text =
                if (point.revenue > 0.0) formatCompactDt(point.revenue) else ""
            bars.addView(column)

            labels.addView(TextView(this).apply {
                text = point.dayLabel
                setTextColor(getColor(R.color.ms_text_tertiary))
                textSize = 11f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
    }

    private fun renderFunnel(rows: List<AdminService.AnalyticsStatusCount>) {
        val container = findViewById<LinearLayout>(R.id.adminAnalyticsFunnelList) ?: return
        container.removeAllViews()
        val maxCount = rows.maxOfOrNull { it.count }?.coerceAtLeast(1) ?: 1
        rows.forEach { row ->
            container.addView(createFunnelRow(row, maxCount))
        }
    }

    private fun createFunnelRow(row: AdminService.AnalyticsStatusCount, maxCount: Int): View {
        val vertical = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, resources.getDimensionPixelSize(R.dimen.space_8), 0, resources.getDimensionPixelSize(R.dimen.space_8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            setTextAppearance(R.style.Ms_Text_Label)
            text = statusLabel(row.status)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val count = TextView(this).apply {
            setTextAppearance(R.style.Ms_Text_Label)
            setTextColor(getColor(R.color.ms_text_secondary))
            text = row.count.toString()
        }
        header.addView(label)
        header.addView(count)
        vertical.addView(header)

        val ratio = row.count.toFloat() / maxCount.toFloat()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 1f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.space_8),
            ).apply {
                topMargin = resources.getDimensionPixelSize(R.dimen.space_8)
            }
        }
        bar.addView(View(this).apply {
            background = ContextCompat.getDrawable(this@AdminAnalyticsActivity, R.drawable.ms_bg_chart_bar)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, ratio.coerceAtLeast(0f))
        })
        bar.addView(View(this).apply {
            background = ContextCompat.getDrawable(this@AdminAnalyticsActivity, R.drawable.ms_bg_skeleton_block)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (1f - ratio).coerceAtLeast(0f))
        })
        vertical.addView(bar)
        return vertical
    }

    private fun renderRankings(
        containerId: Int,
        rows: List<AdminService.AnalyticsRankRow>,
        emptyText: String,
        meta: (AdminService.AnalyticsRankRow) -> String,
    ) {
        val container = findViewById<LinearLayout>(containerId) ?: return
        container.removeAllViews()
        if (rows.isEmpty()) {
            container.addView(TextView(this).apply {
                setTextAppearance(R.style.Ms_Text_Body)
                setTextColor(getColor(R.color.ms_text_secondary))
                gravity = Gravity.CENTER
                text = emptyText
                setPadding(0, resources.getDimensionPixelSize(R.dimen.space_24), 0, resources.getDimensionPixelSize(R.dimen.space_24))
            })
            return
        }
        rows.forEachIndexed { index, row ->
            container.addView(createRankRow(index + 1, row, meta(row)))
        }
    }

    private fun createRankRow(rank: Int, row: AdminService.AnalyticsRankRow, meta: String): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, resources.getDimensionPixelSize(R.dimen.space_12), 0, resources.getDimensionPixelSize(R.dimen.space_12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val rankView = TextView(this).apply {
            setTextAppearance(R.style.Ms_Text_Label)
            setTextColor(getColor(R.color.ms_status_info_fg))
            background = ContextCompat.getDrawable(this@AdminAnalyticsActivity, R.drawable.ms_bg_status_info)
            gravity = Gravity.CENTER
            text = "#$rank"
            layoutParams = LinearLayout.LayoutParams(
                resources.getDimensionPixelSize(R.dimen.ms_min_touch),
                resources.getDimensionPixelSize(R.dimen.ms_min_touch),
            )
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.space_12)
                marginEnd = resources.getDimensionPixelSize(R.dimen.space_12)
            }
        }
        textCol.addView(TextView(this).apply {
            setTextAppearance(R.style.Ms_Text_BodyStrong)
            text = row.name.ifBlank { row.id }
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        textCol.addView(TextView(this).apply {
            setTextAppearance(R.style.Ms_Text_Caption)
            setTextColor(getColor(R.color.ms_text_secondary))
            text = meta
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        val value = TextView(this).apply {
            setTextAppearance(R.style.Ms_Text_Label)
            setTextColor(getColor(R.color.ms_accent_gold))
            text = formatCurrency(row.revenue)
            gravity = Gravity.END
        }
        outer.addView(rankView)
        outer.addView(textCol)
        outer.addView(value)
        return outer
    }

    private fun statusLabel(status: String): String = when (OrderStatuses.normalize(status)) {
        OrderStatuses.PENDING -> getString(R.string.ms_order_status_pending)
        OrderStatuses.CONFIRMED -> getString(R.string.ms_order_status_confirmed)
        OrderStatuses.PREPARING -> getString(R.string.ms_order_status_preparing)
        OrderStatuses.SHIPPED -> getString(R.string.ms_order_status_shipped)
        OrderStatuses.DELIVERED -> getString(R.string.ms_order_status_delivered)
        else -> getString(R.string.ms_order_status_cancelled)
    }

    private fun formatCurrency(value: Double): String =
        getString(R.string.vendor_home_currency_dt, formatDt(value))

    private fun formatCompactDt(value: Double): String {
        return if (value >= 1000.0) {
            getString(R.string.admin_analytics_compact_kdt, String.format(Locale.US, "%.1f", value / 1000.0))
        } else {
            formatDt(value)
        }
    }
}
