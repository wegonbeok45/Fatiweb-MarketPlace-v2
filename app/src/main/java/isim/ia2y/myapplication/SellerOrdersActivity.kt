package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class SellerOrdersActivity : AppCompatActivity() {
    private val rows = mutableListOf<AdminService.SellerOrderRow>()
    private var roleVerified = false
    private val orderDetailsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && roleVerified) loadOrders()
    }
    private val ordersAdapter = SellerOrdersAdapter { row -> openOrderDetails(row) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_seller_orders)
        setupInsets()
        setupActions()
        setupStatCards()

        findViewById<RecyclerView>(R.id.sellerOrdersList)?.apply {
            layoutManager = LinearLayoutManager(this@SellerOrdersActivity)
            adapter = ordersAdapter
            isNestedScrollingEnabled = false
        }
        renderOrders()

        lifecycleScope.launch {
            if (requireAdminOrVendeurRole() == null) return@launch
            roleVerified = true
            if (savedInstanceState == null) {
                revealViewsInOrder(
                    R.id.sellerOrdersTopBar,
                    R.id.sellerOrdersStatsRow,
                    R.id.sellerOrdersHeader,
                    R.id.sellerOrdersCard,
                    startDelayMs = 60L,
                    staggerMs = 48L
                )
            }
            loadOrders()
        }
    }

    override fun onResume() {
        super.onResume()
        if (roleVerified) loadOrders()
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.sellerOrdersAppBar)) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.updatePadding(top = statusBars.top)
            insets
        }
    }

    private fun setupActions() {
        findViewById<View>(R.id.sellerOrdersIvBack)?.setOnClickListener { finish() }
        applyPressFeedback(R.id.sellerOrdersIvBack)
    }

    private fun setupStatCards() {
        statLabel(R.id.sellerOrdersTotalCard)?.text = getString(R.string.admin_orders_total_label)
        statLabel(R.id.sellerOrdersPendingCard)?.text = getString(R.string.seller_dashboard_orders_to_track)
        statLabel(R.id.sellerOrdersRevenueCard)?.text = getString(R.string.seller_dashboard_money_received)
    }

    private fun loadOrders() {
        val uid = FirebaseAuthManager.currentUser?.uid ?: return
        findViewById<ProgressBar>(R.id.sellerOrdersProgress)?.visibility = View.VISIBLE
        lifecycleScope.launch {
            runCatching {
                withTimeout(ORDERS_LOAD_TIMEOUT_MS) {
                    AdminService.fetchSellerOrders(uid, limit = 200)
                }
            }
                .onSuccess { loaded ->
                    findViewById<ProgressBar>(R.id.sellerOrdersProgress)?.visibility = View.GONE
                    rows.clear()
                    rows.addAll(loaded)
                    renderOrders()
                }
                .onFailure {
                    findViewById<ProgressBar>(R.id.sellerOrdersProgress)?.visibility = View.GONE
                    if (rows.isEmpty()) renderOrders()
                    showMotionSnackbar(getString(R.string.admin_orders_load_failed))
                }
        }
    }

    private fun renderOrders() {
        val stats = AdminService.sellerOrderStats(rows)
        statValue(R.id.sellerOrdersTotalCard)?.text = stats.totalOrders.toString()
        statValue(R.id.sellerOrdersPendingCard)?.text = stats.ordersToTrack.toString()
        statValue(R.id.sellerOrdersRevenueCard)?.text = formatDt(stats.totalRevenue)
        findViewById<TextView>(R.id.sellerOrdersEmpty)?.visibility =
            if (rows.isEmpty()) View.VISIBLE else View.GONE
        ordersAdapter.submitList(rows.toList())
    }

    private fun updateOrderStatus(row: AdminService.SellerOrderRow, newStatus: String) {
        lifecycleScope.launch {
            runCatching { FirestoreService.updateOrderStatus(row.uid, row.order.id, newStatus) }
                .onSuccess {
                    val index = rows.indexOfFirst { it.order.id == row.order.id }
                    if (index >= 0) {
                    rows[index] = row.copy(order = row.order.withStatus(newStatus))
                    }
                    renderOrders()
                    showMotionSnackbar(getString(R.string.admin_order_status_updated))
                }
                .onFailure {
                    showMotionSnackbar(getString(R.string.admin_order_status_update_failed))
                }
        }
    }

    private fun showStatusDialog(row: AdminService.SellerOrderRow) {
        val keys = OrderStatuses.supported.toTypedArray()
        val statuses = keys.map { orderStatusLabel(this, it) }.toTypedArray()
        val currentIndex = keys.indexOfFirst { it == OrderStatuses.normalize(row.order.status) }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.admin_order_change_status_title, row.order.displayId))
            .setSingleChoiceItems(statuses, currentIndex) { dialog, which ->
                dialog.dismiss()
                updateOrderStatus(row, keys[which])
            }
            .setNegativeButton(getString(R.string.admin_dialog_cancel), null)
            .show()
    }

    private fun openOrderDetails(row: AdminService.SellerOrderRow) {
        val orderId = row.order.id
        if (orderId.isBlank()) {
            showMotionSnackbar(getString(R.string.admin_orders_load_failed))
            return
        }
        orderDetailsLauncher.launch(
            VendorOrderDetailActivity.createIntent(
                context = this,
                uid = row.uid,
                orderId = orderId,
            )
        )
    }

    private fun showOrderDetails(row: AdminService.SellerOrderRow) {
        val content = buildOrderDetailsContent(row)
        MaterialAlertDialogBuilder(this)
            .setTitle(row.order.displayId)
            .setView(content)
            .setPositiveButton(R.string.admin_order_change_status_cta) { _, _ -> showStatusDialog(row) }
            .setNegativeButton(R.string.admin_dialog_close, null)
            .show()
    }

    private fun buildOrderDetailsContent(row: AdminService.SellerOrderRow): ScrollView {
        val order = row.order
        val address = order.shippingAddress
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(2))
        }

        addDetailLine(root, getString(R.string.seller_order_detail_client), address?.recipientName.orEmpty())
        addDetailLine(root, getString(R.string.seller_order_detail_phone), address?.phone.orEmpty())
        addDetailLine(
            root,
            getString(R.string.seller_order_detail_address),
            listOf(address?.city, address?.governorate, address?.addressLine1)
                .filterNot { it.isNullOrBlank() }
                .joinToString(", ")
        )
        addDetailLine(root, getString(R.string.seller_order_detail_status), order.statusLabel(this))
        addSectionTitle(root, getString(R.string.seller_order_detail_items))
        row.sellerItems.forEach { item -> addItemCard(root, item) }
        addDetailLine(root, getString(R.string.seller_order_detail_seller_total), formatDt(row.sellerTotal))

        return ScrollView(this).apply {
            addView(root)
        }
    }

    private fun addSectionTitle(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.profile_text_primary))
            textSize = 16f
            setPadding(0, dp(12), 0, dp(8))
        })
    }

    private fun addDetailLine(parent: LinearLayout, label: String, value: String) {
        parent.addView(TextView(this).apply {
            text = "$label: ${value.ifBlank { getString(R.string.seller_orders_unknown_client) }}"
            setTextColor(getColor(R.color.profile_text_secondary))
            textSize = 14f
            setPadding(0, dp(3), 0, dp(3))
        })
    }

    private fun addItemCard(parent: LinearLayout, item: OrderItem) {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            radius = dp(8).toFloat()
            strokeWidth = 1
            setStrokeColor(getColor(R.color.colorBorderLight))
            setCardBackgroundColor(getColor(R.color.profile_card_bg))
            cardElevation = 0f
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52))
            scaleType = ImageView.ScaleType.CENTER_CROP
            loadCatalogImage(item.thumbnailUrl, R.drawable.placeholder, dp(96))
        }
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(10)
            }
        }
        textColumn.addView(TextView(this).apply {
            text = item.name.ifBlank { item.productId }
            setTextColor(getColor(R.color.profile_text_primary))
            textSize = 14f
        })
        textColumn.addView(TextView(this).apply {
            text = getString(
                R.string.seller_order_detail_item_meta,
                item.quantity,
                formatDt(item.priceAtPurchase),
                formatDt(item.priceAtPurchase * item.quantity)
            )
            setTextColor(getColor(R.color.profile_text_secondary))
            textSize = 12f
            setPadding(0, dp(3), 0, 0)
        })
        // Variant badge — only when a variant was selected
        val variantText = item.variantLabel.ifBlank {
            listOf(item.selectedColor, item.selectedSize).filter { it.isNotBlank() }.joinToString(" · ")
        }
        if (variantText.isNotBlank()) {
            textColumn.addView(TextView(this).apply {
                text = variantText
                setTextColor(getColor(R.color.ms_text_tertiary))
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, dp(2), 0, 0)
            })
        }
        row.addView(image)
        row.addView(textColumn)
        card.addView(row)
        parent.addView(card)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun statValue(cardId: Int): TextView? =
        findViewById<View>(cardId)?.findViewById(R.id.sellerStatValue)

    private fun statLabel(cardId: Int): TextView? =
        findViewById<View>(cardId)?.findViewById(R.id.sellerStatLabel)

    private companion object {
        private const val ORDERS_LOAD_TIMEOUT_MS = 15_000L
    }

}
