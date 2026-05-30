package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import isim.ia2y.myapplication.ui.state.StateRenderer
import isim.ia2y.myapplication.ui.state.UiState
import kotlinx.coroutines.launch

class VendorShopProfileActivity : AppCompatActivity() {

    private var initial: VendorShopService.ShopProfile = VendorShopService.ShopProfile()
    private var roleVerified = false
    private lateinit var state: StateRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_vendor_shop_profile)
        applyInsets()

        findViewById<View>(R.id.vendorProfileBack)?.setOnClickListener { handleClose() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleClose()
        })

        findViewById<MaterialButton>(R.id.vendorProfileSave)?.setOnClickListener { save() }

        state = StateRenderer(
            loadingView = findViewById(R.id.vendorProfileLoading),
            emptyView = null,
            errorView = findViewById(R.id.vendorProfileError),
            dataView = findViewById(R.id.vendorProfileForm),
        ).also {
            it.bindError(
                titleRes = R.string.ms_error_default_title,
                subtitleRes = R.string.vendor_profile_load_failed,
                onRetry = { load() },
            )
        }
        state.render(UiState.Loading)

        lifecycleScope.launch {
            if (requireAdminOrVendeurRole() == null) return@launch
            roleVerified = true
            load()
        }
    }

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.vendorProfileAppBar)) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
    }

    private fun load() {
        val uid = FirebaseAuthManager.currentUser?.uid
        if (uid.isNullOrBlank()) {
            state.render(UiState.Error())
            return
        }
        state.render(UiState.Loading)
        lifecycleScope.launch {
            runCatching { VendorShopService.fetchShop(uid) }
                .onSuccess { profile ->
                    initial = profile
                    bindForm(profile)
                    state.render(UiState.Data(profile))
                }
                .onFailure { state.render(UiState.Error(cause = it)) }
        }
    }

    private fun bindForm(p: VendorShopService.ShopProfile) {
        edit(R.id.vendorProfileShopName).setText(p.shopName)
        edit(R.id.vendorProfileShopBio).setText(p.shopBio)
        edit(R.id.vendorProfileLogoUrl).setText(p.shopLogoUrl)
        edit(R.id.vendorProfileBannerUrl).setText(p.shopBannerUrl)
        edit(R.id.vendorProfileShippingFee).setText(
            if (p.shippingFeeDt > 0.0) formatDt(p.shippingFeeDt) else ""
        )
        edit(R.id.vendorProfileZones).setText(p.deliveryZones.joinToString(", "))
        edit(R.id.vendorProfileHours).setText(p.operatingHours)
        edit(R.id.vendorProfileEmail).setText(p.email)
        edit(R.id.vendorProfilePhone).setText(p.phone)
    }

    private fun snapshot(): VendorShopService.ShopProfile = VendorShopService.ShopProfile(
        shopName = text(R.id.vendorProfileShopName),
        shopBio = text(R.id.vendorProfileShopBio),
        shopLogoUrl = text(R.id.vendorProfileLogoUrl),
        shopBannerUrl = text(R.id.vendorProfileBannerUrl),
        operatingHours = text(R.id.vendorProfileHours),
        shippingFeeDt = text(R.id.vendorProfileShippingFee)
            .replace(",", ".")
            .toDoubleOrNull() ?: 0.0,
        deliveryZones = text(R.id.vendorProfileZones)
            .split(",").map { it.trim() }.filter { it.isNotBlank() },
        email = initial.email,
        phone = initial.phone,
    )

    private fun save() {
        val uid = FirebaseAuthManager.currentUser?.uid
        if (uid.isNullOrBlank()) return
        val payload = snapshot()
        val saveButton = findViewById<MaterialButton>(R.id.vendorProfileSave)
        saveButton?.isEnabled = false
        lifecycleScope.launch {
            runCatching { VendorShopService.saveShop(uid, payload) }
                .onSuccess {
                    initial = payload
                    showMotionSnackbar(getString(R.string.vendor_profile_save_success))
                }
                .onFailure {
                    showMotionSnackbar(getString(R.string.vendor_profile_save_failed))
                }
            saveButton?.isEnabled = true
        }
    }

    private fun handleClose() {
        if (snapshot() == initial) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.vendor_profile_unsaved_title)
            .setMessage(R.string.vendor_profile_unsaved_message)
            .setNegativeButton(R.string.ms_action_cancel, null)
            .setPositiveButton(R.string.vendor_profile_unsaved_discard) { _, _ -> finish() }
            .show()
    }

    private fun edit(id: Int): TextInputEditText = findViewById(id)
    private fun text(id: Int): String = edit(id).text?.toString()?.trim().orEmpty()
}
