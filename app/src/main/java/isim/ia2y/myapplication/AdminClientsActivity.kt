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
class AdminClientsActivity : AppCompatActivity() {


    // Cette fonction fait une action de cette partie de l'app.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_clients)
        setupWindowInsets()
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.CLIENTS)

        lifecycleScope.launch {
            val uid = FirebaseAuthManager.currentUser?.uid
            if (uid == null || FirestoreService.fetchUserRole(uid) != "admin") {
                finish()
                return@launch
            }

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

    // Cette fonction fait une action de cette partie de l'app.
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminClientsAppBar)) { view, insets ->
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
        refreshAdminBottomNav(AdminNavTab.CLIENTS)
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun setupTopBar() {
        findViewById<View?>(R.id.adminClientsIvBack)?.setOnClickListener { navigateBackToMain() }
        applyPressFeedback(R.id.adminClientsIvBack)
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun loadClients() {
        lifecycleScope.launch {
            val clients = FirestoreService.fetchAllClients()
            renderClients(clients)
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun renderClients(clients: List<FirestoreService.ClientInfo>) {
        val container = findViewById<android.widget.LinearLayout>(R.id.adminClientsListContainer) ?: return
        container.removeAllViews()

        if (clients.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Aucun client inscrit"
                setPadding(48, 48, 48, 48)
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.profile_text_secondary))
            }
            container.addView(emptyTv)
            return
        }

        val inflater = android.view.LayoutInflater.from(this)
        val density = resources.displayMetrics.density

        clients.forEachIndexed { i, client ->
            val row = inflater.inflate(R.layout.item_admin_client_row, container, false)

            row.findViewById<TextView>(R.id.adminClientName)?.text = client.name
            row.findViewById<TextView>(R.id.adminClientEmail)?.text = client.email
            val orderLabel = if (client.orderCount == 1) "1 commande" else "${client.orderCount} commandes"
            row.findViewById<TextView>(R.id.adminClientId)?.text = orderLabel
            
            val initial = if (client.name.isNotBlank()) client.name.take(1).uppercase() else "?"
            row.findViewById<TextView>(R.id.adminClientAvatarInitial)?.text = initial

            row.setOnClickListener {
                showToast("${client.name} — ${client.email}")
            }

            container.addView(row)

            if (i < clients.lastIndex) {
                val divider = View(this).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        (1 * density).toInt()
                    ).apply {
                        marginStart = (16 * density).toInt()
                        marginEnd = (16 * density).toInt()
                    }
                    setBackgroundColor(androidx.core.content.ContextCompat.getColor(context, R.color.profile_divider))
                }
                container.addView(divider)
            }
        }
    }
}
