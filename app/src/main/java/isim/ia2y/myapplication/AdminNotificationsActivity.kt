package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

class AdminNotificationsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_notifications)
        setupWindowInsets()
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.NOTIFICATIONS)

        if (savedInstanceState == null) {
            revealViewsInOrder(
                R.id.adminNotifTopBar,
                R.id.adminNotifComposeCard,
                R.id.adminNotifHistoryCard,
                startDelayMs = 60L,
                staggerMs = 48L
            )
        }
        applyPressFeedback(R.id.adminNotifBtnSend)
        findViewById<View?>(R.id.adminNotifBtnSend)?.setOnClickListener {
            showToast("Notification envoyée !")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.NOTIFICATIONS)
    }

    private fun setupTopBar() {
        findViewById<View?>(R.id.adminNotifIvBack)?.setOnClickListener {
            navigateBackToMain()
        }
        applyPressFeedback(R.id.adminNotifIvBack)
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminBottomNav)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom)
            insets
        }
    }
}
