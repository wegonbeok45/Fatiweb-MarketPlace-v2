package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.AutoCompleteTextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

class AdminCommandesActivity : AppCompatActivity() {
    private data class OrderFilterOption(val key: String, val label: String)

    private val allOrders = mutableListOf<Pair<String, AppOrder>>()
    private val orderFilters by lazy {
        listOf(
            OrderFilterOption("all", getString(R.string.admin_filter_all)),
            OrderFilterOption("pending", getString(R.string.order_status_pending)),
            OrderFilterOption("preparing", getString(R.string.order_status_preparing)),
            OrderFilterOption("shipped", getString(R.string.order_status_shipped)),
            OrderFilterOption("delivered", getString(R.string.order_status_delivered))
        )
    }
    private var lastVisible: com.google.firebase.firestore.DocumentSnapshot? = null
    private var isLastPage = false
    private var isLoading = false
    private val pageSize = 20
    private var searchQuery = ""
    private var selectedFilter = "all"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_commandes)
        setupAdminWindowInsets(R.id.adminCommandesAppBar)
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.COMMANDES)
        setupFilters()

        val recycler = findViewById<RecyclerView>(R.id.adminCommandesList)
        recycler?.layoutManager = LinearLayoutManager(this)
        
        recycler?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (!isLoading && !isLastPage) {
                    if (layoutManager.findLastVisibleItemPosition() >= allOrders.size - 5) {
                        loadNextPage()
                    }
                }
            }
        })

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
            loadNextPage()
            loadStats()
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

    private fun setupFilters() {
        findViewById<android.widget.EditText>(R.id.adminCommandesSearchInput)?.doAfterTextChanged {
            searchQuery = it?.toString().orEmpty().trim()
            renderOrders()
        }
        val filterInput = findViewById<AutoCompleteTextView>(R.id.adminCommandesFilterInput)
        filterInput?.setAdapter(
            android.widget.ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                orderFilters.map { it.label }
            )
        )
        filterInput?.setText(orderFilters.first().label, false)
        filterInput?.setOnClickListener { filterInput.showDropDown() }
        filterInput?.setOnItemClickListener { _, _, position, _ ->
            selectedFilter = orderFilters[position].key
            renderOrders()
        }
    }

    private fun loadStats() {
        lifecycleScope.launch {
            runCatching { AdminService.fetchOrderStatusSummary() }
                .onSuccess { stats ->
                    findViewById<TextView>(R.id.adminCommandesTvTotal)?.text = stats.total.toString()
                    findViewById<TextView>(R.id.adminCommandesTvPending)?.text = stats.pending.toString()
                    findViewById<TextView>(R.id.adminCommandesTvDelivered)?.text = stats.delivered.toString()
                }
        }
    }

    private fun loadNextPage() {
        if (isLoading || isLastPage) return
        isLoading = true
        findViewById<ProgressBar>(R.id.adminCommandesProgress)?.visibility = View.VISIBLE

        lifecycleScope.launch {
            runCatching { 
                AdminService.fetchOrdersPage(pageSize, lastVisible)
            }.onSuccess { snapshot ->
                isLoading = false
                findViewById<ProgressBar>(R.id.adminCommandesProgress)?.visibility = View.GONE
                
                val newItems = snapshot.documents.mapNotNull { doc ->
                    val data = doc.data ?: return@mapNotNull null
                    val uid = data["uid"] as? String ?: return@mapNotNull null
                    uid to AppOrder.fromMap(data)
                }

                if (newItems.isEmpty()) {
                    isLastPage = true
                } else {
                    allOrders.addAll(newItems)
                    lastVisible = snapshot.documents.lastOrNull()
                    if (newItems.size < pageSize) isLastPage = true
                }
                loadStats()
                renderOrders()
            }.onFailure {
                isLoading = false
                findViewById<ProgressBar>(R.id.adminCommandesProgress)?.visibility = View.GONE
                showMotionSnackbar("Erreur lors du chargement des commandes")
            }
        }
    }

    private fun renderOrders() {
        val recycler = findViewById<RecyclerView>(R.id.adminCommandesList) ?: return
        val emptyView = findViewById<TextView>(R.id.adminCommandesEmpty)
        val filteredOrders = filteredOrders()

        if (filteredOrders.isEmpty() && isLastPage) {
            emptyView?.visibility = View.VISIBLE
            emptyView?.text = getString(
                if (searchQuery.isNotBlank() || selectedFilter != "all") {
                    R.string.admin_orders_empty_filtered
                } else {
                    R.string.admin_orders_empty
                }
            )
            recycler.adapter = AdminOrdersAdapter { _, _ -> }.apply { submitList(emptyList()) }
            return
        }

        emptyView?.visibility = View.GONE
        var adapter = recycler.adapter as? AdminOrdersAdapter
        if (adapter == null) {
            adapter = AdminOrdersAdapter { uid, order ->
                showOrderDetailsDialog(uid, order)
            }
            recycler.adapter = adapter
        }
        adapter.submitList(ArrayList(filteredOrders))
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
                            val index = allOrders.indexOfFirst { it.second.id == order.id }
                            if (index >= 0) {
                                allOrders[index] = uid to allOrders[index].second.withStatus(newStatus)
                            }
                            loadStats()
                            renderOrders()
                            showMotionSnackbar(getString(R.string.admin_order_status_updated))
                        }
                        .onFailure {
                            showMotionSnackbar(getString(R.string.admin_order_status_update_failed))
                        }
                }
            }
            .setNegativeButton(getString(R.string.admin_dialog_cancel), null)
            .show()
    }

    private fun showOrderDetailsDialog(uid: String, order: AppOrder) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(order.displayId)
            .setMessage(
                getString(
                    R.string.admin_order_detail_message,
                    order.statusLabel(this),
                    order.paymentMethod,
                    order.items.sumOf { it.quantity },
                    formatDt(order.total),
                    uid
                )
            )
            .setPositiveButton(R.string.admin_order_change_status_cta) { _, _ ->
                showStatusDialog(uid, order)
            }
            .setNegativeButton(R.string.admin_dialog_close, null)
            .show()
    }

    private fun filteredOrders(): List<Pair<String, AppOrder>> {
        return allOrders.filter { (_, order) ->
            val matchesQuery = searchQuery.isBlank() || order.displayId.contains(searchQuery, true) ||
                order.items.any { it.name.contains(searchQuery, true) }
            val matchesFilter = selectedFilter == "all" || order.status == selectedFilter
            matchesQuery && matchesFilter
        }
    }

    private fun updateStatsFromLoadedOrders() {
        loadStats()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
