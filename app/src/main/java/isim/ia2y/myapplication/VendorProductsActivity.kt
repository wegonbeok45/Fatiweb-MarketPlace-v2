package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
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
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import isim.ia2y.myapplication.ui.base.BaseListAdapter
import isim.ia2y.myapplication.ui.base.MsStatusPill
import isim.ia2y.myapplication.ui.base.idDiff
import isim.ia2y.myapplication.ui.state.StateRenderer
import isim.ia2y.myapplication.ui.state.UiState
import kotlinx.coroutines.launch

/**
 * Phase 2 — vendor-scoped product list. Filter chips by status, FAB to add,
 * tap row to edit. Lifecycle actions (publish/unpublish/duplicate/archive)
 * are wired in Phase 2b alongside the product editor split.
 */
class VendorProductsActivity : AppCompatActivity() {

    private val viewModel: VendorProductsViewModel by viewModels()
    private var roleVerified = false

    private val adapter = BaseListAdapter<Product>(
        layoutRes = R.layout.item_vendor_product,
        diff = idDiff { it.id },
        bind = { view, product -> bindRow(view, product) },
        onClick = { product -> openEditor(product.id) },
    )

    private lateinit var listState: StateRenderer

    private val editorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) viewModel.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_vendor_products)
        applyInsets()
        setupList()
        setupFilters()
        setupFab()
        findViewById<View>(R.id.vendorProductsBack)?.setOnClickListener { finish() }

        listState = StateRenderer(
            loadingView = findViewById(R.id.vendorProductsLoading),
            emptyView = findViewById(R.id.vendorProductsEmpty),
            errorView = findViewById(R.id.vendorProductsError),
            dataView = findViewById(R.id.vendorProductsList),
        ).also { renderer ->
            renderer.bindError(
                titleRes = R.string.ms_error_default_title,
                subtitleRes = R.string.vendor_products_load_failed,
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

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.vendorProductsAppBar)) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
    }

    private fun setupList() {
        findViewById<RecyclerView>(R.id.vendorProductsList)?.apply {
            layoutManager = LinearLayoutManager(this@VendorProductsActivity)
            adapter = this@VendorProductsActivity.adapter
            setHasFixedSize(false)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    val lm = rv.layoutManager as? LinearLayoutManager ?: return
                    val total = lm.itemCount
                    val last = lm.findLastVisibleItemPosition()
                    if (total > 0 && last >= total - 4) viewModel.loadNextPage()
                }
            })
        }
    }

    private fun setupFilters() {
        val group = findViewById<ChipGroup>(R.id.vendorProductsFilterRow) ?: return
        val options = listOf(
            VendorProductsViewModel.Filter.All to R.string.vendor_products_filter_all,
            VendorProductsViewModel.Filter.Published to R.string.vendor_products_filter_published,
            VendorProductsViewModel.Filter.Drafts to R.string.vendor_products_filter_drafts,
            VendorProductsViewModel.Filter.LowStock to R.string.vendor_products_filter_low_stock,
            VendorProductsViewModel.Filter.Pending to R.string.vendor_products_filter_pending,
            VendorProductsViewModel.Filter.Rejected to R.string.vendor_products_filter_rejected,
            VendorProductsViewModel.Filter.Archived to R.string.vendor_products_filter_archived,
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

    private fun setupFab() {
        findViewById<ExtendedFloatingActionButton>(R.id.vendorProductsFab)?.setOnClickListener {
            openEditor(productId = null)
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    render(state, viewModel.filter.value)
                }
            }
        }
    }

    private fun render(state: UiState<List<Product>>, filter: VendorProductsViewModel.Filter) {
        when (state) {
            UiState.Loading -> listState.render(state)
            UiState.Empty -> {
                val isFiltered = filter != VendorProductsViewModel.Filter.All
                listState.bindEmpty(
                    titleRes = if (isFiltered) R.string.vendor_products_empty_filter_title
                    else R.string.vendor_products_empty_all_title,
                    subtitleRes = if (isFiltered) R.string.vendor_products_empty_filter_subtitle
                    else R.string.vendor_products_empty_all_subtitle,
                    ctaRes = if (isFiltered) null else R.string.ms_action_add,
                    onCta = if (isFiltered) null else { -> openEditor(null) },
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

    private fun bindRow(view: View, product: Product) {
        view.findViewById<ImageView>(R.id.vendorProductImage)?.loadCatalogImage(
            imageUrl = product.thumbnailUrl ?: product.imageUrl ?: product.imageUrls.firstOrNull(),
            fallbackRes = R.drawable.placeholder,
            requestedSizePx = 240,
        )
        view.findViewById<TextView>(R.id.vendorProductTitle)?.text = product.title.ifBlank {
            getString(R.string.vendor_products_empty_all_title)
        }
        view.findViewById<TextView>(R.id.vendorProductPrice)?.text =
            getString(R.string.vendor_home_currency_dt, formatDt(product.effectivePrice))

        val stock = view.findViewById<TextView>(R.id.vendorProductStock)
        when {
            product.stock <= 0 -> {
                stock?.text = getString(R.string.vendor_products_out_of_stock)
                stock?.setTextColor(getColor(R.color.ms_status_rejected_fg))
            }
            product.stock <= VendorProductsViewModel.LOW_STOCK_THRESHOLD -> {
                stock?.text = getString(R.string.vendor_products_low_stock_threshold, product.stock)
                stock?.setTextColor(getColor(R.color.ms_status_pending_fg))
            }
            else -> {
                stock?.text = getString(R.string.vendor_products_stock_count, product.stock)
                stock?.setTextColor(getColor(R.color.ms_text_secondary))
            }
        }

        // Status pill (lifecycle: published / draft / archived)
        val statusPill = view.findViewById<TextView>(R.id.vendorProductStatus)
        val (statusKind, statusLabel) = when {
            !product.isActive || product.status == "archived" ->
                MsStatusPill.Kind.Archived to R.string.ms_status_archived
            product.status == "draft" ->
                MsStatusPill.Kind.Draft to R.string.ms_status_draft
            else ->
                MsStatusPill.Kind.Info to R.string.ms_status_published
        }
        MsStatusPill.bind(statusPill, statusKind, statusLabel)

        view.findViewById<View>(R.id.vendorProductOverflow)?.setOnClickListener { anchor ->
            showRowActions(anchor, product)
        }

        // Approval pill — only show if non-default (i.e. pending/rejected/draft on the moderation side)
        val approvalPill = view.findViewById<TextView>(R.id.vendorProductApproval)
        val approval = ProductApprovalStatus.fromWire(product.approvalStatus)
        if (approval == ProductApprovalStatus.APPROVED) {
            approvalPill?.visibility = View.GONE
        } else {
            val (kind, label) = when (approval) {
                ProductApprovalStatus.PENDING -> MsStatusPill.Kind.Pending to R.string.ms_status_pending
                ProductApprovalStatus.REJECTED -> MsStatusPill.Kind.Rejected to R.string.ms_status_rejected
                ProductApprovalStatus.DRAFT -> MsStatusPill.Kind.Draft to R.string.ms_status_draft
                ProductApprovalStatus.ARCHIVED -> MsStatusPill.Kind.Archived to R.string.ms_status_archived
                ProductApprovalStatus.APPROVED -> MsStatusPill.Kind.Approved to R.string.ms_status_approved
            }
            MsStatusPill.bind(approvalPill, kind, label)
            approvalPill?.visibility = View.VISIBLE
        }
    }

    private fun openEditor(productId: String?) {
        editorLauncher.launch(
            VendorProductEditorActivity.createIntent(
                context = this,
                productId = productId,
            )
        )
    }

    private fun showRowActions(anchor: View, product: Product) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_vendor_product_actions, popup.menu)
        // Hide actions that don't make sense for the current state.
        val isPublished = product.isActive && product.status == "published"
        popup.menu.findItem(R.id.vendorProductActionPublish)?.isVisible = !isPublished
        popup.menu.findItem(R.id.vendorProductActionUnpublish)?.isVisible = isPublished
        popup.menu.findItem(R.id.vendorProductActionArchive)?.isVisible = product.status != "archived"
        popup.menu.findItem(R.id.vendorProductActionDiscount)?.isVisible = true
        popup.menu.findItem(R.id.vendorProductActionRemoveDiscount)?.isVisible = product.hasDiscount
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.vendorProductActionPublish -> performAction(product, R.string.vendor_products_action_publish_success) {
                    VendorProductLifecycle.publish(product)
                }
                R.id.vendorProductActionUnpublish -> performAction(product, R.string.vendor_products_action_unpublish_success) {
                    VendorProductLifecycle.unpublish(product)
                }
                R.id.vendorProductActionDiscount -> showDiscountDialog(product)
                R.id.vendorProductActionRemoveDiscount -> performAction(
                    product,
                    R.string.vendor_products_action_discount_success,
                ) { VendorProductLifecycle.setDiscount(product, 0) }
                R.id.vendorProductActionDuplicate -> performAction(product, R.string.vendor_products_action_duplicate_success) {
                    VendorProductLifecycle.duplicate(product)
                }
                R.id.vendorProductActionArchive -> confirmAndRun(
                    titleRes = R.string.vendor_products_archive_confirm_title,
                    messageRes = R.string.vendor_products_archive_confirm_message,
                    confirmLabelRes = R.string.ms_action_archive,
                ) {
                    performAction(product, R.string.vendor_products_action_archive_success) {
                        VendorProductLifecycle.archive(product)
                    }
                }
                R.id.vendorProductActionDelete -> confirmAndRun(
                    titleRes = R.string.vendor_products_delete_confirm_title,
                    messageRes = R.string.vendor_products_delete_confirm_message,
                    confirmLabelRes = R.string.ms_action_delete,
                    destructive = true,
                ) {
                    performAction(product, R.string.vendor_products_action_delete_success) {
                        VendorProductLifecycle.delete(product.id)
                    }
                }
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun performAction(
        product: Product,
        successMessageRes: Int,
        block: suspend () -> VendorProductLifecycle.Result,
    ) {
        lifecycleScope.launch {
            when (val result = block()) {
                is VendorProductLifecycle.Result.Success -> {
                    showMotionSnackbar(getString(successMessageRes))
                    viewModel.refresh()
                }
                is VendorProductLifecycle.Result.Failure -> {
                    showMotionSnackbar(getString(R.string.vendor_products_action_failed))
                }
            }
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
        AlertDialog.Builder(this)
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
                performAction(product, R.string.vendor_products_action_discount_success) {
                    VendorProductLifecycle.setDiscount(product, percent)
                }
            }
            .show()
    }

    private fun confirmAndRun(
        titleRes: Int,
        messageRes: Int,
        confirmLabelRes: Int,
        destructive: Boolean = false,
        onConfirm: () -> Unit,
    ) {
        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setNegativeButton(R.string.ms_action_cancel, null)
            .setPositiveButton(confirmLabelRes) { _, _ -> onConfirm() }
            .show()
    }
}
