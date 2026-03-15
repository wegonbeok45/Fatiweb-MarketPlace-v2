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

// Cette classe organise cette partie de l'app.
class AdminCommandesActivity : AppCompatActivity() {

    // Each row slot in the layout (up to 4 visible rows)
    private val rowIds   = listOf(R.id.adminCommRow1,  R.id.adminCommRow2,  R.id.adminCommRow3,  R.id.adminCommRow4)
    private val idTvIds  = listOf(R.id.adminCommId1,   R.id.adminCommId2,   R.id.adminCommId3,   R.id.adminCommId4)
    private val nameTvIds= listOf(R.id.adminCommName1, R.id.adminCommName2, R.id.adminCommName3, R.id.adminCommName4)
    private val badgeIds = listOf(R.id.adminCommBadge1,R.id.adminCommBadge2,R.id.adminCommBadge3,R.id.adminCommBadge4)

    // In-memory list so row taps can reference the right order
    private var loadedOrders: List<Pair<String, AppOrder>> = emptyList()

    // Cette fonction fait une action de cette partie de l'app.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_commandes)
        setupWindowInsets()
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.COMMANDES)

        lifecycleScope.launch {
            val uid = FirebaseAuthManager.currentUser?.uid
            if (uid == null || FirestoreService.fetchUserRole(uid) != "admin") {
                finish()
                return@launch
            }

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

    // Cette fonction fait une action de cette partie de l'app.
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminCommandesAppBar)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminBottomNav)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.COMMANDES)
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun setupTopBar() {
        findViewById<View?>(R.id.adminCommandesIvBack)?.setOnClickListener { navigateBackToMain() }
        applyPressFeedback(R.id.adminCommandesIvBack)
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun loadOrders() {
        lifecycleScope.launch {
            val orders = FirestoreService.fetchAllOrders()
            loadedOrders = orders
            renderOrders(orders)
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun renderOrders(orders: List<Pair<String, AppOrder>>) {
        // Hide all rows first
        rowIds.forEach { id -> findViewById<View>(id)?.visibility = View.GONE }

        if (orders.isEmpty()) {
            // Show empty hint in first row label
            findViewById<View>(rowIds[0])?.visibility = View.VISIBLE
            findViewById<TextView>(nameTvIds[0])?.text = "Aucune commande"
            findViewById<TextView>(idTvIds[0])?.text   = ""
            
            val badgeCard = findViewById<com.google.android.material.card.MaterialCardView>(badgeIds[0])
            val badgeTv = badgeCard?.getChildAt(0) as? TextView
            badgeTv?.text = ""
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
            
            val badgeCard = findViewById<com.google.android.material.card.MaterialCardView>(badgeIds[i])
            val badgeTv = badgeCard?.getChildAt(0) as? TextView
            badgeTv?.text = order.statusLabel

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

    // Cette fonction fait une action de cette partie de l'app.
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
                    val badgeCard = findViewById<com.google.android.material.card.MaterialCardView>(badgeIds[rowIndex])
                    val badgeTv = badgeCard?.getChildAt(0) as? TextView
                    badgeTv?.text = updatedOrder.statusLabel
                    showToast("Statut mis à jour")
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}
