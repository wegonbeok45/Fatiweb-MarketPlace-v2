package isim.ia2y.myapplication

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.PopupMenu
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
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import isim.ia2y.myapplication.ui.base.BaseListAdapter
import isim.ia2y.myapplication.ui.base.MsStatusPill
import isim.ia2y.myapplication.ui.base.idDiff
import isim.ia2y.myapplication.ui.state.StateRenderer
import isim.ia2y.myapplication.ui.state.UiState
import kotlinx.coroutines.launch

/**
 * Admin: browse and moderate vendors. Supports approve / reject / suspend /
 * restore actions via a 3-dot overflow per row.
 */
class AdminVendorsActivity : AppCompatActivity() {

    private val viewModel: AdminVendorsViewModel by viewModels()
    private var roleVerified = false

    private val adapter = BaseListAdapter<AdminVendorService.VendorRow>(
        layoutRes = R.layout.item_admin_vendor,
        diff = idDiff { it.uid },
        bind = { view, row -> bindRow(view, row) },
        onClick = { /* detail screen — future */ },
    )

    private lateinit var listState: StateRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_vendors)
        applyInsets()
        setupToolbar()
        setupChips()
        setupList()

        listState = StateRenderer(
            loadingView = findViewById(R.id.adminVendorsLoading),
            emptyView = findViewById(R.id.adminVendorsEmpty),
            errorView = findViewById(R.id.adminVendorsError),
            dataView = findViewById(R.id.adminVendorsList),
        ).also { renderer ->
            renderer.bindEmpty(
                titleRes = R.string.admin_vendors_empty_title,
                subtitleRes = R.string.admin_vendors_empty_subtitle,
            )
            renderer.bindError(
                titleRes = R.string.ms_error_default_title,
                subtitleRes = R.string.admin_vendors_load_failed,
                onRetry = { viewModel.refresh() },
            )
        }

        observeState()
        lifecycleScope.launch {
            if (requireAdminOrVendeurRole() == null) return@launch
            roleVerified = true
            viewModel.load()
        }
    }

    override fun onResume() {
        super.onResume()
        if (roleVerified) viewModel.load()
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminVendorsAppBar)) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
    }

    private fun setupToolbar() {
        bindAdminBack(AdminNavTab.SETTINGS)
        findViewById<View>(R.id.adminVendorsBack)?.setOnClickListener {
            navigateAdminBack(AdminNavTab.SETTINGS)
        }
        applyPressFeedback(R.id.adminVendorsBack)
    }

    private fun setupChips() {
        val group = findViewById<com.google.android.material.chip.ChipGroup>(R.id.adminVendorsChipGroup) ?: return
        group.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.adminVendorsChipPending -> AdminVendorsViewModel.Filter.PENDING
                R.id.adminVendorsChipApproved -> AdminVendorsViewModel.Filter.APPROVED
                R.id.adminVendorsChipSuspended -> AdminVendorsViewModel.Filter.SUSPENDED
                R.id.adminVendorsChipRejected -> AdminVendorsViewModel.Filter.REJECTED
                else -> AdminVendorsViewModel.Filter.ALL
            }
            viewModel.setFilter(filter)
        }
    }

    private fun setupList() {
        findViewById<RecyclerView>(R.id.adminVendorsList)?.apply {
            layoutManager = LinearLayoutManager(this@AdminVendorsActivity)
            this.adapter = this@AdminVendorsActivity.adapter
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    listState.render(state)
                    if (state is UiState.Data) {
                        adapter.submitList(state.value)
                    } else if (state is UiState.Empty || state is UiState.Error) {
                        adapter.submitList(emptyList())
                    }
                }
            }
        }
    }

    // ===== Row binding =====

    private fun bindRow(view: View, row: AdminVendorService.VendorRow) {
        val shopName = view.findViewById<TextView>(R.id.adminVendorShopName)
        val email = view.findViewById<TextView>(R.id.adminVendorEmail)
        val status = view.findViewById<TextView>(R.id.adminVendorStatus)
        val overflow = view.findViewById<ImageView>(R.id.adminVendorOverflow)
        val avatar = view.findViewById<ImageView>(R.id.adminVendorAvatar)

        shopName?.text = row.shopName.ifBlank {
            row.name.ifBlank { getString(R.string.admin_vendors_row_shop_fallback) }
        }
        email?.text = listOf(row.email, row.phone)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .ifBlank { row.uid.takeLast(8) }

        // Status pill
        val (kind, labelRes) = vendorStatusPill(row.status)
        MsStatusPill.bind(status, kind, labelRes)
        status?.visibility = View.VISIBLE

        // Avatar tint by status
        val tintColor = when (row.status) {
            VendorStatus.APPROVED -> getColor(R.color.ms_accent_gold)
            VendorStatus.PENDING -> getColor(R.color.ms_status_pending_fg)
            VendorStatus.SUSPENDED -> getColor(R.color.ms_status_rejected_fg)
            VendorStatus.REJECTED -> getColor(R.color.ms_text_tertiary)
        }
        avatar?.setColorFilter(tintColor)

        overflow?.setOnClickListener { showRowActions(overflow, row) }
        overflow?.applyPressFeedback()
    }

    private fun vendorStatusPill(status: VendorStatus): Pair<MsStatusPill.Kind, Int> = when (status) {
        VendorStatus.PENDING -> MsStatusPill.Kind.Pending to R.string.ms_status_pending
        VendorStatus.APPROVED -> MsStatusPill.Kind.Approved to R.string.ms_status_approved
        VendorStatus.SUSPENDED -> MsStatusPill.Kind.Rejected to R.string.admin_vendors_filter_suspended
        VendorStatus.REJECTED -> MsStatusPill.Kind.Archived to R.string.admin_vendors_action_reject
    }

    // ===== Actions overflow =====

    private fun showRowActions(anchor: View, row: AdminVendorService.VendorRow) {
        val popup = PopupMenu(this, anchor)
        when (row.status) {
            VendorStatus.PENDING -> {
                popup.menu.add(0, ACTION_APPROVE, 0, R.string.admin_vendors_action_approve)
                popup.menu.add(0, ACTION_REJECT, 1, R.string.admin_vendors_action_reject)
            }
            VendorStatus.APPROVED -> {
                popup.menu.add(0, ACTION_SUSPEND, 0, R.string.admin_vendors_action_suspend)
            }
            VendorStatus.SUSPENDED -> {
                popup.menu.add(0, ACTION_APPROVE, 0, R.string.admin_vendors_action_approve)
                popup.menu.add(0, ACTION_RESTORE, 1, R.string.admin_vendors_action_restore)
            }
            VendorStatus.REJECTED -> {
                popup.menu.add(0, ACTION_RESTORE, 0, R.string.admin_vendors_action_restore)
            }
        }
        popup.setOnMenuItemClickListener { item: MenuItem ->
            handleAction(item.itemId, row)
            true
        }
        popup.show()
    }

    private fun handleAction(actionId: Int, row: AdminVendorService.VendorRow) {
        when (actionId) {
            ACTION_APPROVE -> confirmAndRun(
                titleRes = null,
                messageRes = null,
                doAction = { AdminVendorService.approve(row.uid) },
                successRes = R.string.admin_vendors_action_success_approved,
            )
            ACTION_REJECT -> confirmDestructive(
                titleRes = R.string.admin_vendors_reject_confirm_title,
                messageRes = R.string.admin_vendors_reject_confirm_message,
                doAction = { AdminVendorService.reject(row.uid) },
                successRes = R.string.admin_vendors_action_success_rejected,
            )
            ACTION_SUSPEND -> confirmDestructive(
                titleRes = R.string.admin_vendors_suspend_confirm_title,
                messageRes = R.string.admin_vendors_suspend_confirm_message,
                doAction = { AdminVendorService.suspend(row.uid) },
                successRes = R.string.admin_vendors_action_success_suspended,
            )
            ACTION_RESTORE -> confirmAndRun(
                titleRes = null,
                messageRes = null,
                doAction = { AdminVendorService.restoreToPending(row.uid) },
                successRes = R.string.admin_vendors_action_success_restored,
            )
        }
    }

    /** Run an action immediately (no destructive confirm needed). */
    private fun confirmAndRun(
        titleRes: Int?,
        messageRes: Int?,
        doAction: suspend () -> Unit,
        successRes: Int,
    ) {
        lifecycleScope.launch {
            runCatching { doAction() }
                .onSuccess {
                    showMotionSnackbar(getString(successRes))
                    viewModel.reload()
                }
                .onFailure {
                    showMotionSnackbar(getString(R.string.admin_vendors_action_failed))
                }
        }
    }

    /** Show a destructive confirmation dialog before running action. */
    private fun confirmDestructive(
        titleRes: Int,
        messageRes: Int,
        doAction: suspend () -> Unit,
        successRes: Int,
    ) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(messageRes)
            .setPositiveButton(R.string.ms_action_confirm) { _, _ ->
                confirmAndRun(
                    titleRes = null,
                    messageRes = null,
                    doAction = doAction,
                    successRes = successRes,
                )
            }
            .setNegativeButton(R.string.ms_action_cancel, null)
            .show()
    }

    companion object {
        private const val ACTION_APPROVE = 1
        private const val ACTION_REJECT = 2
        private const val ACTION_SUSPEND = 3
        private const val ACTION_RESTORE = 4
    }
}
