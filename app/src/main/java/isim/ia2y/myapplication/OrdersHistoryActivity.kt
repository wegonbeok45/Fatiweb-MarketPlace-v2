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

class OrdersHistoryActivity : AppCompatActivity() {

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

    private fun showEmpty() {
        val empty = findViewById<TextView>(R.id.tvEmptyOrders) ?: return
        val container = findViewById<LinearLayout>(R.id.layoutOrdersContainer) ?: return
        container.removeAllViews()
        empty.visibility = View.VISIBLE
    }

    private fun renderOrders(orders: List<AppOrder>) {
        val container = findViewById<LinearLayout>(R.id.layoutOrdersContainer) ?: return
        val empty = findViewById<TextView>(R.id.tvEmptyOrders) ?: return
        container.removeAllViews()

        if (orders.isEmpty()) {
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE

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

