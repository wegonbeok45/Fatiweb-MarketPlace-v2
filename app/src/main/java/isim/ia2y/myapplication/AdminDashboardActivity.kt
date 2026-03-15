package isim.ia2y.myapplication

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch

private enum class DashboardInlineTab(val navTab: AdminNavTab, val titleRes: Int) {
    OVERVIEW(AdminNavTab.DASHBOARD, R.string.admin_title_dashboard),
    COMMANDES(AdminNavTab.COMMANDES, R.string.admin_title_orders),
    CLIENTS(AdminNavTab.CLIENTS, R.string.admin_title_clients)
}

private const val EXTRA_ADMIN_DASHBOARD_TAB = "extra_admin_dashboard_tab"
private const val EXTRA_ADMIN_TAB_SWITCH = "extra_admin_tab_switch"

class AdminDashboardActivity : AppCompatActivity() {

    private val logTag = "AdminDashboard"
    private val stateTabKey = "admin_dashboard_active_tab"
    private var activeTab: DashboardInlineTab = DashboardInlineTab.OVERVIEW
    private var commandesContent: View? = null
    private var clientsContent: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val uid = FirebaseAuthManager.currentUser?.uid
        AdminSession.clearIfDifferent(uid)
        if (uid == null) {
            finish()
            return
        }

