package isim.ia2y.myapplication

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class AdminClientsActivity : AppCompatActivity() {
    private val allClients = mutableListOf<FirestoreService.ClientInfo>()
    private var lastVisible: com.google.firebase.firestore.DocumentSnapshot? = null
    private var isLastPage = false
    private var isLoading = false
    private val pageSize = 30
    private var searchQuery = ""
    private val clientsAdapter = AdminClientsAdapter { client -> showClientDetails(client) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_clients)
        setupAdminWindowInsets(R.id.adminClientsAppBar)
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.CLIENTS)
        setupFilters()
        val recycler = findViewById<RecyclerView>(R.id.adminClientsList)
        recycler?.layoutManager = LinearLayoutManager(this)
        recycler?.adapter = clientsAdapter
        recycler?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (!isLoading && !isLastPage && layoutManager.findLastVisibleItemPosition() >= allClients.size - 5) {
                    loadNextPage()
                }
            }
        })

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
            resetAndLoadClients()
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

    private fun setupFilters() {
        findViewById<android.widget.EditText>(R.id.adminClientsSearchInput)?.doAfterTextChanged {
            searchQuery = it?.toString().orEmpty().trim()
            renderClients(
                when {
                    isLoading && allClients.isEmpty() -> ScreenState.Loading
                    allClients.isEmpty() -> ScreenState.Empty(getString(R.string.admin_no_clients))
                    else -> ScreenState.Content(allClients)
                }
            )
        }
    }

    private fun loadClients() {
        resetAndLoadClients()
    }

    private fun resetAndLoadClients() {
        allClients.clear()
        lastVisible = null
        isLastPage = false
        renderClients(ScreenState.Loading)
        loadNextPage()
    }

    private fun loadNextPage() {
        if (isLoading || isLastPage) return
        isLoading = true
        findViewById<ProgressBar>(R.id.loadingIndicator)?.visibility = View.VISIBLE
        lifecycleScope.launch {
            val result = runCatching { AdminService.fetchClientsPage(pageSize, lastVisible) }
            val state: ScreenState<List<FirestoreService.ClientInfo>> = result.fold(
                onSuccess = { snapshot ->
                    isLoading = false
                    findViewById<ProgressBar>(R.id.loadingIndicator)?.visibility = View.GONE
                    val clients = snapshot.documents.mapNotNull(AdminService::clientInfoFromDocument)
                    if (clients.isEmpty()) {
                        isLastPage = true
                    } else {
                        allClients.addAll(clients)
                        lastVisible = snapshot.documents.lastOrNull()
                        if (clients.size < pageSize) isLastPage = true
                    }
                    if (allClients.isEmpty()) ScreenState.Empty(getString(R.string.admin_no_clients)) else ScreenState.Content(allClients)
                },
                onFailure = {
                    isLoading = false
                    findViewById<ProgressBar>(R.id.loadingIndicator)?.visibility = View.GONE
                    if (allClients.isNotEmpty()) {
                        showMotionSnackbar(getString(R.string.admin_clients_load_error))
                        ScreenState.Content(allClients)
                    } else {
                        ScreenState.Error(getString(R.string.admin_clients_load_error))
                    }
                }
            )
            renderClients(state)
        }
    }

    private fun renderClients(state: ScreenState<List<FirestoreService.ClientInfo>>) {
        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.adminClientsList) ?: return
        val totalText = findViewById<TextView>(R.id.adminClientsTvTotal)
        val newText = findViewById<TextView>(R.id.adminClientsTvNew)
        val loading = findViewById<ProgressBar>(R.id.loadingIndicator)
        val emptyView = findViewById<TextView>(R.id.adminClientsEmpty)

        when (state) {
            is ScreenState.Content -> {
                loading?.visibility = if (isLoading) View.VISIBLE else View.GONE
                val filtered = state.data.filter {
                    searchQuery.isBlank() ||
                        it.name.contains(searchQuery, true) ||
                        it.email.contains(searchQuery, true) ||
                        it.phone.contains(searchQuery, true) ||
                        it.role.contains(searchQuery, true)
                }
                val newClients = state.data.count {
                    System.currentTimeMillis() - it.createdAt <= 30L * 24L * 60L * 60L * 1000L
                }
                totalText?.text = state.data.size.toString()
                newText?.text = newClients.toString()
                newText?.visibility = View.VISIBLE
                clientsAdapter.submitList(filtered.toList())
                emptyView?.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
                emptyView?.text = getString(R.string.admin_no_clients)
            }
            is ScreenState.Empty -> {
                loading?.visibility = View.GONE
                totalText?.text = getString(R.string.str_2f43b4)
                newText?.text = getString(R.string.str_2f43b4)
                newText?.visibility = View.VISIBLE
                clientsAdapter.submitList(emptyList())
                emptyView?.visibility = View.VISIBLE
                emptyView?.text = state.message
            }
            is ScreenState.Error -> {
                loading?.visibility = View.GONE
                totalText?.text = getString(R.string.str_2f43b4)
                newText?.text = getString(R.string.str_2f43b4)
                newText?.visibility = View.VISIBLE
                clientsAdapter.submitList(emptyList())
                emptyView?.visibility = View.VISIBLE
                emptyView?.text = state.message
                showMotionSnackbar(state.message)
            }
            ScreenState.Loading -> {
                loading?.visibility = View.VISIBLE
                if (allClients.isEmpty()) {
                    totalText?.text = getString(R.string.str_2f43b4)
                    newText?.text = getString(R.string.str_2f43b4)
                    newText?.visibility = View.VISIBLE
                    emptyView?.visibility = View.GONE
                    clientsAdapter.submitList(emptyList())
                }
            }
        }
    }

    private fun showClientDetails(client: FirestoreService.ClientInfo) {
        startActivity(AdminClientDetailsActivity.createIntent(this, client.uid))
    }

    private fun showClientDetailsDialog(client: FirestoreService.ClientInfo) {
        val joinedDate = if (client.createdAt > 0L) {
            java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.FRANCE)
                .format(java.util.Date(client.createdAt))
        } else {
            getString(R.string.admin_client_unknown_date)
        }

        val phone = client.phone.ifBlank { getString(R.string.admin_client_fallback_phone) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(client.name.ifBlank { getString(R.string.admin_client_fallback_name) })
            .setMessage(
                getString(
                    R.string.admin_client_detail_message,
                    client.email.ifBlank { getString(R.string.admin_client_fallback_email) },
                    phone,
                    client.role,
                    client.uid,
                    client.orderCount,
                    joinedDate
                )
            )
            .setNegativeButton(R.string.admin_dialog_close, null)
        if (client.role.equals(UserRoles.VENDEUR, ignoreCase = true)) {
            dialog.setPositiveButton(R.string.admin_client_contact) { _, _ ->
                openEmail(client.email, "FatiWeb - ${client.name}")
            }
        } else {
            dialog
                .setPositiveButton(R.string.admin_client_make_vendeur) { _, _ -> confirmPromoteClient(client) }
                .setNeutralButton(R.string.admin_client_contact) { _, _ ->
                    openEmail(client.email, "FatiWeb - ${client.name}")
                }
        }
        dialog.show()
    }

    private fun confirmPromoteClient(client: FirestoreService.ClientInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.admin_client_make_vendeur)
            .setMessage(R.string.admin_client_promote_confirm)
            .setNegativeButton(R.string.admin_dialog_cancel, null)
            .setPositiveButton(R.string.admin_client_promote_cta) { _, _ ->
                lifecycleScope.launch {
                    runCatching { FirestoreService.promoteUserToVendeur(client.uid) }
                        .onSuccess {
                            markClientAsVendeur(client.uid)
                            showMotionSnackbar(getString(R.string.admin_client_promote_success))
                            loadClients()
                        }
                        .onFailure {
                            Log.e(TAG, "Failed to promote client to vendeur: ${client.uid}", it)
                            showMotionSnackbar(getString(R.string.admin_client_promote_failed))
                        }
                }
            }
            .show()
    }

    private fun markClientAsVendeur(uid: String) {
        val index = allClients.indexOfFirst { it.uid == uid }
        if (index == -1) return
        allClients[index] = allClients[index].copy(role = UserRoles.VENDEUR)
        renderClients(ScreenState.Content(allClients))
    }

    private companion object {
        const val TAG = "AdminClientsActivity"
    }
}
