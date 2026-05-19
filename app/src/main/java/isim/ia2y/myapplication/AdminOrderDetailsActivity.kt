package isim.ia2y.myapplication

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminOrderDetailsActivity : AppCompatActivity() {
    private val itemsAdapter = OrderDetailItemsAdapter { item -> openProductItem(item) }
    private var uid: String = ""
    private var orderId: String = ""
    private var sellerMode: Boolean = false
    private var currentOrder: AppOrder? = null
    private var currentClientEmail: String = ""
    private var currentClientPhone: String = ""
    private var currentClientName: String = ""
    private var currentMapQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_order_details)
        uid = intent.getStringExtra(EXTRA_UID).orEmpty()
        orderId = intent.getStringExtra(EXTRA_ORDER_ID).orEmpty()
        sellerMode = intent.getBooleanExtra(EXTRA_SELLER_MODE, false)

        setupInsets()
        findViewById<View>(R.id.adminOrderDetailsIvBack)?.setOnClickListener { finish() }
        findViewById<RecyclerView>(R.id.adminOrderDetailsItemsList)?.apply {
            layoutManager = LinearLayoutManager(this@AdminOrderDetailsActivity)
            adapter = itemsAdapter
            isNestedScrollingEnabled = false
        }
        findViewById<MaterialButton>(R.id.adminOrderDetailsStatusButton)?.setOnClickListener {
            showStatusPicker()
        }
        findViewById<View>(R.id.adminOrderDetailsBtnCall)?.setOnClickListener {
            if (currentClientPhone.isNotBlank()) openPhoneDialer(currentClientPhone)
        }
        findViewById<View>(R.id.adminOrderDetailsBtnEmail)?.setOnClickListener {
            if (currentClientEmail.isNotBlank()) {
                openEmail(currentClientEmail, "FatiWeb - ${currentClientName.ifBlank { currentClientEmail }}")
            }
        }
        findViewById<View>(R.id.adminOrderDetailsBtnMap)?.setOnClickListener {
            if (currentMapQuery.isNotBlank()) openInMaps(currentMapQuery)
        }
        applyPressFeedback(R.id.adminOrderDetailsIvBack, R.id.adminOrderDetailsStatusButton)

        lifecycleScope.launch {
            if (requireAdminOrVendeurRole() == null) return@launch
            loadOrder()
        }
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminOrderDetailsAppBar)) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = statusBars.top)
            insets
        }
    }

    private suspend fun loadOrder() {
        showLoading(true)
        runCatching {
            val order = OrderService.fetchOrder(uid, orderId)
                ?: throw IllegalStateException("Order not found")
            val client = if (sellerMode) {
                null
            } else {
                runCatching { AdminService.fetchClientProfileDetails(order.uid) }.getOrNull()
            }
            val vendorId = order.sellerIds.firstOrNull().orEmpty()
            val vendor = vendorId.takeIf { it.isNotBlank() }?.let {
                runCatching { UserService.fetchUserProfile(it) }.getOrNull()
            }
            Triple(order, client, vendor)
        }.onSuccess { (order, client, vendor) ->
            currentOrder = order
            render(order, client, vendor)
            showLoading(false)
        }.onFailure {
            showLoading(false)
            showMotionSnackbar(getString(R.string.admin_order_details_load_error))
        }
    }

    private fun showLoading(show: Boolean) {
        findViewById<ProgressBar>(R.id.adminOrderDetailsProgress)?.visibility = if (show) View.VISIBLE else View.GONE
        findViewById<View>(R.id.adminOrderDetailsContent)?.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun render(
        order: AppOrder,
        client: AdminService.ClientProfileDetails?,
        vendor: FirestoreService.UserProfile?
    ) {
        findViewById<TextView>(R.id.adminOrderDetailsId)?.text = order.displayId
        findViewById<TextView>(R.id.adminOrderDetailsDate)?.text =
            getString(R.string.admin_order_details_created, formatDate(order.createdAtMillis))
        findViewById<TextView>(R.id.adminOrderDetailsStatus)?.text = order.statusLabel(this)
        findViewById<TextView>(R.id.adminOrderDetailsPayment)?.text = order.paymentMethod
        findViewById<TextView>(R.id.adminOrderDetailsSubtotal)?.text = formatDt(order.subtotal)
        findViewById<TextView>(R.id.adminOrderDetailsShipping)?.text = formatDt(order.deliveryFee)
        findViewById<TextView>(R.id.adminOrderDetailsTotal)?.text = formatDt(order.total)

        val address = order.shippingAddress
        val clientName = IdentityResolver.displayName(
            client?.name ?: address?.recipientName,
            client?.email,
            getString(R.string.admin_client_fallback_name)
        )
        currentClientName = clientName
        currentClientEmail = client?.email.orEmpty()
        currentClientPhone = address?.phone?.trim().orEmpty()
            .ifBlank { client?.phone.orEmpty().trim() }
        findViewById<TextView>(R.id.adminOrderDetailsClientName)?.text = clientName
        findViewById<TextView>(R.id.adminOrderDetailsClientMeta)?.text =
            listOf(currentClientEmail, currentClientPhone)
                .filter { it.isNotBlank() }
                .joinToString(" - ")
                .ifBlank { getString(R.string.admin_order_details_unknown) }
        findViewById<ImageView>(R.id.adminOrderDetailsClientAvatar)
            ?.loadAvatarImage(client?.avatarUrl, 180)
        findViewById<View>(R.id.adminOrderDetailsBtnCall)?.visibility =
            if (currentClientPhone.isNotBlank()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.adminOrderDetailsBtnEmail)?.visibility =
            if (currentClientEmail.isNotBlank()) View.VISIBLE else View.GONE

        val firstSeller = order.items.firstOrNull { it.sellerName.isNotBlank() || it.sellerAvatarUrl.isNotBlank() }
        val vendorName = IdentityResolver.displayName(
            vendor?.name ?: firstSeller?.sellerName,
            vendor?.email,
            getString(R.string.seller_orders_unknown_client)
        )
        findViewById<TextView>(R.id.adminOrderDetailsVendorName)?.text = vendorName
        findViewById<TextView>(R.id.adminOrderDetailsVendorMeta)?.text =
            order.sellerIds.filter { it.isNotBlank() }.distinct().joinToString(", ")
                .ifBlank { getString(R.string.admin_order_details_unknown) }
        findViewById<ImageView>(R.id.adminOrderDetailsVendorAvatar)
            ?.loadAvatarImage(IdentityResolver.avatarUrl(vendor?.avatarUrl, firstSeller?.sellerAvatarUrl), 180)

        findViewById<TextView>(R.id.adminOrderDetailsAddress)?.text = buildAddressText(address)
        findViewById<TextView>(R.id.adminOrderDetailsNotes)?.text =
            address?.deliveryNotes?.takeIf { it.isNotBlank() } ?: getString(R.string.admin_order_details_unknown)
        currentMapQuery = buildMapQuery(address)
        findViewById<View>(R.id.adminOrderDetailsBtnMap)?.visibility =
            if (currentMapQuery.isNotBlank()) View.VISIBLE else View.GONE

        tintStatusChip(order.status)
        itemsAdapter.submitList(order.items)
    }

    private fun buildMapQuery(address: DeliveryAddressSnapshot?): String {
        if (address == null) return ""
        return listOf(
            address.addressLine1,
            address.addressLine2,
            address.city,
            address.governorate,
            address.postalCode
        ).filter { !it.isNullOrBlank() }.joinToString(", ")
    }

    private fun openProductItem(item: OrderItem) {
        if (item.productId.isBlank()) return
        navigateToProductDetails(item.productId)
    }

    private fun tintStatusChip(status: String) {
        val normalized = OrderStatuses.normalize(status)
        val chip = findViewById<MaterialCardView>(R.id.adminOrderDetailsStatusCard) ?: return
        val label = findViewById<TextView>(R.id.adminOrderDetailsStatus) ?: return
        val (background, text) = when (normalized) {
            OrderStatuses.DELIVERED -> R.color.status_chip_bg_delivered to R.color.status_chip_text_delivered
            OrderStatuses.PENDING -> R.color.status_chip_bg_pending to R.color.status_chip_text_pending
            OrderStatuses.SHIPPED, OrderStatuses.CONFIRMED, OrderStatuses.PREPARING ->
                R.color.status_chip_bg_shipped to R.color.status_chip_text_shipped
            else -> R.color.status_chip_bg_cancelled to R.color.status_chip_text_cancelled
        }
        chip.setCardBackgroundColor(getColor(background))
        label.setTextColor(getColor(text))
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

    private fun showStatusPicker() {
        val order = currentOrder ?: return
        val statuses = OrderStatuses.supported
        val labels = statuses.map { orderStatusLabel(this, it) }.toTypedArray()
        val currentIndex = statuses.indexOf(OrderStatuses.normalize(order.status)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.admin_order_change_status_title, order.displayId))
            .setSingleChoiceItems(labels, currentIndex) { dialog, which ->
                dialog.dismiss()
                updateStatus(statuses[which])
            }
            .setNegativeButton(getString(R.string.admin_dialog_cancel), null)
            .show()
    }

    private fun updateStatus(newStatus: String) {
        val order = currentOrder ?: return
        val effectiveOrderId = order.id.ifBlank { orderId }
        findViewById<MaterialButton>(R.id.adminOrderDetailsStatusButton)?.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                coroutineScope {
                    val updated = OrderService.updateOrderStatus(order.uid, effectiveOrderId, newStatus)
                    val clientDeferred = async { AdminService.fetchClientProfileDetails(updated.uid) }
                    val vendorDeferred = async {
                        updated.sellerIds.firstOrNull()?.let { UserService.fetchUserProfile(it) }
                    }
                    Triple(updated, clientDeferred.await(), vendorDeferred.await())
                }
            }
                .onSuccess { (updated, client, vendor) ->
                    currentOrder = updated.copy(id = effectiveOrderId)
                    render(currentOrder ?: updated, client, vendor)
                    setResult(Activity.RESULT_OK)
                    showMotionSnackbar(getString(R.string.admin_order_status_updated))
                }
                .onFailure {
                    showMotionSnackbar(getString(R.string.admin_order_status_update_failed))
                }
            findViewById<MaterialButton>(R.id.adminOrderDetailsStatusButton)?.isEnabled = true
        }
    }

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("dd MMM yyyy - HH:mm", Locale.getDefault()).format(Date(millis))

    private class OrderDetailItemsAdapter(
        private val onItemClick: (OrderItem) -> Unit
    ) : ListAdapter<OrderItem, OrderDetailItemsAdapter.ViewHolder>(DiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_admin_order_detail_product, parent, false),
                onItemClick
            )
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        private class ViewHolder(
            itemView: View,
            private val onItemClick: (OrderItem) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {
            fun bind(item: OrderItem) {
                itemView.findViewById<ImageView>(R.id.adminOrderProductImage)
                    ?.loadCatalogImage(item.thumbnailUrl, R.drawable.placeholder, 240)
                itemView.findViewById<TextView>(R.id.adminOrderProductName)?.text =
                    item.name.ifBlank { item.productId }
                itemView.findViewById<TextView>(R.id.adminOrderProductMeta)?.text =
                    itemView.context.getString(
                        R.string.admin_order_item_qty_price,
                        item.quantity,
                        formatDt(item.priceAtPurchase)
                    )
                itemView.findViewById<TextView>(R.id.adminOrderProductTotal)?.text =
                    formatDt(item.priceAtPurchase * item.quantity)
                itemView.findViewById<TextView>(R.id.adminOrderProductSeller)?.text =
                    item.sellerName.ifBlank { item.sellerId.ifBlank { itemView.context.getString(R.string.admin_order_details_unknown) } }
                itemView.isClickable = item.productId.isNotBlank()
                itemView.isFocusable = item.productId.isNotBlank()
                itemView.setOnClickListener {
                    if (item.productId.isNotBlank()) onItemClick(item)
                }
            }
        }

        private class DiffCallback : DiffUtil.ItemCallback<OrderItem>() {
            override fun areItemsTheSame(oldItem: OrderItem, newItem: OrderItem): Boolean =
                oldItem.productId == newItem.productId && oldItem.sellerId == newItem.sellerId

            override fun areContentsTheSame(oldItem: OrderItem, newItem: OrderItem): Boolean =
                oldItem == newItem
        }
    }

    companion object {
        private const val EXTRA_UID = "extra_uid"
        private const val EXTRA_ORDER_ID = "extra_order_id"
        private const val EXTRA_SELLER_MODE = "extra_seller_mode"

        fun createIntent(
            context: Context,
            uid: String,
            orderId: String,
            sellerMode: Boolean = false
        ): Intent = Intent(context, AdminOrderDetailsActivity::class.java).apply {
            putExtra(EXTRA_UID, uid)
            putExtra(EXTRA_ORDER_ID, orderId)
            putExtra(EXTRA_SELLER_MODE, sellerMode)
        }
    }
}
