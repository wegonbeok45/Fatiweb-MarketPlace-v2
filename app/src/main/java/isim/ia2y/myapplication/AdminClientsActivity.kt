package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class AdminClientsActivity : AppCompatActivity() {

    private val rowIds    = listOf(R.id.adminClientRow1,   R.id.adminClientRow2,   R.id.adminClientRow3)
    private val nameTvIds = listOf(R.id.adminClientName1,  R.id.adminClientName2,  R.id.adminClientName3)
    private val emailIds  = listOf(R.id.adminClientEmail1, R.id.adminClientEmail2, R.id.adminClientEmail3)
    private val idTvIds   = listOf(R.id.adminClientId1,    R.id.adminClientId2,    R.id.adminClientId3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_clients)
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.CLIENTS)
        revealViewsInOrder(
            R.id.adminClientsTopBar,
            R.id.adminClientsStatsRow,
            R.id.adminClientsTvHeader,
            R.id.adminClientsCard,
            startDelayMs = 60L,
            staggerMs = 48L
        )
        loadClients()
    }

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.CLIENTS)
    }

    private fun setupTopBar() {
        findViewById<View?>(R.id.adminClientsIvBack)?.setOnClickListener { navigateBackToMain() }
        applyPressFeedback(R.id.adminClientsIvBack)
    }

    private fun loadClients() {
        lifecycleScope.launch {
            val clients = FirestoreService.fetchAllClients()
            renderClients(clients)
        }
    }

    private fun renderClients(clients: List<FirestoreService.ClientInfo>) {
        rowIds.forEach { id -> findViewById<View>(id)?.visibility = View.GONE }

        if (clients.isEmpty()) {
            findViewById<View>(rowIds[0])?.visibility = View.VISIBLE
            findViewById<TextView>(nameTvIds[0])?.text = "Aucun client inscrit"
            return
        }

        clients.take(rowIds.size).forEachIndexed { i, client ->
            val row = findViewById<View>(rowIds[i]) ?: return@forEachIndexed
            row.visibility = View.VISIBLE

            findViewById<TextView>(nameTvIds[i])?.text = client.name
            findViewById<TextView>(emailIds[i])?.text  = client.email
            val orderLabel = if (client.orderCount == 1) "1 commande" else "${client.orderCount} commandes"
            findViewById<TextView>(idTvIds[i])?.text   = orderLabel

            applyPressFeedback(rowIds[i])
            row.setOnClickListener {
                showToast("${client.name} — ${client.email}")
            }
        }

        // Update total clients count if view exists
        // NOTE: adminClientsTvCount not in layout, using adminClientsTvHeader or others if needed
        // findViewById<TextView>(R.id.adminClientsTvCount)?.text = clients.size.toString()
    }
}
