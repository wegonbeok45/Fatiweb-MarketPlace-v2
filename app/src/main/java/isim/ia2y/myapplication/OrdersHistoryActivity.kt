package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Cette classe organise cette partie de l'app.
class OrdersHistoryActivity : AppCompatActivity() {

    // Cette fonction fait une action de cette partie de l'app.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_orders_history)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<View>(R.id.ivBack)?.setOnClickListener { finishWithMotion(isForward = false) }
        applyPressFeedback(R.id.ivBack)
        loadOrders()
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun loadOrders() {
        val uid = FirebaseAuthManager.currentUser?.uid
        if (uid == null) {
            // Not logged in — show empty state
            showEmpty()
            return
        }

        lifecycleScope.launch {
            val orders = FirestoreService.fetchOrders(uid)
            renderOrders(orders)
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun showEmpty() {
        val empty = findViewById<TextView>(R.id.tvEmptyOrders) ?: return
        val emptyState = findViewById<View>(R.id.layoutEmptyOrdersState) ?: return
        val emptyAnimation = findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.ivEmptyOrdersAnimation)
        val container = findViewById<LinearLayout>(R.id.layoutOrdersContainer) ?: return
        container.removeAllViews()
        emptyState.visibility = View.VISIBLE
        empty.visibility = View.VISIBLE
        emptyAnimation?.playAnimation()
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun renderOrders(orders: List<AppOrder>) {
        val container = findViewById<LinearLayout>(R.id.layoutOrdersContainer) ?: return
        val empty = findViewById<TextView>(R.id.tvEmptyOrders) ?: return
        val emptyState = findViewById<View>(R.id.layoutEmptyOrdersState) ?: return
        val emptyAnimation = findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.ivEmptyOrdersAnimation)
        container.removeAllViews()

        if (orders.isEmpty()) {
            emptyState.visibility = View.VISIBLE
            empty.visibility = View.VISIBLE
            emptyAnimation?.playAnimation()
            return
        }
        emptyState.visibility = View.GONE
        empty.visibility = View.GONE
        emptyAnimation?.pauseAnimation()

        orders.forEachIndexed { index, order ->
            val row = layoutInflater.inflate(R.layout.item_order_history, container, false)

            // Summarise items: "Chechia x1, Bijoux x2, …"
            val itemsSummary = order.items.entries.take(2).joinToString(", ") { (id, qty) ->
                val name = ProductCatalog.byId(id)?.title?.split(" ")?.firstOrNull() ?: id
                "$name x$qty"
            }.let { if (order.items.size > 2) "$it…" else it }

            row.findViewById<TextView>(R.id.tvOrderId)?.text = order.displayId
            row.findViewById<TextView>(R.id.tvOrderDate)?.text = order.formattedDate
            row.findViewById<TextView>(R.id.tvOrderItems)?.text = itemsSummary
            row.findViewById<TextView>(R.id.tvOrderTotal)?.text = formatDt(order.total)
            row.findViewById<TextView>(R.id.tvOrderStatus)?.text = order.statusLabel
            container.addView(row)
            animateListItemEntry(row, index, startDelayMs = 35L)
        }
    }
}

