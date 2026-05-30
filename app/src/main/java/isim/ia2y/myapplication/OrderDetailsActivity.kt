package isim.ia2y.myapplication

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class OrderDetailsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_order_details)
        val scrollDetails = findViewById<View>(R.id.scrollOrderDetails)
        val orderState = findViewById<View>(R.id.layoutOrderState)
        val scrollBaseBottomPadding = scrollDetails.paddingBottom
        val orderStateBaseBottomPadding = orderState.paddingBottom
        val extraBottomSpacing = resources.getDimensionPixelSize(R.dimen.space_24)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.orderDetailsRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            scrollDetails.updatePadding(
                bottom = scrollBaseBottomPadding + systemBars.bottom + extraBottomSpacing
            )
            orderState.updatePadding(bottom = orderStateBaseBottomPadding + systemBars.bottom)
            insets
        }

        findViewById<View>(R.id.ivBack)?.setOnClickListener { finishWithMotion(isForward = false) }
        findViewById<View>(R.id.btnOrderSupport)?.setOnClickListener { showSupportDialog() }
        findViewById<View>(R.id.btnOrderStateAction)?.setOnClickListener { loadOrder() }
        applyPressFeedback(R.id.ivBack, R.id.btnOrderSupport, R.id.btnReorder)
        renderOrderState(loading = true)
        loadOrder()
    }

    private fun loadOrder() {
        val uid = FirebaseAuthManager.currentUser?.uid ?: run {
            renderOrderState(
                loading = false,
                title = getString(R.string.order_details_state_login_title),
                message = getString(R.string.order_details_login_required),
                actionLabel = getString(R.string.order_details_state_login_action)
            ) {
                startActivity(
                    LoginActivity.createIntent(
                        this,
                        returnToRoute = AUTH_RETURN_ROUTE_ORDERS
                    )
                )
            }
            return
        }
        val orderId = intent.getStringExtra(EXTRA_ORDER_ID).orEmpty()
        if (orderId.isBlank()) {
            renderOrderState(
                loading = false,
                title = getString(R.string.order_details_state_missing_title),
                message = getString(R.string.order_details_not_found),
                actionLabel = getString(R.string.order_details_state_missing_action)
            ) {
                finishWithMotion(isForward = false)
            }
            return
        }

        renderOrderState(loading = true)
        lifecycleScope.launch {
            runCatching { FirestoreService.fetchOrder(uid, orderId) }
                .onSuccess { order ->
                    val resolvedOrder = order ?: LocalOrderStore.findById(this@OrderDetailsActivity, orderId)
                    if (resolvedOrder == null) {
                        renderOrderState(
                            loading = false,
                            title = getString(R.string.order_details_state_missing_title),
                            message = getString(R.string.order_details_not_found),
                            actionLabel = getString(R.string.order_details_state_missing_action)
                        ) {
                            finishWithMotion(isForward = false)
                        }
                    } else {
                        bindOrder(resolvedOrder)
                    }
                }
                .onFailure {
                    val localOrder = LocalOrderStore.findById(this@OrderDetailsActivity, orderId)
                    if (localOrder != null) {
                        bindOrder(localOrder)
                    } else {
                        renderOrderState(
                            loading = false,
                            title = getString(R.string.order_details_state_error_title),
                            message = getString(R.string.order_details_load_failed),
                            actionLabel = getString(R.string.order_details_state_error_action)
                        ) {
                            loadOrder()
                        }
                    }
                }
        }
    }

    private fun bindOrder(order: AppOrder) {
        renderOrderState(loading = false, showContent = true)
        findViewById<TextView>(R.id.tvOrderDetailsId)?.text = order.displayId
        findViewById<TextView>(R.id.tvOrderDetailsStatus)?.text = order.statusLabel(this)
        findViewById<TextView>(R.id.tvOrderDetailsDate)?.text =
            getString(R.string.order_details_created_on, order.formattedDate)
        findViewById<TextView>(R.id.tvOrderDetailsEta)?.text = buildEtaText(order)
        val addressText = buildString {
            val snapshot = order.shippingAddress
            if (snapshot != null) {
                append(snapshot.recipientName)
                if (snapshot.summaryLine.isNotBlank()) append("\n").append(snapshot.summaryLine)
                if (snapshot.detailsLine.isNotBlank()) append("\n").append(snapshot.detailsLine)
                if (snapshot.deliveryNotes.isNotBlank()) {
                    append("\n").append(getString(R.string.order_details_instructions, snapshot.deliveryNotes))
                }
            } else {
                append(getString(R.string.order_details_address_missing))
            }
        }
        findViewById<TextView>(R.id.tvOrderDetailsAddress)?.text = addressText
        findViewById<TextView>(R.id.tvOrderDetailsTotal)?.text =
            getString(R.string.order_details_total, formatDt(order.total))

        val itemsContainer = findViewById<LinearLayout>(R.id.layoutOrderDetailsItems)
        itemsContainer?.removeAllViews()
        order.items.forEach { item ->
            val itemView = layoutInflater.inflate(R.layout.item_confirmation_product, itemsContainer, false)

            itemView.findViewById<ImageView>(R.id.ivConfirmItemImage)
                ?.loadCatalogImage(item.thumbnailUrl, R.drawable.placeholder)
            itemView.findViewById<TextView>(R.id.tvConfirmItemName)?.text = item.name
            itemView.findViewById<TextView>(R.id.tvConfirmItemDetails)?.apply {
                val variantPart = item.variantLabel.ifBlank {
                    listOf(item.selectedColor, item.selectedSize)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                }
                text = if (variantPart.isBlank()) {
                    getString(R.string.order_details_item_qty, item.quantity)
                } else {
                    "${getString(R.string.order_details_item_qty, item.quantity)} · $variantPart"
                }
            }
            itemView.findViewById<TextView>(R.id.tvConfirmItemPrice)?.text =
                formatDt(item.priceAtPurchase * item.quantity)
            itemsContainer?.addView(itemView)
        }

        // Re-order: add each item back to the cart (preserving variant) then navigate to cart
        findViewById<View>(R.id.btnReorder)?.setOnClickListener {
            order.items.forEach { item ->
                if (item.productId.isNotBlank()) {
                    CartStore.add(
                        this,
                        item.productId,
                        item.quantity,
                        variantId = item.variantId.takeIf { it.isNotBlank() }
                    )
                }
            }
            showMotionSnackbar(getString(R.string.order_reorder_added))
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_TAB, MainActivity.Tab.CART.name)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
        }

        val timelineContainer = findViewById<LinearLayout>(R.id.layoutOrderTimeline)
        timelineContainer?.removeAllViews()
        val formatter = SimpleDateFormat("dd MMM yyyy - HH:mm", Locale.FRANCE)
        val sortedEvents = order.trackingEvents.sortedBy { it.changedAt }
        sortedEvents.forEachIndexed { index, entry ->
            val isFirst = index == 0
            val isLast = index == sortedEvents.lastIndex
            val itemView = layoutInflater.inflate(R.layout.item_order_timeline_entry, timelineContainer, false)
            itemView.findViewById<TextView>(R.id.tvTimelineStatus)?.text = orderStatusLabel(this, entry.status)
            itemView.findViewById<TextView>(R.id.tvTimelineTime)?.text = formatter.format(Date(entry.changedAt))
            // Connector lines: invisible (not GONE) so height stays consistent
            itemView.findViewById<View>(R.id.viewTimelineTopLine)?.visibility =
                if (isFirst) View.INVISIBLE else View.VISIBLE
            itemView.findViewById<View>(R.id.viewTimelineBottomLine)?.visibility =
                if (isLast) View.INVISIBLE else View.VISIBLE
            // Highlight the most recent (last) dot with primary colour
            if (isLast) {
                itemView.findViewById<View>(R.id.viewTimelineDot)
                    ?.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    androidx.core.content.ContextCompat.getColor(this, R.color.colorPrimary)
                )
            }
            timelineContainer?.addView(itemView)
        }
    }

    private fun buildEtaText(order: AppOrder): String {
        return when (order.status.lowercase(Locale.getDefault())) {
            "delivered" -> getString(R.string.order_details_eta_delivered)
            "cancelled" -> getString(R.string.order_details_eta_cancelled)
            "shipped" -> getString(
                R.string.order_details_eta,
                SimpleDateFormat("dd MMM", Locale.FRANCE).format(Date(order.createdAtMillis + 2 * 86_400_000L))
            )
            else -> getString(
                R.string.order_details_eta,
                SimpleDateFormat("dd MMM", Locale.FRANCE).format(Date(order.createdAtMillis + 4 * 86_400_000L))
            )
        }
    }

    private fun renderOrderState(
        loading: Boolean,
        showContent: Boolean = false,
        title: String = "",
        message: String = "",
        actionLabel: String = "",
        action: (() -> Unit)? = null
    ) {
        findViewById<View>(R.id.orderDetailsLoading)?.visibility = if (loading) View.VISIBLE else View.GONE
        findViewById<View>(R.id.scrollOrderDetails)?.visibility = if (showContent) View.VISIBLE else View.GONE
        findViewById<View>(R.id.layoutOrderState)?.visibility = if (!loading && !showContent) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.tvOrderStateTitle)?.text = title
        findViewById<TextView>(R.id.tvOrderStateMessage)?.text = message
        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnOrderStateAction)?.apply {
            visibility = if (action == null) View.GONE else View.VISIBLE
            text = actionLabel
            setOnClickListener { action?.invoke() }
        }
    }

    private fun showSupportDialog() {
        val options = arrayOf(
            getString(R.string.support_whatsapp_label),
            getString(R.string.support_email_label)
        )
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.order_help_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openWhatsApp(getString(R.string.support_whatsapp_number))
                    1 -> openEmail(
                        getString(R.string.support_email),
                        "Question commande " + (findViewById<TextView>(R.id.tvOrderDetailsId)?.text ?: "")
                    )
                }
            }
            .setNegativeButton(getString(R.string.admin_dialog_close), null)
            .show()
    }

    companion object {
        private const val EXTRA_ORDER_ID = "extra_order_id"

        fun createIntent(context: Context, orderId: String): Intent {
            return Intent(context, OrderDetailsActivity::class.java)
                .putExtra(EXTRA_ORDER_ID, orderId)
        }
    }
}
