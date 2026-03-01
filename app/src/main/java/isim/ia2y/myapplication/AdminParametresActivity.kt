package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class AdminParametresActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_parametres)
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.SETTINGS)
        revealViewsInOrder(
            R.id.adminParametresTopBar,
            R.id.adminParamCardBoutique,
            R.id.adminParamCardLivraison,
            R.id.adminParamCardPaiement,
            startDelayMs = 60L,
            staggerMs = 48L
        )
        applyPressFeedback(R.id.adminParamCardLivraison, R.id.adminParamCardPaiement)
        bindComingSoon(R.id.adminParamCardLivraison, R.id.adminParamCardPaiement)
    }

    private fun setupTopBar() {
        findViewById<View?>(R.id.adminParametresIvBack)?.setOnClickListener {
            finishWithMotion(isForward = false)
        }
        applyPressFeedback(R.id.adminParametresIvBack)
    }
}
