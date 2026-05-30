package isim.ia2y.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import isim.ia2y.myapplication.ui.base.BaseListAdapter
import isim.ia2y.myapplication.ui.base.MsStatusPill
import isim.ia2y.myapplication.ui.base.idDiff
import isim.ia2y.myapplication.ui.state.StateRenderer
import isim.ia2y.myapplication.ui.state.UiState
import kotlinx.coroutines.launch

/**
 * Rebuilt admin products screen (Phase 6).
 *
 * Replaces the legacy multi-purpose product list with a clean moderation-first
 * view: filter tabs (All / Pending / Approved / Rejected / Draft / Archived),
 * per-row approve / reject / archive / edit actions, and infinite-scroll
 * pagination backed by [AdminProductsViewModel].
 *
 * [EXTRA_SELLER_MODE] is kept for backward-compat with [AdminProductEditorActivity]
 * and [SellerDashboardActivity] — those callers are gradually migrated to
 * [VendorProductsActivity].
 */
open class AdminProduitsActivity : AppCompatActivity() {

    private val viewModel: AdminProductsViewModel by viewModels()
    private var roleVerified = false

    private val adapter = BaseListAdapter<Product>(
        layoutRes = R.layout.item_admin_product,
        diff = idDiff { it.id },
        bind = { view, product -> bindRow(view, product) },
        onClick = { product -> openEditor(product) },
    )

