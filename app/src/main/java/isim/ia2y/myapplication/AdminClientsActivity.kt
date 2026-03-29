package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch

class AdminClientsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_clients)
        setupAdminWindowInsets(R.id.adminClientsAppBar)
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.CLIENTS)
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.adminClientsList)?.layoutManager =
            LinearLayoutManager(this)

        lifecycleScope.launch {
            if (!requireAdminRole()) return@launch

            if (savedInstanceState == null) {
                revealViewsInOrder(
                    R.id.adminClientsTopBar,
                    R.id.adminClientsStatsRow,
                    R.id.adminClientsTvHeader,
                    R.id.adminClientsCard,
                    startDelayMs = 60L,
                    staggerMs = 48L
                )
            }
            loadClients()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.CLIENTS)
    }

    private fun setupTopBar() {
        findViewById<View?>(R.id.adminClientsIvBack)?.setOnClickListener { navigateBackToMain() }
        findViewById<View?>(R.id.adminClientsIvSettings)?.setOnClickListener {
            navigateNoShift(AdminParametresActivity::class.java)
        }
        applyPressFeedback(R.id.adminClientsIvBack, R.id.adminClientsIvSettings)
    }

    private fun loadClients() {
        lifecycleScope.launch {
            val result = runCatching { FirestoreService.fetchAllClients() }
            val state: ScreenState<List<FirestoreService.ClientInfo>> = result.fold(
                onSuccess = { clients ->
                    if (clients.isEmpty()) ScreenState.Empty(getString(R.string.admin_no_clients)) else ScreenState.Content(clients)
                },
                onFailure = { ScreenState.Error(getString(R.string.admin_clients_load_error)) }
            )
            renderClients(state)
        }
    }

    private fun renderClients(state: ScreenState<List<FirestoreService.ClientInfo>>) {
        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.adminClientsList) ?: return
        val totalText = findViewById<TextView>(R.id.adminClientsTvTotal)
        val newText = findViewById<TextView>(R.id.adminClientsTvNew)
        val loading = findViewById<ProgressBar>(R.id.loadingIndicator)

        when (state) {
            is ScreenState.Content -> {
                loading?.visibility = View.GONE
                val newClients = state.data.count {
                    System.currentTimeMillis() - it.createdAt <= 30L * 24L * 60L * 60L * 1000L
                }
                totalText?.text = state.data.size.toString()
                newText?.text = newClients.toString()
                newText?.visibility = View.VISIBLE
                recycler.adapter = AdminClientsStaticAdapter(state.data) { client ->
                    showToast(getString(R.string.admin_client_selected, client.name))
                }
            }
            is ScreenState.Empty -> {
                loading?.visibility = View.GONE
                totalText?.text = "0"
                newText?.text = "0"
                newText?.visibility = View.VISIBLE
                recycler.adapter = AdminClientsStaticAdapter(emptyList()) { }
                showMotionSnackbar(state.message)
            }
            is ScreenState.Error -> {
                loading?.visibility = View.GONE
                totalText?.text = "0"
                newText?.text = "0"
                newText?.visibility = View.VISIBLE
                recycler.adapter = AdminClientsStaticAdapter(emptyList()) { }
                showMotionSnackbar(state.message)
            }
            ScreenState.Loading -> {
                loading?.visibility = View.VISIBLE
            }
        }
    }
}
