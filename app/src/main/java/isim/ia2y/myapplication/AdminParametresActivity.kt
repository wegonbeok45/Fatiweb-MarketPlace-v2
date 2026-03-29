package isim.ia2y.myapplication

import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch

class AdminParametresActivity : AppCompatActivity() {

    private var currentConfig = FirestoreService.CommerceConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_parametres)
        setupAdminWindowInsets(R.id.adminParametresAppBar)
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.SETTINGS)

        lifecycleScope.launch {
            if (!requireAdminRole()) return@launch

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
            applyPressFeedback(R.id.adminParamCardLivraison)
            configurePaymentSection()
            setupDeliveryEdits()
            loadDeliveryConfig()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.SETTINGS)
    }

    private fun configurePaymentSection() {
        findViewById<View>(R.id.adminParamCardPaiement)?.apply {
            alpha = 0.65f
            setOnClickListener {
                showMotionSnackbar(getString(R.string.admin_payments_online_hint))
            }
        }
    }

    private fun loadDeliveryConfig() {
        lifecycleScope.launch {
            runCatching { FirestoreService.fetchCommerceConfig() }
                .onSuccess { config ->
                    currentConfig = config
                    bindDeliveryConfig(config)
                }
                .onFailure {
                    bindDeliveryConfig(currentConfig)
                    showMotionSnackbar(getString(R.string.admin_shipping_load_failed))
                }
        }
    }

    private fun bindDeliveryConfig(config: FirestoreService.CommerceConfig) {
        findViewById<TextView>(R.id.adminParamLivrStandardPrice)?.text = formatDt(config.standardShippingFee)
        findViewById<TextView>(R.id.adminParamLivrExpressPrice)?.text = formatDt(config.expressShippingFee)
    }

    private fun setupDeliveryEdits() {
        val standardGroup = findViewById<View>(R.id.adminParamLivrStandardEditGroup)
        val expressGroup = findViewById<View>(R.id.adminParamLivrExpressEditGroup)

        applyPressFeedback(R.id.adminParamLivrStandardEditGroup, R.id.adminParamLivrExpressEditGroup)

        standardGroup?.setOnClickListener {
            showEditPriceDialog(currentConfig.standardShippingFee, "Standard") { newPrice ->
                saveDeliveryConfig(currentConfig.copy(standardShippingFee = newPrice))
            }
        }

        expressGroup?.setOnClickListener {
            showEditPriceDialog(currentConfig.expressShippingFee, "Express") { newPrice ->
                saveDeliveryConfig(currentConfig.copy(expressShippingFee = newPrice))
            }
        }
    }

    private fun saveDeliveryConfig(config: FirestoreService.CommerceConfig) {
        lifecycleScope.launch {
            runCatching { FirestoreService.saveCommerceConfig(config) }
                .onSuccess { savedConfig ->
                    currentConfig = savedConfig
                    bindDeliveryConfig(savedConfig)
                    showMotionSnackbar(getString(R.string.admin_shipping_updated))
                }
                .onFailure {
                    showMotionSnackbar(getString(R.string.admin_shipping_save_failed))
                }
        }
    }

    private fun showEditPriceDialog(
        currentPrice: Double,
        method: String,
        onSave: (Double) -> Unit
    ) {
        val dialog = Dialog(this, R.style.ThemeOverlay_MyApp_Dialog)
        dialog.setContentView(R.layout.dialog_admin_edit_price)
        dialog.window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        val tvTitle = dialog.findViewById<TextView>(R.id.tvEditPriceTitle)
        val etPrice = dialog.findViewById<TextInputEditText>(R.id.etEditPrice)
        val btnCancel = dialog.findViewById<MaterialButton>(R.id.btnEditPriceCancel)
        val btnSave = dialog.findViewById<MaterialButton>(R.id.btnEditPriceSave)

        val initialValue = String.format(java.util.Locale.US, "%.3f", currentPrice)
        tvTitle?.text = getString(R.string.admin_delivery_price_title, method)
        etPrice?.setText(initialValue)
        etPrice?.setSelection(initialValue.length)

        btnCancel?.setOnClickListener {
            dialog.dismiss()
        }

        btnSave?.setOnClickListener {
            val newPrice = etPrice?.text.toString().trim().replace(',', '.').toDoubleOrNull()
            if (newPrice == null || newPrice < 0.0) {
                showToast(getString(R.string.admin_invalid_price))
                return@setOnClickListener
            }
            onSave(newPrice)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun setupTopBar() {
        setupAdminTopBar(getString(R.string.admin_title_settings))
    }
}
