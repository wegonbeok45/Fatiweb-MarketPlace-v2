package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class AdminClientsActivity : AppCompatActivity() {

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
        applyPressFeedback(
            R.id.adminClientRow1,
            R.id.adminClientRow2,
            R.id.adminClientRow3
        )
        bindComingSoon(
            R.id.adminClientRow1,
            R.id.adminClientRow2,
            R.id.adminClientRow3
        )
    }

    private fun setupTopBar() {
        findViewById<View?>(R.id.adminClientsIvBack)?.setOnClickListener {
            finishWithMotion(isForward = false)
        }
        applyPressFeedback(R.id.adminClientsIvBack)
    }
}
