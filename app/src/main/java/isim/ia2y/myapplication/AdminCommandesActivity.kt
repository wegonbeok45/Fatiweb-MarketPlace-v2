package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class AdminCommandesActivity : AppCompatActivity() {

    // Each row slot in the layout (up to 4 visible rows)
    private val rowIds   = listOf(R.id.adminCommRow1,  R.id.adminCommRow2,  R.id.adminCommRow3,  R.id.adminCommRow4)
    private val idTvIds  = listOf(R.id.adminCommId1,   R.id.adminCommId2,   R.id.adminCommId3,   R.id.adminCommId4)
    private val nameTvIds= listOf(R.id.adminCommName1, R.id.adminCommName2, R.id.adminCommName3, R.id.adminCommName4)
    private val badgeIds = listOf(R.id.adminCommBadge1,R.id.adminCommBadge2,R.id.adminCommBadge3,R.id.adminCommBadge4)

    // In-memory list so row taps can reference the right order
    private var loadedOrders: List<Pair<String, AppOrder>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_commandes)
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.COMMANDES)
        revealViewsInOrder(
            R.id.adminCommandesTopBar,
            R.id.adminCommandesStatsRow,
            R.id.adminCommandesTvHeader,
            R.id.adminCommandesCard,
            startDelayMs = 60L,
            staggerMs = 48L
        )
        loadOrders()
    }

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.COMMANDES)
    }

    private fun setupTopBar() {
        findViewById<View?>(R.id.adminCommandesIvBack)?.setOnClickListener { navigateBackToMain() }
        applyPressFeedback(R.id.adminCommandesIvBack)
    }

    private fun loadOrders() {
        lifecycleScope.launch {
            val orders = FirestoreService.fetchAllOrders()
            loadedOrders = orders
            renderOrders(orders)
        }
    }

    private fun renderOrders(orders: List<Pair<String, AppOrder>>) {
        // Hide all rows first
        rowIds.forEach { id -> findViewById<View>(id)?.visibility = View.GONE }

        if (orders.isEmpty()) {
            // Show empty hint in first row label
            findViewById<View>(rowIds[0])?.visibility = View.VISIBLE
            findViewById<TextView>(nameTvIds[0])?.text = "Aucune commande"
            findViewById<TextView>(idTvIds[0])?.text   = ""
            findViewById<TextView>(badgeIds[0])?.text  = ""
            return
        }

        // Show up to 4 most recent orders
        orders.take(rowIds.size).forEachIndexed { i, (uid, order) ->
            val row = findViewById<View>(rowIds[i]) ?: return@forEachIndexed
            row.visibility = View.VISIBLE

            // First product name summary
            val firstProduct = order.items.entries.firstOrNull()?.let { (id, qty) ->
                val name = ProductCatalog.byId(id)?.title?.split(" ")?.firstOrNull() ?: id
                "$name x$qty"
            } ?: "Commande"

            findViewById<TextView>(idTvIds[i])?.text   = order.displayId
            findViewById<TextView>(nameTvIds[i])?.text = firstProduct
            findViewById<TextView>(badgeIds[i])?.text  = order.statusLabel

            applyPressFeedback(rowIds[i])
            row.setOnClickListener { showStatusDialog(uid, order, i) }
        }

        // Update header stats if views exist
        val pendingCount = orders.count { it.second.status == "pending" }
        findViewById<TextView>(R.id.adminCommandesTvTotal)?.text = orders.size.toString()
        findViewById<TextView>(R.id.adminCommandesTvPending)?.text = pendingCount.toString()
        val deliveredCount = orders.count { it.second.status == "delivered" }
        findViewById<TextView>(R.id.adminCommandesTvDelivered)?.text = deliveredCount.toString()
    }

    private fun showStatusDialog(uid: String, order: AppOrder, rowIndex: Int) {
        val statuses = arrayOf("En attente", "En préparation", "En livraison", "Livré")
        val keys     = arrayOf("pending",    "preparing",      "shipped",      "delivered")

        android.app.AlertDialog.Builder(this)
            .setTitle("Changer le statut — ${order.displayId}")
            .setItems(statuses) { _, which ->
                val newStatus = keys[which]
                lifecycleScope.launch {
                    FirestoreService.updateOrderStatus(uid, order.id, newStatus)
                    // Update the badge immediately in the UI
                    val updatedOrder = order.copy(status = newStatus)
                    loadedOrders = loadedOrders.toMutableList().also {
                        it[rowIndex] = uid to updatedOrder
                    }
                    findViewById<TextView>(badgeIds[rowIndex])?.text = updatedOrder.statusLabel
                    showToast("Statut mis à jour")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
