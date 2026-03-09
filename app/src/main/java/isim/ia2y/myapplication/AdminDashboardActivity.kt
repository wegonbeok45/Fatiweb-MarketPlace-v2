package isim.ia2y.myapplication

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class AdminDashboardActivity : AppCompatActivity() {

    private val avatarPrefsName = "profile_prefs"
    private val avatarUriKey   = "avatar_uri"
    private val logTag         = "AdminDashboard"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        runCatching {
            setContentView(R.layout.activity_admin_dashboard)
            setupWindowInsets()
            setupTopBar()
            setupBottomNav()
            restoreAvatar()
            setupQuickActions()
            revealViewsInOrder(
                R.id.adminTopBar,
                R.id.adminCardWelcome,
                R.id.adminTvStatsHeader,
                R.id.adminStatsRow1,
                R.id.adminStatsRow2,
                R.id.adminTvActionsHeader,
                R.id.adminActionsRow,
                R.id.adminTvOrdersHeader,
                R.id.adminCardOrders,
                R.id.adminBottomNav,
                startDelayMs = 60L,
                staggerMs = 48L
            )
        }.onFailure { e ->
            Log.e(logTag, "Failed to init admin dashboard", e)
            showToast(getString(R.string.coming_soon))
            finish()
        }
        seedProductsOnce()
        loadAdminStats()
    }

    private fun setupWindowInsets() {
        // Top: push the AppBar below the status bar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminAppBar)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top)
            insets
        }
        // Bottom: push the bottom nav above the system nav bar / gesture handle
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminBottomNav)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    private fun setupTopBar() {
        findViewById<View>(R.id.adminIvBack)?.setOnClickListener {
            navigateBackToMain()
        }
        applyPressFeedback(R.id.adminIvBack)
    }

    private fun setupBottomNav() {
        setupAdminBottomNav(AdminNavTab.DASHBOARD)
    }

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.DASHBOARD)
    }

    private fun restoreAvatar() {
        val savedPath = getSharedPreferences(avatarPrefsName, MODE_PRIVATE)
            .getString(avatarUriKey, null) ?: return

        val uri = Uri.parse(savedPath)
        val imageView = findViewById<ImageView>(R.id.adminIvAvatarImg) ?: return

        if (uri.scheme == "file") {
            val file = java.io.File(uri.path ?: "")
            if (!file.exists()) return
        }

        runCatching {
            imageView.setImageURI(uri)
        }.onFailure { e ->
            Log.e(logTag, "Failed to restore avatar on dashboard", e)
        }
    }

    private fun setupQuickActions() {
        applyPressFeedback(
            R.id.adminBtnAddProduct,
            R.id.adminBtnViewOrders,
            R.id.adminBtnSettings
        )

        // Wire "View Orders" button to AdminCommandesActivity
        findViewById<View>(R.id.adminBtnViewOrders)?.setOnClickListener {
            navigateNoShift(AdminCommandesActivity::class.java)
        }

        // Stat cards — still coming soon individually
        bindComingSoon(R.id.adminBtnAddProduct, R.id.adminBtnSettings)

        // Hide the 3 static placeholder order rows — real orders shown in AdminCommandesActivity
        listOf(R.id.adminOrderRow1, R.id.adminOrderRow2, R.id.adminOrderRow3).forEach { id ->
            findViewById<View>(id)?.visibility = View.GONE
        }
    }

    /** Fetch real stats from Firestore and populate the dashboard stat cards. */
    private fun loadAdminStats() {
        lifecycleScope.launch {
            runCatching {
                val stats = FirestoreService.fetchAdminStats()

                findViewById<TextView>(R.id.adminTvCommandesVal)?.text = stats.totalOrders.toString()
                findViewById<TextView>(R.id.adminTvRevenueVal)?.text = formatDt(stats.totalRevenue)
                findViewById<TextView>(R.id.adminTvClientsVal)?.text = stats.totalClients.toString()
                findViewById<TextView>(R.id.adminTvStockVal)?.text = stats.totalProducts.toString()
            }.onFailure { e ->
                Log.w(logTag, "Could not load admin stats", e)
            }
        }
    }

    /**
     * Seeds the 10 local products to Firestore exactly once.
     * After the first run, the flag "catalog_seeded" is set in SharedPreferences
     * so this never runs again on subsequent openings.
     */
    private fun seedProductsOnce() {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        if (prefs.getBoolean("catalog_seeded", false)) return // Already done

        lifecycleScope.launch {
            runCatching {
                FirestoreService.seedProducts()
                prefs.edit().putBoolean("catalog_seeded", true).apply()
                Log.i(logTag, "Product catalog seeded to Firestore successfully.")
            }.onFailure { e ->
                Log.w(logTag, "Could not seed catalog", e)
            }
        }
    }
}
