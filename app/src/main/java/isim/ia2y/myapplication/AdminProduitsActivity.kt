package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity

class AdminProduitsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_produits)
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.PRODUITS)
        revealViewsInOrder(
            R.id.adminProduitsTopBar,
            R.id.adminProduitsTvHeader,
            R.id.adminProduitsCard,
            startDelayMs = 60L,
            staggerMs = 48L
        )
        applyPressFeedback(
            R.id.adminProduitRow1,
            R.id.adminProduitRow2,
            R.id.adminProduitRow3
        )
        bindComingSoon(
            R.id.adminProduitRow1,
            R.id.adminProduitRow2,
            R.id.adminProduitRow3
        )
    }

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.PRODUITS)
    }

    private fun setupTopBar() {
        findViewById<View?>(R.id.adminProduitsIvBack)?.setOnClickListener {
            navigateBackToMain()
        }
        applyPressFeedback(R.id.adminProduitsIvBack)
    }
}
