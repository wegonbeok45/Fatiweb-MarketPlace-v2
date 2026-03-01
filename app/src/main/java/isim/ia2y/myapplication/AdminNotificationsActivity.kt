package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class AdminNotificationsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_notifications)
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.NOTIFICATIONS)
        revealViewsInOrder(
            R.id.adminNotifTopBar,
            R.id.adminNotifComposeCard,
            R.id.adminNotifHistoryCard,
            startDelayMs = 60L,
            staggerMs = 48L
        )
        applyPressFeedback(R.id.adminNotifBtnSend)
        findViewById<View?>(R.id.adminNotifBtnSend)?.setOnClickListener {
            showToast("Notification envoyée !")
        }
    }

    private fun setupTopBar() {
        findViewById<View?>(R.id.adminNotifIvBack)?.setOnClickListener {
            navigateBackToMain()
        }
        applyPressFeedback(R.id.adminNotifIvBack)
    }
}
