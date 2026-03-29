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
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.orderDetailsRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<View>(R.id.ivBack)?.setOnClickListener { finishWithMotion(isForward = false) }
        findViewById<View>(R.id.btnOrderSupport)?.setOnClickListener { showSupportDialog() }
        applyPressFeedback(R.id.ivBack, R.id.btnOrderSupport)
        loadOrder()
    }

    private fun loadOrder() {
        val uid = FirebaseAuthManager.currentUser?.uid ?: run {
            showMotionSnackbar(getString(R.string.order_details_login_required))
            finishWithMotion(isForward = false)
            return
        }
        val orderId = intent.getStringExtra(EXTRA_ORDER_ID).orEmpty()
        if (orderId.isBlank()) {
            showMotionSnackbar(getString(R.string.order_details_not_found))
            finishWithMotion(isForward = false)
            return
        }

        lifecycleScope.launch {
            runCatching { FirestoreService.fetchOrder(uid, orderId) }
                .onSuccess { order ->
                    val resolvedOrder = order ?: LocalOrderStore.findById(this@OrderDetailsActivity, orderId)
                    if (resolvedOrder == null) {
                        showMotionSnackbar(getString(R.string.order_details_not_found))
                        finishWithMotion(isForward = false)
                    } else {
                        bindOrder(resolvedOrder)
                    }
                }
                .onFailure {
                    val localOrder = LocalOrderStore.findById(this@OrderDetailsActivity, orderId)
                    if (localOrder != null) {
                        bindOrder(localOrder)
                    } else {
                        showMotionSnackbar(getString(R.string.order_details_load_failed))
                    }
                }
        }
    }

    private fun bindOrder(order: AppOrder) {
        findViewById<TextView>(R.id.tvOrderDetailsId)?.text = order.displayId
        findViewById<TextView>(R.id.tvOrderDetailsStatus)?.text = order.statusLabel(this)
        findViewById<TextView>(R.id.tvOrderDetailsDate)?.text =
            getString(R.string.order_details_created_on, order.formattedDate)
        findViewById<TextView>(R.id.tvOrderDetailsEta)?.text =
            if (order.estimatedDeliveryLabel.isNotBlank()) {
                getString(R.string.order_details_eta, order.estimatedDeliveryLabel)
            } else {
                getString(R.string.order_details_eta_pending)
            }
        val addressText = buildString {
            val snapshot = order.deliveryAddressSnapshot
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
        order.items.forEach { (productId, quantity) ->
            val product = ProductCatalog.byId(productId) ?: return@forEach
            val itemView = layoutInflater.inflate(R.layout.item_confirmation_product, itemsContainer, false)
            itemView.findViewById<ImageView>(R.id.ivConfirmItemImage)
                ?.loadCatalogImage(product.imageUrl, product.imageRes)
            itemView.findViewById<TextView>(R.id.tvConfirmItemName)?.text = product.title
            itemView.findViewById<TextView>(R.id.tvConfirmItemDetails)?.text =
                getString(R.string.order_details_item_meta, quantity, product.subtitle)
            itemView.findViewById<TextView>(R.id.tvConfirmItemPrice)?.text = formatDt(product.price * quantity)
            itemsContainer?.addView(itemView)
        }

        val timelineContainer = findViewById<LinearLayout>(R.id.layoutOrderTimeline)
        timelineContainer?.removeAllViews()
        val formatter = SimpleDateFormat("dd MMM yyyy • HH:mm", Locale.FRANCE)
        order.statusTimeline.sortedBy { it.changedAt }.forEach { entry ->
            val itemView = layoutInflater.inflate(R.layout.item_order_timeline_entry, timelineContainer, false)
            itemView.findViewById<TextView>(R.id.tvTimelineStatus)?.text = orderStatusLabel(this, entry.status)
            itemView.findViewById<TextView>(R.id.tvTimelineTime)?.text = formatter.format(Date(entry.changedAt))
            timelineContainer?.addView(itemView)
        }
    }

    private fun showSupportDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.order_help_title))
            .setMessage(getString(R.string.order_help_message))
            .setPositiveButton(getString(R.string.profile_support_close), null)
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
