package isim.ia2y.myapplication

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class AdminDashboardActivity : AppCompatActivity() {

    private val avatarPrefsName = "profile_prefs"
    private val avatarUriKey   = "avatar_uri"
    private val logTag         = "AdminDashboard"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching {
            setContentView(R.layout.activity_admin_dashboard)
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
            R.id.adminBtnSettings,
            R.id.adminOrderRow1,
            R.id.adminOrderRow2,
            R.id.adminOrderRow3
        )
        listOf(
            R.id.adminBtnAddProduct,
            R.id.adminBtnViewOrders,
            R.id.adminBtnSettings,
            R.id.adminOrderRow1,
            R.id.adminOrderRow2,
            R.id.adminOrderRow3,
            R.id.adminCardCommandes,
            R.id.adminCardRevenue,
            R.id.adminCardClients,
            R.id.adminCardStock
        ).forEach { id ->
            bindComingSoon(id)
        }
    }
}