        runCatching {
            setContentView(R.layout.activity_admin_dashboard)
            setupWindowInsets()
            setupTopBar()
            setupBottomNav()
            setupQuickActions()

            lifecycleScope.launch {
                if (!AdminSession.isVerified(uid)) {
                    val role = FirestoreService.fetchUserRole(uid)
                    if (role != "admin") {
                        finish()
                        return@launch
                    }
                    AdminSession.markVerified(uid)
                }

                val restoredTab = savedInstanceState?.getString(stateTabKey)
                    ?.let { runCatching { DashboardInlineTab.valueOf(it) }.getOrNull() }
                val requestedTab = requestedInlineTabFromIntent(intent.getStringExtra(EXTRA_ADMIN_DASHBOARD_TAB))
                if (savedInstanceState == null) {
                    if (!intent.getBooleanExtra(EXTRA_ADMIN_TAB_SWITCH, false)) {
                        revealViewsInOrder(
                            R.id.adminTopBar,
                            R.id.adminCardWelcome,
                            R.id.adminTvStatsHeader,
                            R.id.adminStatsRow1,
                            R.id.adminTvActionsHeader,
                            R.id.adminActionsRow,
                            R.id.adminTvOrdersHeader,
                            R.id.adminCardOrders,
                            R.id.adminBottomNav,
                            startDelayMs = 60L,
                            staggerMs = 48L
                        )
                    }
                    switchTab(requestedTab ?: DashboardInlineTab.OVERVIEW, animate = false)
                } else {
                    switchTab(restoredTab ?: requestedTab ?: DashboardInlineTab.OVERVIEW, animate = false)
                }

                seedProductsOnce()
                loadAdminStats()
            }
        }.onFailure { e ->
            Log.e(logTag, "Failed to init admin dashboard", e)
            showToast(getString(R.string.coming_soon))
            finish()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestedTab = requestedInlineTabFromIntent(intent.getStringExtra(EXTRA_ADMIN_DASHBOARD_TAB)) ?: return
        switchTab(requestedTab, animate = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(stateTabKey, activeTab.name)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminAppBar)) { view, insets ->
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

    private fun setupTopBar() {
        setupAdminTopBar(getString(R.string.admin_title_dashboard))
    }

    private fun setupBottomNav() {
        setupAdminBottomNav(AdminNavTab.DASHBOARD)

        findViewById<View>(R.id.adminNavDashboard)?.setOnClickListener {
            switchTab(DashboardInlineTab.OVERVIEW, animate = true)
        }
        findViewById<View>(R.id.adminNavCommandes)?.setOnClickListener {
            switchTab(DashboardInlineTab.COMMANDES, animate = true)
        }
        findViewById<View>(R.id.adminNavClients)?.setOnClickListener {
            switchTab(DashboardInlineTab.CLIENTS, animate = true)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(activeTab.navTab)
    }

    private fun setupQuickActions() {
        applyPressFeedback(
            R.id.adminCardCommandes,
            R.id.adminCardClients,
            R.id.adminBtnAddProduct,
            R.id.adminBtnViewOrders,
            R.id.adminBtnSettings
        )

        findViewById<View>(R.id.adminCardCommandes)?.setOnClickListener {
            switchTab(DashboardInlineTab.COMMANDES, animate = true)
        }
        findViewById<View>(R.id.adminCardClients)?.setOnClickListener {
            switchTab(DashboardInlineTab.CLIENTS, animate = true)
        }
        findViewById<View>(R.id.adminBtnViewOrders)?.setOnClickListener {
            switchTab(DashboardInlineTab.COMMANDES, animate = true)
        }

        bindComingSoon(R.id.adminBtnAddProduct, R.id.adminBtnSettings)

        listOf(R.id.adminOrderRow1, R.id.adminOrderRow2, R.id.adminOrderRow3).forEach { id ->
            findViewById<View>(id)?.visibility = View.GONE
        }
    }

    private fun switchTab(tab: DashboardInlineTab, animate: Boolean) {
        if (tab == activeTab && tab != DashboardInlineTab.OVERVIEW) {
            when (tab) {
                DashboardInlineTab.COMMANDES -> loadInlineOrders()
                DashboardInlineTab.CLIENTS -> loadInlineClients()
                DashboardInlineTab.OVERVIEW -> Unit
            }
            return
        }

        activeTab = tab
        setupAdminTopBar(getString(tab.titleRes))
        selectAdminBottomNav(tab.navTab, animate = animate)

        when (tab) {
            DashboardInlineTab.OVERVIEW -> showOverviewContent(animate)
            DashboardInlineTab.COMMANDES -> {
                val content = ensureInlineCommandesView()
                showInlineContent(content, animate)
                loadInlineOrders()
            }
            DashboardInlineTab.CLIENTS -> {
                val content = ensureInlineClientsView()
                showInlineContent(content, animate)
                loadInlineClients()
            }
        }
    }

    private fun ensureInlineCommandesView(): View {
        commandesContent?.let { return it }
        val view = LayoutInflater.from(this)
            .inflate(R.layout.layout_admin_inline_commandes, findViewById<FrameLayout>(R.id.adminInlineContentContainer), false)
        commandesContent = view
        return view
    }

    private fun ensureInlineClientsView(): View {
        clientsContent?.let { return it }
        val view = LayoutInflater.from(this)
            .inflate(R.layout.layout_admin_inline_clients, findViewById<FrameLayout>(R.id.adminInlineContentContainer), false)
        clientsContent = view
        return view
    }

    private fun showOverviewContent(animate: Boolean) {
        val overview = findViewById<NestedScrollView>(R.id.adminScrollContent)
        val inline = findViewById<FrameLayout>(R.id.adminInlineContentContainer)

        if (!animate || isReducedMotionEnabled()) {
            inline.visibility = View.GONE
            overview.visibility = View.VISIBLE
            overview.alpha = 1f
            overview.translationY = 0f
            return
        }

        val distance = 10f * resources.displayMetrics.density
        inline.animate().cancel()
        overview.animate().cancel()

        if (inline.visibility == View.VISIBLE) {
            inline.animate()
                .alpha(0f)
                .translationY(-distance)
                .setDuration(160L)
                .withEndAction {
                    inline.visibility = View.GONE
                    inline.alpha = 1f
                    inline.translationY = 0f
                }
                .start()
        }

        overview.alpha = 0f
        overview.translationY = distance
        overview.visibility = View.VISIBLE
        overview.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .start()
    }

    private fun showInlineContent(content: View, animate: Boolean) {
        val overview = findViewById<NestedScrollView>(R.id.adminScrollContent)
        val inline = findViewById<FrameLayout>(R.id.adminInlineContentContainer)

        if (content.parent != inline) {
            inline.removeAllViews()
            inline.addView(content)
        }

        if (!animate || isReducedMotionEnabled()) {
            overview.visibility = View.GONE
            inline.visibility = View.VISIBLE
            inline.alpha = 1f
            inline.translationY = 0f
            return
        }

        val distance = 10f * resources.displayMetrics.density
        overview.animate().cancel()
        inline.animate().cancel()

        if (overview.visibility == View.VISIBLE) {
            overview.animate()
                .alpha(0f)
                .translationY(-distance)
                .setDuration(160L)
                .withEndAction {
                    overview.visibility = View.GONE
                    overview.alpha = 1f
                    overview.translationY = 0f
                }
                .start()
        }

        inline.alpha = 0f
        inline.translationY = distance
        inline.visibility = View.VISIBLE
        inline.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .start()
    }

    private fun loadInlineOrders() {
        val content = commandesContent ?: return
        lifecycleScope.launch {
            val orders = FirestoreService.fetchAllOrders()
            renderInlineOrders(content, orders)
        }
    }

    private fun renderInlineOrders(content: View, orders: List<Pair<String, AppOrder>>) {
        content.findViewById<TextView>(R.id.adminInlineCommandesTvTotal)?.text = orders.size.toString()
        content.findViewById<TextView>(R.id.adminInlineCommandesTvPending)?.text =
            orders.count { it.second.status == "pending" }.toString()
        content.findViewById<TextView>(R.id.adminInlineCommandesTvDelivered)?.text =
            orders.count { it.second.status == "delivered" }.toString()

        val container = content.findViewById<LinearLayout>(R.id.adminInlineOrdersContainer) ?: return
        container.removeAllViews()

        if (orders.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Aucune commande"
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.profile_text_secondary))
                textSize = 14f
                setPadding(dp(16), dp(28), dp(16), dp(28))
            })
            return
        }

        val inflater = LayoutInflater.from(this)
        orders.take(10).forEachIndexed { i, (uid, order) ->
            val row = inflater.inflate(R.layout.item_admin_inline_order_row, container, false)
            row.findViewById<TextView>(R.id.adminInlineOrderId)?.text = order.displayId
            row.findViewById<TextView>(R.id.adminInlineOrderName)?.text = firstProductSummary(order)
            val badge = row.findViewById<MaterialCardView>(R.id.adminInlineOrderBadge)
            val badgeText = row.findViewById<TextView>(R.id.adminInlineOrderBadgeText)
            badgeText.text = order.statusLabel
            styleOrderBadge(badge, badgeText, order.status)

            row.applyPressFeedback()
            row.setOnClickListener {
                showStatusDialogInline(uid, order)
            }
            container.addView(row)

            if (i < minOf(orders.size, 10) - 1) {
                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1)
                    ).apply {
                        marginStart = dp(16)
                        marginEnd = dp(16)
                    }
                    setBackgroundColor(ContextCompat.getColor(context, R.color.profile_divider))
                })
            }
        }
    }

    private fun showStatusDialogInline(uid: String, order: AppOrder) {
        val statuses = arrayOf("En attente", "En preparation", "En livraison", "Livre")
        val keys = arrayOf("pending", "preparing", "shipped", "delivered")

        android.app.AlertDialog.Builder(this)
            .setTitle("Changer le statut - ${order.displayId}")
            .setItems(statuses) { _, which ->
                val newStatus = keys[which]
                lifecycleScope.launch {
                    FirestoreService.updateOrderStatus(uid, order.id, newStatus)
                    showToast("Statut mis a jour")
                    loadInlineOrders()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun firstProductSummary(order: AppOrder): String {
        return order.items.entries.firstOrNull()?.let { (id, qty) ->
            val name = ProductCatalog.byId(id)?.title?.split(" ")?.firstOrNull() ?: id
            "$name x$qty"
        } ?: "Commande"
    }

    private fun styleOrderBadge(card: MaterialCardView, text: TextView, status: String) {
        when (status) {
            "delivered" -> {
                card.setCardBackgroundColor(0xFFE8F5E9.toInt())
                text.setTextColor(0xFF1B5E20.toInt())
            }
            "pending" -> {
                card.setCardBackgroundColor(0xFFFFF3E0.toInt())
                text.setTextColor(0xFFE65100.toInt())
            }
            "preparing" -> {
                card.setCardBackgroundColor(0xFFE3F2FD.toInt())
                text.setTextColor(0xFF1565C0.toInt())
            }
            else -> {
                card.setCardBackgroundColor(0xFFFCE4EC.toInt())
                text.setTextColor(0xFF880E4F.toInt())
            }
        }
    }

    private fun loadInlineClients() {
        val content = clientsContent ?: return
        lifecycleScope.launch {
            val clients = FirestoreService.fetchAllClients()
            renderInlineClients(content, clients)
        }
    }

    private fun renderInlineClients(content: View, clients: List<FirestoreService.ClientInfo>) {
        content.findViewById<TextView>(R.id.adminInlineClientsTvTotal)?.text = clients.size.toString()
        val container = content.findViewById<LinearLayout>(R.id.adminInlineClientsListContainer) ?: return
        container.removeAllViews()

        if (clients.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "Aucun client inscrit"
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.profile_text_secondary))
                textSize = 14f
                setPadding(dp(16), dp(28), dp(16), dp(28))
            })
            return
        }

        val inflater = LayoutInflater.from(this)
        clients.forEachIndexed { i, client ->
            val row = inflater.inflate(R.layout.item_admin_client_row, container, false)
            row.findViewById<TextView>(R.id.adminClientName)?.text = client.name
            row.findViewById<TextView>(R.id.adminClientEmail)?.text = client.email
            val orderLabel = if (client.orderCount == 1) "1 commande" else "${client.orderCount} commandes"
            row.findViewById<TextView>(R.id.adminClientId)?.text = orderLabel
            row.findViewById<TextView>(R.id.adminClientAvatarInitial)?.text =
                if (client.name.isNotBlank()) client.name.take(1).uppercase() else "?"
            row.setOnClickListener { showToast("${client.name} - ${client.email}") }
            container.addView(row)

            if (i < clients.lastIndex) {
                container.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(1)
                    ).apply {
                        marginStart = dp(16)
                        marginEnd = dp(16)
                    }
                    setBackgroundColor(ContextCompat.getColor(context, R.color.profile_divider))
                })
            }
        }
    }

    private fun requestedInlineTabFromIntent(tabName: String?): DashboardInlineTab? {
        val nav = tabName?.let {
            runCatching { AdminNavTab.valueOf(it) }.getOrNull()
        } ?: return null
        return when (nav) {
            AdminNavTab.COMMANDES -> DashboardInlineTab.COMMANDES
            AdminNavTab.CLIENTS -> DashboardInlineTab.CLIENTS
            else -> DashboardInlineTab.OVERVIEW
        }
    }

    private fun loadAdminStats() {
        lifecycleScope.launch {
            runCatching {
                val ordersCount = FirestoreService.fetchAllOrders().size
                val clientsCount = FirestoreService.fetchAllClients().size
                findViewById<TextView>(R.id.adminTvCommandesVal)?.text = ordersCount.toString()
                findViewById<TextView>(R.id.adminTvClientsVal)?.text = clientsCount.toString()
            }.onFailure { e ->
                Log.w(logTag, "Could not load admin stats", e)
            }
        }
    }

    private fun seedProductsOnce() {
        lifecycleScope.launch {
            runCatching {
                FirestoreService.seedProducts()
                Log.i(logTag, "Product catalog seed check finished.")
            }.onFailure { e ->
                Log.w(logTag, "Could not check/seed catalog", e)
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
