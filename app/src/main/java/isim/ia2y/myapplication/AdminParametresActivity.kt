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

import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class AdminParametresActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_parametres)
        setupWindowInsets()
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.SETTINGS)

        lifecycleScope.launch {
            val uid = FirebaseAuthManager.currentUser?.uid
            if (uid == null || FirestoreService.fetchUserRole(uid) != "admin") {
                finish()
                return@launch
            }

            if (savedInstanceState == null) {
                revealViewsInOrder(
                    R.id.adminParametresTopBar,
                    R.id.adminParamCardBoutique,
                    R.id.adminParamCardLivraison,
                    R.id.adminParamCardPaiement,
                    startDelayMs = 60L,
                    staggerMs = 48L
                )
            }
            applyPressFeedback(R.id.adminParamCardLivraison, R.id.adminParamCardPaiement)
            bindComingSoon(R.id.adminParamCardPaiement)
            setupDeliveryEdits()
        }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminParametresAppBar)) { view, insets ->
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

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.SETTINGS)
    }

    private fun setupDeliveryEdits() {
        val standardGroup = findViewById<View>(R.id.adminParamLivrStandardEditGroup)
        val expressGroup = findViewById<View>(R.id.adminParamLivrExpressEditGroup)
        val standardPriceText = findViewById<TextView>(R.id.adminParamLivrStandardPrice)
        val expressPriceText = findViewById<TextView>(R.id.adminParamLivrExpressPrice)

        applyPressFeedback(R.id.adminParamLivrStandardEditGroup, R.id.adminParamLivrExpressEditGroup)

        standardGroup?.setOnClickListener {
            val currentRaw = standardPriceText?.text.toString().replace(" DT", "").trim()
            showEditPriceDialog(currentRaw, "Standard") { newPrice ->
                standardPriceText?.text = "$newPrice DT"
                showMotionSnackbar("Prix de livraison standard mis à jour")
            }
        }

        expressGroup?.setOnClickListener {
            val currentRaw = expressPriceText?.text.toString().replace(" DT", "").trim()
            showEditPriceDialog(currentRaw, "Express") { newPrice ->
                expressPriceText?.text = "$newPrice DT"
                showMotionSnackbar("Prix de livraison express mis à jour")
            }
        }
    }

    private fun showEditPriceDialog(currentPrice: String, method: String, onSave: (String) -> Unit) {
        val dialog = Dialog(this, R.style.ThemeOverlay_MyApp_Dialog)
        dialog.setContentView(R.layout.dialog_admin_edit_price)
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        
        val tvTitle = dialog.findViewById<TextView>(R.id.tvEditPriceTitle)
        val etPrice = dialog.findViewById<TextInputEditText>(R.id.etEditPrice)
        val btnCancel = dialog.findViewById<MaterialButton>(R.id.btnEditPriceCancel)
        val btnSave = dialog.findViewById<MaterialButton>(R.id.btnEditPriceSave)

        tvTitle?.text = "Prix de livraison $method"
        etPrice?.setText(currentPrice)
        etPrice?.setSelection(currentPrice.length)

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        btnSave?.setOnClickListener {
            val newPrice = etPrice?.text.toString().trim()
            if (newPrice.isNotEmpty()) {
                onSave(newPrice)
                dialog.dismiss()
            } else {
                showToast("Veuillez entrer un prix valide")
            }
        }

        dialog.show()
    }

    private fun setupTopBar() {
        findViewById<View?>(R.id.adminParametresIvBack)?.setOnClickListener {
            navigateBackToMain()
        }
        applyPressFeedback(R.id.adminParametresIvBack)
    }
}
