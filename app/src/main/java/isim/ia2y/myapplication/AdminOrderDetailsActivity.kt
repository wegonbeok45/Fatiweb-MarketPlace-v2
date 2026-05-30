package isim.ia2y.myapplication

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import isim.ia2y.myapplication.ui.base.MsStatusPill
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rebuilt admin order details screen.
 *
 * Open class so [VendorOrderDetailActivity] (future) can subclass and hide the
 * vendor card + status-change button that are admin-only in this view.
 *
 * Accepts [EXTRA_ORDER_ID] + [EXTRA_UID] — both required to look up the order
 * in Firestore (orders live under `users/{uid}/orders/{orderId}`).
 * [EXTRA_SELLER_MODE] hides the vendor identity card and the client-profile
 * fetch (vendors should not see other users' private data).
 */
open class AdminOrderDetailsActivity : AppCompatActivity() {

    private val itemsAdapter = OrderDetailItemsAdapter { item -> openProductItem(item) }

    private var orderId: String = ""
    private var uid: String = ""
    protected open val isSellerMode: Boolean
        get() = intent.getBooleanExtra(EXTRA_SELLER_MODE, false)

    private var currentOrder: AppOrder? = null
    private var currentClientEmail: String = ""
    private var currentClientPhone: String = ""
    private var currentClientName: String = ""
    private var currentMapQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_order_details_v2)

        orderId = intent.getStringExtra(EXTRA_ORDER_ID).orEmpty()
        uid     = intent.getStringExtra(EXTRA_UID).orEmpty()

        setupInsets()
        setupTopBar()
        setupItemsList()
        setupActions()

        lifecycleScope.launch {
            if (requireAdminOrVendeurRole() == null) return@launch
            loadOrder()
        }
    }

    // ===== Insets =====

    private fun setupInsets() {
        val baseTopBarHeight = resources.getDimensionPixelSize(R.dimen.ms_top_bar_height)
        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.adminOrderDetailsAppBar)
        ) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            // Top bar has fixed height in XML; without growing it the children
            // (back button + title) get squished into < 56dp on phones with a
            // tall status bar. Grow height by the status-bar inset to keep the
            // visible bar area at the full 56dp.
            v.updateLayoutParams<ViewGroup.LayoutParams> {
                height = baseTopBarHeight + top
            }
            insets
        }
        // Push scrollable content above the system gesture/nav bar.
        val scroll = findViewById<View>(R.id.adminOrderDetailsContent)
        val baseBottom = scroll?.paddingBottom ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.adminOrderDetailsContent)
        ) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.updatePadding(bottom = baseBottom + bottom)
            insets
        }
    }

    // ===== Top bar =====

    private fun setupTopBar() {
        if (!isSellerMode) bindAdminBack(AdminNavTab.COMMANDES)
        findViewById<View>(R.id.adminOrderDetailsIvBack)?.setOnClickListener {
            if (isSellerMode) finishWithMotion() else navigateAdminBack(AdminNavTab.COMMANDES)
        }
        applyPressFeedback(R.id.adminOrderDetailsIvBack)
    }

    // ===== Items RecyclerView =====

    private fun setupItemsList() {
        val rv = findViewById<RecyclerView>(R.id.adminOrderDetailsItemsList) ?: return
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = itemsAdapter
        rv.isNestedScrollingEnabled = false
    }

    // ===== Click actions =====

    private fun setupActions() {
        findViewById<MaterialButton>(R.id.adminOrderDetailsStatusButton)?.setOnClickListener {
            showStatusPicker()
        }
        applyPressFeedback(R.id.adminOrderDetailsStatusButton)

        findViewById<MaterialButton>(R.id.adminOrderDetailsBtnCall)?.setOnClickListener {
            if (currentClientPhone.isNotBlank()) openPhoneDialer(currentClientPhone)
        }
        findViewById<MaterialButton>(R.id.adminOrderDetailsBtnEmail)?.setOnClickListener {
            if (currentClientEmail.isNotBlank()) {
                openEmail(
                    currentClientEmail,
                    "FatiWeb – ${currentClientName.ifBlank { currentClientEmail }}"
                )
            }
        }
        findViewById<MaterialButton>(R.id.adminOrderDetailsBtnMap)?.setOnClickListener {
            if (currentMapQuery.isNotBlank()) openInMaps(currentMapQuery)
        }

        // Vendor card hidden for vendor-mode callers (they shouldn't see other users' data).
        // Status button stays visible — vendors update status as they fulfill the order
        // (confirm → prepare → ship). The picker itself filters allowed transitions.
        if (isSellerMode) {
            findViewById<View>(R.id.adminOrderDetailsVendorCard)?.visibility = View.GONE
        }
    }

    // ===== Load =====

    private suspend fun loadOrder() {
        showLoading(true)
        runCatching {
            coroutineScope {
                val order = OrderService.fetchOrder(uid, orderId)
                    ?: throw IllegalStateException("Order $orderId not found")

                val clientDeferred = if (!isSellerMode) {
                    async { runCatching { AdminService.fetchClientProfileDetails(order.uid) }.getOrNull() }
                } else null

                val vendorId = order.sellerIds.firstOrNull().orEmpty()
                val vendorDeferred = if (vendorId.isNotBlank() && !isSellerMode) {
                    async { runCatching { UserService.fetchUserProfile(vendorId) }.getOrNull() }
                } else null

                Triple(order, clientDeferred?.await(), vendorDeferred?.await())
            }
        }.onSuccess { (order, client, vendor) ->
            currentOrder = order
            render(order, client, vendor)
            showContent()
        }.onFailure {
            showError()
        }
    }

    // ===== Render =====

    private fun render(
        order: AppOrder,
        client: AdminService.ClientProfileDetails?,
        vendor: FirestoreService.UserProfile?
    ) {
        // Top bar title
        findViewById<TextView>(R.id.adminOrderDetailsTitle)?.text = order.displayId

        // Order ID + date
        findViewById<TextView>(R.id.adminOrderDetailsId)?.text = order.displayId
        findViewById<TextView>(R.id.adminOrderDetailsDate)?.text =
            getString(R.string.admin_order_details_created, formatDate(order.createdAtMillis))

        // Status pill
        val statusPill = findViewById<TextView>(R.id.adminOrderDetailsStatusPill)
        val (pillKind, pillLabel) = orderStatusPill(order.status)
        MsStatusPill.bind(statusPill, pillKind, pillLabel)
        statusPill?.visibility = View.VISIBLE

        // Payment
        findViewById<TextView>(R.id.adminOrderDetailsPayment)?.text =
            order.paymentMethod.ifBlank { getString(R.string.admin_order_details_unknown) }

        // Totals
        findViewById<TextView>(R.id.adminOrderDetailsSubtotal)?.text =
            getString(R.string.vendor_home_currency_dt, formatDt(order.subtotal))
        findViewById<TextView>(R.id.adminOrderDetailsShipping)?.text =
            getString(R.string.vendor_home_currency_dt, formatDt(order.deliveryFee))
        findViewById<TextView>(R.id.adminOrderDetailsTotal)?.text =
            getString(R.string.vendor_home_currency_dt, formatDt(order.total))

        // Client section
        val address = order.shippingAddress
        val clientName = IdentityResolver.displayName(
            client?.name ?: address?.recipientName,
            client?.email,
            getString(R.string.admin_client_fallback_name)
        )
        currentClientName  = clientName
        currentClientEmail = client?.email.orEmpty()
        currentClientPhone = address?.phone?.trim().orEmpty()
            .ifBlank { client?.phone.orEmpty().trim() }

        findViewById<TextView>(R.id.adminOrderDetailsClientName)?.text = clientName
        findViewById<TextView>(R.id.adminOrderDetailsClientMeta)?.text =
            listOf(currentClientEmail, currentClientPhone)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
                .ifBlank { getString(R.string.admin_order_details_unknown) }
        findViewById<ImageView>(R.id.adminOrderDetailsClientAvatar)
            ?.loadAvatarImage(client?.avatarUrl, 180)

        findViewById<MaterialButton>(R.id.adminOrderDetailsBtnCall)?.visibility =
            if (currentClientPhone.isNotBlank()) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.adminOrderDetailsBtnEmail)?.visibility =
            if (currentClientEmail.isNotBlank()) View.VISIBLE else View.GONE

        // Vendor section (already hidden in sellerMode by setupActions)
        if (!isSellerMode) {
            val firstSeller = order.items.firstOrNull {
                it.sellerName.isNotBlank() || it.sellerAvatarUrl.isNotBlank()
            }
            val vendorName = IdentityResolver.displayName(
                vendor?.name ?: firstSeller?.sellerName,
                vendor?.email,
                getString(R.string.seller_orders_unknown_client)
            )
            findViewById<TextView>(R.id.adminOrderDetailsVendorName)?.text = vendorName
            findViewById<TextView>(R.id.adminOrderDetailsVendorMeta)?.text =
                order.sellerIds.filter { it.isNotBlank() }.distinct()
                    .joinToString(", ")
                    .ifBlank { getString(R.string.admin_order_details_unknown) }
            findViewById<ImageView>(R.id.adminOrderDetailsVendorAvatar)
                ?.loadAvatarImage(
                    IdentityResolver.avatarUrl(vendor?.avatarUrl, firstSeller?.sellerAvatarUrl),
                    180
                )
        }

        // Address + notes
        findViewById<TextView>(R.id.adminOrderDetailsAddress)?.text =
            buildAddressText(address)
        findViewById<TextView>(R.id.adminOrderDetailsNotes)?.text =
            address?.deliveryNotes?.takeIf { it.isNotBlank() }
                ?: getString(R.string.admin_order_details_unknown)
        currentMapQuery = buildMapQuery(address)
        findViewById<MaterialButton>(R.id.adminOrderDetailsBtnMap)?.visibility =
            if (currentMapQuery.isNotBlank()) View.VISIBLE else View.GONE

        // Items list
        itemsAdapter.submitList(order.items)
    }

    // ===== Status picker =====

    private fun showStatusPicker() {
        val order = currentOrder ?: return
        val statuses = OrderStatuses.supported
        val labels   = statuses.map { orderStatusLabel(this, it) }.toTypedArray()
        val currentIndex = statuses.indexOf(OrderStatuses.normalize(order.status)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.admin_order_change_status_title, order.displayId))
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                dialog.dismiss()
                updateStatus(statuses[which])
            }
            .setNegativeButton(R.string.ms_action_cancel, null)
            .show()
    }

    private fun updateStatus(newStatus: String) {
        val order = currentOrder ?: return
        val effectiveOrderId = order.id.ifBlank { orderId }
        val effectiveUid     = order.uid.ifBlank { uid }
        val button = findViewById<MaterialButton>(R.id.adminOrderDetailsStatusButton)
        button?.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                coroutineScope {
                    val updated = OrderService.updateOrderStatus(effectiveUid, effectiveOrderId, newStatus)
                    val clientDeferred = async {
                        runCatching { AdminService.fetchClientProfileDetails(updated.uid) }.getOrNull()
                    }
                    val vendorDeferred = async {
                        updated.sellerIds.firstOrNull()?.let {
                            runCatching { UserService.fetchUserProfile(it) }.getOrNull()
                        }
                    }
                    Triple(updated, clientDeferred.await(), vendorDeferred.await())
                }
            }.onSuccess { (updated, client, vendor) ->
                currentOrder = updated.copy(id = effectiveOrderId)
                render(currentOrder ?: updated, client, vendor)
                setResult(Activity.RESULT_OK)
                showMotionSnackbar(getString(R.string.admin_order_status_updated))
            }.onFailure {
                showMotionSnackbar(getString(R.string.admin_order_status_update_failed))
            }
            button?.isEnabled = true
        }
    }

    // ===== Helpers =====

    private fun showLoading(show: Boolean) {
        findViewById<View>(R.id.adminOrderDetailsLoading)?.visibility =
            if (show) View.VISIBLE else View.GONE
        if (show) {
            // Ensure content + error are hidden while loading
            findViewById<View>(R.id.adminOrderDetailsContent)?.visibility = View.GONE
            findViewById<View>(R.id.adminOrderDetailsError)?.visibility   = View.GONE
        }
    }

    private fun showContent() {
        findViewById<View>(R.id.adminOrderDetailsLoading)?.visibility = View.GONE
        findViewById<View>(R.id.adminOrderDetailsError)?.visibility   = View.GONE
        findViewById<View>(R.id.adminOrderDetailsContent)?.visibility = View.VISIBLE
    }

    private fun showError() {
        val errorView = findViewById<View>(R.id.adminOrderDetailsError) ?: return
        // Wire retry button if not yet wired
        errorView.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.msErrorRetry
        )?.setOnClickListener {
            lifecycleScope.launch { loadOrder() }
        }
        errorView.findViewById<android.widget.TextView>(R.id.msErrorTitle)
            ?.setText(R.string.ms_error_default_title)
        errorView.findViewById<android.widget.TextView>(R.id.msErrorSubtitle)
            ?.setText(R.string.admin_order_details_load_error)
        findViewById<View>(R.id.adminOrderDetailsLoading)?.visibility = View.GONE
        errorView.visibility = View.VISIBLE
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

    private fun buildAddressText(address: DeliveryAddressSnapshot?): String {
        if (address == null) return getString(R.string.order_details_address_missing)
        return listOf(
            address.recipientName,
            address.summaryLine,
            address.detailsLine
        ).filter { it.isNotBlank() }.joinToString("\n")
            .ifBlank { getString(R.string.order_details_address_missing) }
    }

    private fun buildMapQuery(address: DeliveryAddressSnapshot?): String {
        if (address == null) return ""
        return listOf(
            address.addressLine1,
            address.addressLine2,
            address.city,
            address.governorate,
            address.postalCode
        ).filterNotNull().filter { it.isNotBlank() }.joinToString(", ")
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("dd MMM yyyy – HH:mm", Locale.getDefault()).format(Date(millis))

    private fun openProductItem(item: OrderItem) {
        if (item.productId.isBlank()) return
        navigateToProductDetails(item.productId)
    }

    // ===== Item adapter =====

    private class OrderDetailItemsAdapter(
        private val onItemClick: (OrderItem) -> Unit
    ) : ListAdapter<OrderItem, OrderDetailItemsAdapter.ViewHolder>(DiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
            ViewHolder(
                android.view.LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_order_detail_product, parent, false),
                onItemClick
            )

        override fun onBindViewHolder(holder: ViewHolder, position: Int) =
            holder.bind(getItem(position))

        private class ViewHolder(
            itemView: View,
            private val onItemClick: (OrderItem) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {

            fun bind(item: OrderItem) {
                itemView.findViewById<ImageView>(R.id.orderDetailProductImage)
                    ?.loadCatalogImage(item.thumbnailUrl, R.drawable.placeholder, 240)

                itemView.findViewById<TextView>(R.id.orderDetailProductName)?.text =
                    item.name.ifBlank { item.productId }

                itemView.findViewById<TextView>(R.id.orderDetailProductMeta)?.text =
                    itemView.context.getString(
                        R.string.admin_order_item_qty_price,
                        item.quantity,
                        formatDt(item.priceAtPurchase)
                    )

                itemView.findViewById<TextView>(R.id.orderDetailProductTotal)?.text =
                    formatDt(item.priceAtPurchase * item.quantity)

                // Variant badge: show "Bleu · M" or "Rouge · EU 42" when a variant was selected
                val variantBadge = itemView.findViewById<TextView>(R.id.orderDetailProductVariant)
                val variantText = item.variantLabel.ifBlank {
                    listOf(item.selectedColor, item.selectedSize)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                }
                if (variantBadge != null) {
                    variantBadge.text = variantText
                    variantBadge.visibility = if (variantText.isNotBlank()) View.VISIBLE else View.GONE
                }

                itemView.findViewById<TextView>(R.id.orderDetailProductSeller)?.text =
                    item.sellerName.ifBlank {
                        item.sellerId.ifBlank {
                            itemView.context.getString(R.string.admin_order_details_unknown)
                        }
                    }

                val isClickable = item.productId.isNotBlank()
                itemView.isClickable  = isClickable
                itemView.isFocusable  = isClickable
                itemView.setOnClickListener { if (isClickable) onItemClick(item) }
                itemView.findViewById<ImageView>(R.id.orderDetailProductChevron)?.visibility =
                    if (isClickable) View.VISIBLE else View.GONE
            }
        }

        private class DiffCallback : DiffUtil.ItemCallback<OrderItem>() {
            override fun areItemsTheSame(a: OrderItem, b: OrderItem): Boolean =
                a.productId == b.productId && a.variantId == b.variantId && a.sellerId == b.sellerId
            override fun areContentsTheSame(a: OrderItem, b: OrderItem): Boolean = a == b
        }
    }

    // ===== Companion =====

    companion object {
        const val EXTRA_ORDER_ID    = "extra_order_id"
        const val EXTRA_UID         = "extra_uid"
        const val EXTRA_SELLER_MODE = "extra_seller_mode"

        /**
         * Create an intent for the admin order details screen.
         *
         * @param uid         Buyer's Firebase UID (available on the [AppOrder]).
         *                    Pass empty string only if unavailable — the activity
         *                    will attempt an admin-side uid lookup in that case.
         * @param orderId     Firestore order document ID.
         * @param sellerMode  `true` to hide client profile + vendor card (vendor-facing mode).
         */
        fun createIntent(
            context: Context,
            uid: String,
            orderId: String,
            sellerMode: Boolean = false,
        ): Intent = Intent(context, AdminOrderDetailsActivity::class.java).apply {
            putExtra(EXTRA_UID, uid)
            putExtra(EXTRA_ORDER_ID, orderId)
            putExtra(EXTRA_SELLER_MODE, sellerMode)
        }
    }
}
