package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
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
 * Rebuilt admin orders screen (Phase 6b).
 *
 * 7-tab filter (All / Pending / Confirmed / Preparing / Shipped / Delivered /
 * Cancelled) with client-side filtering on top of paginated Firestore reads.
 * Each row uses [ms_component_list_row] — no new item layout required.
 */
class AdminCommandesActivity : AppCompatActivity() {

    private val viewModel: AdminOrdersViewModel by viewModels()

    private val adapter = BaseListAdapter<Pair<String, AppOrder>>(
        layoutRes = R.layout.ms_component_list_row,
        diff = idDiff { it.first },
        bind = { view, row -> bindRow(view, row) },
        onClick = { row -> openDetail(row.first, row.second) },
    )

    private lateinit var listState: StateRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_commandes_v2)
        applyInsets()
        setupChips()
        setupList()
        setupBottomNav()

        listState = StateRenderer(
            loadingView = findViewById(R.id.adminCommandesLoading),
            emptyView = findViewById(R.id.adminCommandesEmpty),
            errorView = findViewById(R.id.adminCommandesError),
            dataView = findViewById(R.id.adminCommandesList),
        ).also { renderer ->
            renderer.bindEmpty(
                titleRes = R.string.admin_commandes_empty_title,
                subtitleRes = R.string.admin_commandes_empty_subtitle,
            )
            renderer.bindError(
                titleRes = R.string.ms_error_default_title,
                subtitleRes = R.string.admin_commandes_load_failed,
                onRetry = { viewModel.refresh() },
            )
        }

        observeState()
        viewModel.load()
    }

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.COMMANDES)
    }

    // ===== Insets =====

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminCommandesAppBar)) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminCommandesList)) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom + 96)
            insets
        }
    }

    // ===== Filter chips =====

    private fun setupChips() {
        val group = findViewById<com.google.android.material.chip.ChipGroup>(
            R.id.adminCommandesChipGroup
        ) ?: return
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.adminCommandesChipPending   -> AdminOrdersViewModel.Filter.PENDING
                R.id.adminCommandesChipConfirmed -> AdminOrdersViewModel.Filter.CONFIRMED
                R.id.adminCommandesChipPreparing -> AdminOrdersViewModel.Filter.PREPARING
                R.id.adminCommandesChipShipped   -> AdminOrdersViewModel.Filter.SHIPPED
                R.id.adminCommandesChipDelivered -> AdminOrdersViewModel.Filter.DELIVERED
                R.id.adminCommandesChipCancelled -> AdminOrdersViewModel.Filter.CANCELLED
                else                             -> AdminOrdersViewModel.Filter.ALL
            }
            viewModel.setFilter(filter)
        }
    }

    // ===== List + infinite scroll =====

    private fun setupList() {
        val rv = findViewById<RecyclerView>(R.id.adminCommandesList) ?: return
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= lm.itemCount - 5 && viewModel.hasMore) {
                    viewModel.loadNextPage()
                }
            }
        })
    }

    // ===== Bottom nav =====

    private fun setupBottomNav() {
        setupAdminBottomNav(AdminNavTab.COMMANDES)
        findViewById<View>(R.id.adminCommandesBell)?.setOnClickListener {
            openNotificationsScreenWithPermissionCheck()
        }
        applyPressFeedback(R.id.adminCommandesBell)
    }

    // ===== State =====

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    listState.render(state)
                    when (state) {
                        is UiState.Data -> adapter.submitList(state.value)
                        is UiState.Empty, is UiState.Error -> adapter.submitList(emptyList())
                        else -> Unit
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loadingMore.collect { loading ->
                    findViewById<ProgressBar>(R.id.adminCommandesLoadMore)?.visibility =
                        if (loading && viewModel.state.value is UiState.Data) View.VISIBLE
                        else View.GONE
                }
            }
        }
    }

    // ===== Row binding =====

    private fun bindRow(view: View, row: Pair<String, AppOrder>) {
        val (orderId, order) = row

        view.findViewById<ImageView>(R.id.msRowAvatar)?.apply {
            setImageResource(R.drawable.ic_admin_nav_commandes)
            setColorFilter(getColor(R.color.ms_accent_gold))
        }

        view.findViewById<TextView>(R.id.msRowTitle)?.text =
            "FW-${orderId.takeLast(6).uppercase()}"

        view.findViewById<TextView>(R.id.msRowSubtitle)?.text =
            order.shippingAddress?.recipientName?.takeIf { it.isNotBlank() }
                ?: order.uid.takeLast(8)

        val itemCount = order.items.sumOf { it.quantity }
        view.findViewById<TextView>(R.id.msRowMeta)?.apply {
            text = getString(R.string.admin_commandes_items_count, itemCount)
            visibility = View.VISIBLE
        }

        val statusView = view.findViewById<TextView>(R.id.msRowStatus)
        val (kind, labelRes) = orderStatusPill(order.status)
        MsStatusPill.bind(statusView, kind, labelRes)
        statusView?.visibility = View.VISIBLE

        // Chevron always visible on order rows
        view.findViewById<ImageView>(R.id.msRowChevron)?.visibility = View.VISIBLE
    }

    private fun orderStatusPill(status: String): Pair<MsStatusPill.Kind, Int> =
        when (OrderStatuses.normalize(status)) {
            OrderStatuses.PENDING   -> MsStatusPill.Kind.Pending  to R.string.ms_order_status_pending
            OrderStatuses.CONFIRMED -> MsStatusPill.Kind.Info     to R.string.ms_order_status_confirmed
            OrderStatuses.PREPARING -> MsStatusPill.Kind.Info     to R.string.ms_order_status_preparing
            OrderStatuses.SHIPPED   -> MsStatusPill.Kind.Info     to R.string.ms_order_status_shipped
            OrderStatuses.DELIVERED -> MsStatusPill.Kind.Approved to R.string.ms_order_status_delivered
            else                    -> MsStatusPill.Kind.Archived to R.string.ms_order_status_cancelled
        }

    // ===== Navigation =====

    private fun openDetail(orderId: String, order: AppOrder) {
        startActivity(
            AdminOrderDetailsActivity.createIntent(
                context   = this,
                uid       = order.uid,
                orderId   = orderId,
                sellerMode = false,
            )
        )
    }
}
