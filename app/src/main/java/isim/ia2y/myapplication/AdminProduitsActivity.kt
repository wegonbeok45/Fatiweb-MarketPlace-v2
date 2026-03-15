package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Cette classe organise cette partie de l'app.
class AdminProduitsActivity : AppCompatActivity() {

    // Cette fonction fait une action de cette partie de l'app.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_produits)
        setupWindowInsets()
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.PRODUITS)

        lifecycleScope.launch {
            val uid = FirebaseAuthManager.currentUser?.uid
            if (uid == null || FirestoreService.fetchUserRole(uid) != "admin") {
                finish()
                return@launch
            }

            if (savedInstanceState == null) {
                revealViewsInOrder(
                    R.id.adminProduitsTopBar,
                    R.id.adminProduitsTvHeader,
                    R.id.adminProduitsCard,
                    startDelayMs = 60L,
                    staggerMs = 48L
                )
            }
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
    }

    // Cette fonction fait une action de cette partie de l'app.
    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.PRODUITS)
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun setupTopBar() {
        findViewById<View?>(R.id.adminProduitsIvBack)?.setOnClickListener {
            navigateBackToMain()
        }
        applyPressFeedback(R.id.adminProduitsIvBack)
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminProduitsAppBar)) { view, insets ->
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
}
