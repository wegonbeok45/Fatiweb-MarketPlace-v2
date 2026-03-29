package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class AdminCommandesActivity : AppCompatActivity() {

    private var loadedOrders: List<Pair<String, AppOrder>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_commandes)
        setupAdminWindowInsets(R.id.adminCommandesAppBar)
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.COMMANDES)

        findViewById<RecyclerView>(R.id.adminCommandesList)?.layoutManager =
            LinearLayoutManager(this)

        lifecycleScope.launch {
            if (!requireAdminRole()) return@launch

            if (savedInstanceState == null) {
                revealViewsInOrder(
                    R.id.adminCommandesTopBar,
                    R.id.adminCommandesStatsRow,
                    R.id.adminCommandesTvHeader,
                    R.id.adminCommandesCard,
                    startDelayMs = 60L,
                    staggerMs = 48L
                )
            }
            listenToOrders()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.COMMANDES)
    }

    private fun setupTopBar() {
        findViewById<View?>(R.id.adminCommandesIvBack)?.setOnClickListener { navigateBackToMain() }
        findViewById<View?>(R.id.adminCommandesIvSettings)?.setOnClickListener {
            navigateNoShift(AdminParametresActivity::class.java)
        }
        applyPressFeedback(R.id.adminCommandesIvBack, R.id.adminCommandesIvSettings)
    }

    private var ordersListener: com.google.firebase.firestore.ListenerRegistration? = null

    private fun listenToOrders() {
        findViewById<ProgressBar>(R.id.adminCommandesProgress)?.visibility = View.VISIBLE
        findViewById<View>(R.id.adminCommandesEmpty)?.visibility = View.GONE
        
        ordersListener?.remove()
        ordersListener = AdminService.listenToAllOrders { orders ->
            loadedOrders = orders
            findViewById<ProgressBar>(R.id.adminCommandesProgress)?.visibility = View.GONE
            renderOrders(loadedOrders)
        }
    }

    private fun renderOrders(orders: List<Pair<String, AppOrder>>) {
        val recycler = findViewById<RecyclerView>(R.id.adminCommandesList) ?: return
        val emptyView = findViewById<TextView>(R.id.adminCommandesEmpty)

        if (orders.isEmpty()) {
            emptyView?.visibility = View.VISIBLE
            recycler.adapter = AdminOrdersAdapter(emptyList()) { _, _ -> }
            return
        }

        emptyView?.visibility = View.GONE
        recycler.adapter = AdminOrdersAdapter(orders) { uid, order ->
            showStatusDialog(uid, order)
        }

        val pendingCount = orders.count { it.second.status == "pending" }
        findViewById<TextView>(R.id.adminCommandesTvTotal)?.text = orders.size.toString()
        findViewById<TextView>(R.id.adminCommandesTvPending)?.text = pendingCount.toString()
        val deliveredCount = orders.count { it.second.status == "delivered" }
        findViewById<TextView>(R.id.adminCommandesTvDelivered)?.text = deliveredCount.toString()
    }

    private fun showStatusDialog(uid: String, order: AppOrder) {
        val statuses = arrayOf(
            getString(R.string.order_status_pending),
            getString(R.string.order_status_preparing),
            getString(R.string.order_status_shipped),
            getString(R.string.order_status_delivered)
        )
        val keys = arrayOf("pending", "preparing", "shipped", "delivered")
        val currentIndex = keys.indexOfFirst { it == order.status }.coerceAtLeast(0)

        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.admin_order_change_status_title, order.displayId))
            .setSingleChoiceItems(statuses, currentIndex) { dialog, which ->
                val newStatus = keys[which]
                dialog.dismiss()
                lifecycleScope.launch {
                    runCatching { FirestoreService.updateOrderStatus(uid, order.id, newStatus) }
                        .onSuccess {
                            showToast(getString(R.string.admin_order_status_updated))
                        }
                        .onFailure {
                            showMotionSnackbar(getString(R.string.admin_order_status_update_failed))
                        }
                }
            }
            .setNegativeButton(getString(R.string.admin_dialog_cancel), null)
            .show()
    }

    override fun onDestroy() {
        ordersListener?.remove()
        super.onDestroy()
    }
}
