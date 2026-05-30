package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import isim.ia2y.myapplication.ui.base.BaseListAdapter
import isim.ia2y.myapplication.ui.base.MsStatusPill
import isim.ia2y.myapplication.ui.base.idDiff
import isim.ia2y.myapplication.ui.state.StateRenderer
import isim.ia2y.myapplication.ui.state.UiState
import kotlinx.coroutines.launch

/**
 * Phase 3 — vendor-scoped order list with status filter chips.
 * Tap row → [VendorOrderDetailActivity] (seller-mode detail view).
 */
class VendorOrdersActivity : AppCompatActivity() {

    private val viewModel: VendorOrdersViewModel by viewModels()
    private var roleVerified = false

    private val adapter = BaseListAdapter<AdminService.SellerOrderRow>(
        layoutRes = R.layout.item_vendor_order,
        diff = idDiff { it.order.id.ifBlank { it.order.uid + it.order.createdAtMillis } },
        bind = { view, row -> bindRow(view, row) },
        onClick = { row -> openOrderDetail(row) },
    )

    private lateinit var listState: StateRenderer

    private val detailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) viewModel.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_vendor_orders)
        applyInsets()
        setupList()
        setupFilters()
        findViewById<View>(R.id.vendorOrdersBack)?.setOnClickListener { finish() }

        listState = StateRenderer(
            loadingView = findViewById(R.id.vendorOrdersLoading),
            emptyView = findViewById(R.id.vendorOrdersEmpty),
            errorView = findViewById(R.id.vendorOrdersError),
            dataView = findViewById(R.id.vendorOrdersList),
        ).also { renderer ->
            renderer.bindError(
                titleRes = R.string.ms_error_default_title,
                subtitleRes = R.string.vendor_orders_load_failed,
                onRetry = { viewModel.refresh() },
            )
        }

        observeState()
        lifecycleScope.launch {
            if (requireAdminOrVendeurRole() == null) return@launch
            roleVerified = true
            viewModel.setSellerId(FirebaseAuthManager.currentUser?.uid)
        }
    }

    override fun onResume() {
        super.onResume()
        if (roleVerified) viewModel.refresh()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.vendorOrdersAppBar)) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
        val list = findViewById<RecyclerView>(R.id.vendorOrdersList)
        val baseBottom = list?.paddingBottom ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.vendorOrdersRoot)
        ) { _, insets ->
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            list?.updatePadding(bottom = baseBottom + navBottom)
            insets
        }
    }

    private fun setupList() {
        findViewById<RecyclerView>(R.id.vendorOrdersList)?.apply {
            layoutManager = LinearLayoutManager(this@VendorOrdersActivity)
            adapter = this@VendorOrdersActivity.adapter
            setHasFixedSize(false)
        }
    }

    private fun setupFilters() {
        val group = findViewById<ChipGroup>(R.id.vendorOrdersFilterRow) ?: return
        val options = listOf(
            VendorOrdersViewModel.Filter.All to R.string.vendor_orders_filter_all,
            VendorOrdersViewModel.Filter.Pending to R.string.vendor_orders_filter_pending,
            VendorOrdersViewModel.Filter.Confirmed to R.string.vendor_orders_filter_confirmed,
            VendorOrdersViewModel.Filter.Preparing to R.string.vendor_orders_filter_preparing,
            VendorOrdersViewModel.Filter.Shipped to R.string.vendor_orders_filter_shipped,
            VendorOrdersViewModel.Filter.Delivered to R.string.vendor_orders_filter_delivered,
            VendorOrdersViewModel.Filter.Cancelled to R.string.vendor_orders_filter_cancelled,
        )
        group.removeAllViews()
        options.forEachIndexed { index, (filter, labelRes) ->
            val chip = Chip(this).apply {
                text = getString(labelRes)
                isCheckable = true
                isChecked = index == 0
                setChipBackgroundColorResource(R.color.ms_surface_card)
                setTextColor(getColor(R.color.ms_text_primary))
                chipStrokeWidth = resources.getDimension(R.dimen.ms_stroke_hairline)
                setChipStrokeColorResource(R.color.ms_border_default)
                setOnClickListener { viewModel.setFilter(filter) }
            }
            group.addView(chip)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it, viewModel.filter.value) }
            }
        }
    }

    private fun render(
        state: UiState<List<AdminService.SellerOrderRow>>,
        filter: VendorOrdersViewModel.Filter,
    ) {
        when (state) {
            UiState.Loading -> listState.render(state)
            UiState.Empty -> {
                val isFiltered = filter != VendorOrdersViewModel.Filter.All
                listState.bindEmpty(
                    titleRes = if (isFiltered) R.string.vendor_orders_empty_filter_title
                    else R.string.vendor_orders_empty_title,
                    subtitleRes = if (isFiltered) R.string.vendor_orders_empty_filter_subtitle
                    else R.string.vendor_orders_empty_subtitle,
                )
                listState.render(state)
                adapter.submitList(emptyList())
            }
            is UiState.Error -> listState.render(state)
            is UiState.Data -> {
                listState.render(state)
                adapter.submitList(state.value)
            }
        }
    }

    private fun bindRow(view: View, row: AdminService.SellerOrderRow) {
        val orderId = view.findViewById<TextView>(R.id.vendorOrderId)
        val customer = view.findViewById<TextView>(R.id.vendorOrderCustomer)
        val items = view.findViewById<TextView>(R.id.vendorOrderItems)
        val status = view.findViewById<TextView>(R.id.vendorOrderStatus)
        view.findViewById<ImageView>(R.id.vendorOrderOverflow)?.setOnClickListener { anchor ->
            showRowActions(anchor, row)
        }

        val idShort = row.order.id.takeLast(6).uppercase().ifBlank {
            row.order.uid.takeLast(6).uppercase()
        }
        orderId?.text = getString(R.string.vendor_orders_row_id, idShort)
        customer?.text = row.order.shippingAddress?.recipientName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.ms_cd_avatar)
        items?.text = getString(
            R.string.vendor_orders_row_items,
            row.itemCount,
            getString(R.string.vendor_home_currency_dt, formatDt(row.sellerTotal)),
        )

        val (kind, labelRes) = when (OrderStatuses.normalize(row.order.status)) {
            OrderStatuses.PENDING -> MsStatusPill.Kind.Pending to R.string.ms_order_status_pending
            OrderStatuses.CONFIRMED -> MsStatusPill.Kind.Info to R.string.ms_order_status_confirmed
            OrderStatuses.PREPARING -> MsStatusPill.Kind.Info to R.string.ms_order_status_preparing
            OrderStatuses.SHIPPED -> MsStatusPill.Kind.Info to R.string.ms_order_status_shipped
            OrderStatuses.DELIVERED -> MsStatusPill.Kind.Approved to R.string.ms_order_status_delivered
            else -> MsStatusPill.Kind.Archived to R.string.ms_order_status_cancelled
        }
        MsStatusPill.bind(status, kind, labelRes)
    }

    private fun showRowActions(anchor: View, row: AdminService.SellerOrderRow) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_vendor_order_actions, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.vendorOrderActionUpdateStatus -> showStatusPicker(row)
                R.id.vendorOrderActionViewDetail -> openOrderDetail(row)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun showStatusPicker(row: AdminService.SellerOrderRow) {
        val statuses = OrderStatuses.supported
        val labels = statuses.map { orderStatusLabel(this, it) }.toTypedArray()
        val currentIndex = statuses.indexOf(OrderStatuses.normalize(row.order.status))
            .coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.vendor_orders_action_update_status)
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                dialog.dismiss()
                updateStatus(row, statuses[which])
            }
            .setNegativeButton(R.string.ms_action_cancel, null)
            .show()
    }

    private fun updateStatus(row: AdminService.SellerOrderRow, newStatus: String) {
        val orderId = row.order.id
        if (orderId.isBlank()) return
        lifecycleScope.launch {
            runCatching {
                OrderService.updateOrderStatus(row.uid, orderId, newStatus)
            }.onSuccess {
                showMotionSnackbar(getString(R.string.vendor_orders_status_updated))
                viewModel.refresh()
            }.onFailure {
                showMotionSnackbar(getString(R.string.vendor_orders_status_update_failed))
            }
        }
    }

    private fun openOrderDetail(row: AdminService.SellerOrderRow) {
        val orderId = row.order.id
        if (orderId.isBlank()) {
            showMotionSnackbar(getString(R.string.vendor_orders_load_failed))
            return
        }
        detailLauncher.launch(
            VendorOrderDetailActivity.createIntent(
                context = this,
                uid = row.uid,
                orderId = orderId,
            )
        )
    }
}