    private lateinit var listState: StateRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_produits_v2)
        applyInsets()
        setupChips()
        setupList()
        setupBottomNav()

        listState = StateRenderer(
            loadingView = findViewById(R.id.adminProduitsLoading),
            emptyView = findViewById(R.id.adminProduitsEmpty),
            errorView = findViewById(R.id.adminProduitsError),
            dataView = findViewById(R.id.adminProduitsList),
        ).also { renderer ->
            renderer.bindEmpty(
                titleRes = R.string.admin_products_empty_title,
                subtitleRes = R.string.admin_products_empty_subtitle,
            )
            renderer.bindError(
                titleRes = R.string.ms_error_default_title,
                subtitleRes = R.string.admin_products_load_failed,
                onRetry = { viewModel.refresh() },
            )
        }

        observeState()
        observePendingBadge()

        lifecycleScope.launch {
            if (requireAdminOrVendeurRole() == null) return@launch
            roleVerified = true
            viewModel.load()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.PRODUITS)
    }

    // ===== Insets =====

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminProduitsAppBar)) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminProduitsList)) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom + 96)
            insets
        }
    }

    // ===== Chip filter =====

    private fun setupChips() {
        val group = findViewById<com.google.android.material.chip.ChipGroup>(
            R.id.adminProduitsChipGroup
        ) ?: return
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.adminProduitsChipPending -> AdminProductsViewModel.Filter.PENDING
                R.id.adminProduitsChipApproved -> AdminProductsViewModel.Filter.APPROVED
                R.id.adminProduitsChipRejected -> AdminProductsViewModel.Filter.REJECTED
                R.id.adminProduitsChipDraft -> AdminProductsViewModel.Filter.DRAFT
                R.id.adminProduitsChipArchived -> AdminProductsViewModel.Filter.ARCHIVED
                else -> AdminProductsViewModel.Filter.ALL
            }
            viewModel.setFilter(filter)
        }
    }

    // ===== List + pagination =====

    private fun setupList() {
        val rv = findViewById<RecyclerView>(R.id.adminProduitsList) ?: return
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = lm.findLastVisibleItemPosition()
                val total = lm.itemCount
                if (total > 0 && lastVisible >= total - 4 && viewModel.hasMore) {
                    viewModel.loadNextPage()
                }
            }
        })
    }

    // ===== Bottom nav =====

    private fun setupBottomNav() {
        setupAdminBottomNav(AdminNavTab.PRODUITS)
        findViewById<View>(R.id.adminProduitsBell)?.setOnClickListener {
            openNotificationsScreenWithPermissionCheck()
        }
        applyPressFeedback(R.id.adminProduitsBell)
    }

    // ===== State observation =====

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
                    findViewById<ProgressBar>(R.id.adminProduitsLoadMore)?.visibility =
                        if (loading && viewModel.state.value is UiState.Data) View.VISIBLE
                        else View.GONE
                }
            }
        }
    }

    /** Show/hide the pending badge in the title bar. */
    private fun observePendingBadge() {
        lifecycleScope.launch {
            // Fetch count once on load — lightweight aggregate query
            val count = runCatching {
                AdminProductService.countByApprovalStatus(ProductApprovalStatus.PENDING)
            }.getOrDefault(0)
            val badge = findViewById<TextView>(R.id.adminProduitsPendingBadge) ?: return@launch
            if (count > 0) {
                badge.text = getString(R.string.admin_products_pending_badge, count)
                badge.visibility = View.VISIBLE
            } else {
                badge.visibility = View.GONE
            }
        }
    }

    // ===== Row binding =====

    private fun bindRow(view: View, product: Product) {
        // Thumbnail
        view.findViewById<ImageView>(R.id.adminProductThumb)?.loadCatalogImage(
            imageUrl = product.thumbnailUrl ?: product.imageUrl
                ?: product.thumbnailUrls.firstOrNull()
                ?: product.imageUrls.firstOrNull(),
            fallbackRes = R.drawable.placeholder,
            requestedSizePx = 160,
        )

        // Title + seller
        view.findViewById<TextView>(R.id.adminProductTitle)?.text = product.title.ifBlank {
            getString(R.string.admin_products_seller_unknown)
        }
        view.findViewById<TextView>(R.id.adminProductSeller)?.text = product.sellerName.ifBlank {
            getString(R.string.admin_products_seller_unknown)
        }

        // Price
        view.findViewById<TextView>(R.id.adminProductPrice)?.text =
            formatDt(product.effectivePrice)

        // Stock badge
        val stockView = view.findViewById<TextView>(R.id.adminProductStock)
        when {
            product.stock <= 0 -> {
                stockView?.text = getString(R.string.vendor_products_out_of_stock)
                stockView?.background = getDrawable(R.drawable.ms_bg_status_rejected)
                stockView?.setTextColor(getColor(R.color.ms_status_rejected_fg))
                stockView?.visibility = View.VISIBLE
            }
            product.stock <= 5 -> {
                stockView?.text = getString(R.string.vendor_products_low_stock_threshold, product.stock)
                stockView?.background = getDrawable(R.drawable.ms_bg_status_pending)
                stockView?.setTextColor(getColor(R.color.ms_status_pending_fg))
                stockView?.visibility = View.VISIBLE
            }
            else -> {
                stockView?.visibility = View.GONE
            }
        }

        // Approval status pill
        val approvalView = view.findViewById<TextView>(R.id.adminProductApprovalStatus)
        val approvalStatus = ProductApprovalStatus.fromWire(product.approvalStatus)
        val (kind, labelRes) = approvalStatusPill(approvalStatus)
        MsStatusPill.bind(approvalView, kind, labelRes)
        approvalView?.visibility = View.VISIBLE

        // Overflow
        val overflow = view.findViewById<ImageView>(R.id.adminProductOverflow)
        overflow?.setOnClickListener { showRowActions(overflow, product) }
        overflow?.applyPressFeedback()
    }

    private fun approvalStatusPill(status: ProductApprovalStatus): Pair<MsStatusPill.Kind, Int> =
        when (status) {
            ProductApprovalStatus.PENDING -> MsStatusPill.Kind.Pending to R.string.ms_status_pending
            ProductApprovalStatus.APPROVED -> MsStatusPill.Kind.Approved to R.string.ms_status_approved
            ProductApprovalStatus.REJECTED -> MsStatusPill.Kind.Rejected to R.string.ms_status_rejected
            ProductApprovalStatus.DRAFT -> MsStatusPill.Kind.Draft to R.string.ms_status_draft
            ProductApprovalStatus.ARCHIVED -> MsStatusPill.Kind.Archived to R.string.ms_status_archived
        }

    // ===== Row actions =====

    private fun showRowActions(anchor: View, product: Product) {
        val popup = PopupMenu(this, anchor)
        val status = ProductApprovalStatus.fromWire(product.approvalStatus)

        when (status) {
            ProductApprovalStatus.PENDING -> {
                popup.menu.add(0, ACTION_APPROVE, 0, R.string.admin_products_action_approve)
                popup.menu.add(0, ACTION_REJECT, 1, R.string.admin_products_action_reject)
                popup.menu.add(0, ACTION_EDIT, 2, R.string.admin_products_action_edit)
            }
            ProductApprovalStatus.APPROVED -> {
                popup.menu.add(0, ACTION_REJECT, 0, R.string.admin_products_action_reject)
                popup.menu.add(0, ACTION_ARCHIVE, 1, R.string.admin_products_action_archive)
                popup.menu.add(0, ACTION_EDIT, 2, R.string.admin_products_action_edit)
            }
            ProductApprovalStatus.REJECTED, ProductApprovalStatus.DRAFT -> {
                popup.menu.add(0, ACTION_APPROVE, 0, R.string.admin_products_action_approve)
                popup.menu.add(0, ACTION_ARCHIVE, 1, R.string.admin_products_action_archive)
                popup.menu.add(0, ACTION_EDIT, 2, R.string.admin_products_action_edit)
            }
            ProductApprovalStatus.ARCHIVED -> {
                popup.menu.add(0, ACTION_APPROVE, 0, R.string.admin_products_action_approve)
                popup.menu.add(0, ACTION_EDIT, 1, R.string.admin_products_action_edit)
            }
        }
        // Discount available in every state.
        popup.menu.add(0, ACTION_DISCOUNT, 90, R.string.vendor_products_action_discount)
        if (product.hasDiscount) {
            popup.menu.add(0, ACTION_REMOVE_DISCOUNT, 91, R.string.vendor_products_action_remove_discount)
        }

        popup.setOnMenuItemClickListener { item: MenuItem ->
            handleAction(item.itemId, product)
            true
        }
        popup.show()
    }

    private fun handleAction(actionId: Int, product: Product) {
        when (actionId) {
            ACTION_APPROVE -> dispatchAction(
                product = product,
                action = { viewModel.approve(product) },
                successRes = R.string.admin_products_action_success_approved,
            )
            ACTION_REJECT -> confirmThenRun(
                titleRes = R.string.admin_products_reject_confirm_title,
                messageRes = R.string.admin_products_reject_confirm_message,
                product = product,
                action = { viewModel.reject(product) },
                successRes = R.string.admin_products_action_success_rejected,
            )
            ACTION_ARCHIVE -> dispatchAction(
                product = product,
                action = { viewModel.archive(product) },
                successRes = R.string.admin_products_action_success_archived,
            )
            ACTION_EDIT -> openEditor(product)
            ACTION_DISCOUNT -> showDiscountDialog(product)
            ACTION_REMOVE_DISCOUNT -> applyDiscount(product, 0)
        }
    }

    private fun showDiscountDialog(product: Product) {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.vendor_products_discount_dialog_hint)
            if (product.discountPercentClamped > 0) setText(product.discountPercentClamped.toString())
        }
        val padding = resources.getDimensionPixelSize(R.dimen.space_20)
        val container = android.widget.FrameLayout(this).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vendor_products_discount_dialog_title)
            .setMessage(R.string.vendor_products_discount_dialog_message)
            .setView(container)
            .setNegativeButton(R.string.ms_action_cancel, null)
            .setPositiveButton(R.string.ms_action_save) { _, _ ->
                val percent = input.text.toString().trim().toIntOrNull()
                if (percent == null || percent !in 1..90) {
                    showMotionSnackbar(getString(R.string.vendor_products_discount_invalid))
                    return@setPositiveButton
                }
                applyDiscount(product, percent)
            }
            .show()
    }

    private fun applyDiscount(product: Product, percent: Int) {
        lifecycleScope.launch {
            val result = VendorProductLifecycle.setDiscount(product, percent)
            if (result is VendorProductLifecycle.Result.Success) {
                showMotionSnackbar(getString(R.string.vendor_products_action_discount_success))
                viewModel.refresh()
            } else {
                showMotionSnackbar(getString(R.string.vendor_products_action_failed))
            }
        }
    }

    /** For non-destructive actions — run immediately and show snackbar. */
    private fun dispatchAction(
        product: Product,
        action: () -> Unit,
        successRes: Int,
    ) {
        // The ViewModel handles the Firestore write + optimistic list removal.
        action()
        showMotionSnackbar(getString(successRes))
    }

    private fun confirmThenRun(
        titleRes: Int,
        messageRes: Int,
        product: Product,
        action: () -> Unit,
        successRes: Int,
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.ms_action_confirm) { _, _ ->
                dispatchAction(product, action, successRes)
            }
            .setNegativeButton(R.string.ms_action_cancel, null)
            .show()
    }

    // ===== Navigation =====

    private fun openEditor(product: Product) {
        startActivity(
            AdminProductEditorActivity.createIntent(
                context = this,
                productId = product.id,
                sellerMode = false,
            )
        )
    }

    companion object {
        /** Kept for backward compatibility — vendor-mode callers use [VendorProductsActivity]. */
        const val EXTRA_SELLER_MODE = "extra_seller_mode"

        private const val ACTION_APPROVE = 1
        private const val ACTION_REJECT = 2
        private const val ACTION_ARCHIVE = 3
        private const val ACTION_EDIT = 4
        private const val ACTION_DISCOUNT = 5
        private const val ACTION_REMOVE_DISCOUNT = 6
    }
}
