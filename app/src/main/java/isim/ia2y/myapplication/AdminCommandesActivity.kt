package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class AdminCommandesActivity : AppCompatActivity() {

    private val rowIds = listOf(R.id.adminCommRow1, R.id.adminCommRow2, R.id.adminCommRow3, R.id.adminCommRow4)
    private val idTvIds = listOf(R.id.adminCommId1, R.id.adminCommId2, R.id.adminCommId3, R.id.adminCommId4)
    private val nameTvIds = listOf(R.id.adminCommName1, R.id.adminCommName2, R.id.adminCommName3, R.id.adminCommName4)
    private val badgeIds = listOf(R.id.adminCommBadge1, R.id.adminCommBadge2, R.id.adminCommBadge3, R.id.adminCommBadge4)

    private var loadedOrders: List<Pair<String, AppOrder>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_commandes)
        setupAdminWindowInsets(R.id.adminCommandesAppBar)
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.COMMANDES)

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
            loadOrders()
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

    private fun loadOrders() {
        lifecycleScope.launch {
            runCatching { FirestoreService.fetchAllOrders() }
                .onSuccess { orders ->
                    loadedOrders = orders
                    renderOrders(orders)
                }
                .onFailure {
                    loadedOrders = emptyList()
                    renderOrders(emptyList())
                    showMotionSnackbar(getString(R.string.admin_orders_load_failed))
                }
        }
    }

    private fun renderOrders(orders: List<Pair<String, AppOrder>>) {
        rowIds.forEach { id -> findViewById<View>(id)?.visibility = View.GONE }

        if (orders.isEmpty()) {
            findViewById<View>(rowIds[0])?.visibility = View.VISIBLE
            findViewById<TextView>(nameTvIds[0])?.text = getString(R.string.admin_orders_empty)
            findViewById<TextView>(idTvIds[0])?.text = ""

            val badgeCard = findViewById<com.google.android.material.card.MaterialCardView>(badgeIds[0])
            val badgeTv = badgeCard?.getChildAt(0) as? TextView
            badgeTv?.text = ""
            return
        }

        orders.take(rowIds.size).forEachIndexed { i, (uid, order) ->
            val row = findViewById<View>(rowIds[i]) ?: return@forEachIndexed
            row.visibility = View.VISIBLE

            val firstProduct = order.items.entries.firstOrNull()?.let { (id, qty) ->
                val name = ProductCatalog.byId(id)?.title?.split(" ")?.firstOrNull() ?: id
                "$name x$qty"
            } ?: getString(R.string.admin_order_fallback_label)

            findViewById<TextView>(idTvIds[i])?.text = order.displayId
            findViewById<TextView>(nameTvIds[i])?.text = firstProduct

            val badgeCard = findViewById<com.google.android.material.card.MaterialCardView>(badgeIds[i])
            val badgeTv = badgeCard?.getChildAt(0) as? TextView
            badgeTv?.text = order.statusLabel(this)

            applyPressFeedback(rowIds[i])
            row.setOnClickListener { showStatusDialog(uid, order, i) }
        }

        val pendingCount = orders.count { it.second.status == "pending" }
        findViewById<TextView>(R.id.adminCommandesTvTotal)?.text = orders.size.toString()
        findViewById<TextView>(R.id.adminCommandesTvPending)?.text = pendingCount.toString()
        val deliveredCount = orders.count { it.second.status == "delivered" }
        findViewById<TextView>(R.id.adminCommandesTvDelivered)?.text = deliveredCount.toString()
    }

    private fun showStatusDialog(uid: String, order: AppOrder, rowIndex: Int) {
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
                            val updatedOrder = order.withStatus(newStatus)
                            loadedOrders = loadedOrders.toMutableList().also {
                                it[rowIndex] = uid to updatedOrder
                            }
                            val badgeCard = findViewById<com.google.android.material.card.MaterialCardView>(badgeIds[rowIndex])
                            val badgeTv = badgeCard?.getChildAt(0) as? TextView
                            badgeTv?.text = updatedOrder.statusLabel(this@AdminCommandesActivity)
                            renderOrders(loadedOrders)
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
}
