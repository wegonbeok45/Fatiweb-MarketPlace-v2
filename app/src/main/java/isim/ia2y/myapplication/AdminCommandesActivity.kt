package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class AdminCommandesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_commandes)
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.COMMANDES)
        revealViewsInOrder(
            R.id.adminCommandesTopBar,
            R.id.adminCommandesStatsRow,
            R.id.adminCommandesTvHeader,
            R.id.adminCommandesCard,
            startDelayMs = 60L,
            staggerMs = 48L
        )
        applyPressFeedback(
            R.id.adminCommRow1,
            R.id.adminCommRow2,
            R.id.adminCommRow3,
            R.id.adminCommRow4
        )
        bindComingSoon(
            R.id.adminCommRow1,
            R.id.adminCommRow2,
            R.id.adminCommRow3,
            R.id.adminCommRow4
        )
    }

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.COMMANDES)
    }

    private fun setupTopBar() {
        findViewById<View?>(R.id.adminCommandesIvBack)?.setOnClickListener {
            navigateBackToMain()
        }
        applyPressFeedback(R.id.adminCommandesIvBack)
    }
}
