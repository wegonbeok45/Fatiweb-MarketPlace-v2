package isim.ia2y.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CheckoutDetailsActivity : AppCompatActivity() {

    private val EXPRESS_FEE = 12.500

    /** true = Standard selected, false = Express selected */
    private var isStandardSelected = true
    
    // Payment State
    private var currentStep = 1
    private var selectedPaymentMethod = PaymentMethod.CARD // Default
    private var isUsingSavedCard = true

    enum class PaymentMethod { CARD, EDINAR, CASH }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_checkout_details)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.layoutCheckoutRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        setupActions()
        bindDynamicData()
        applyDeliverySelection()
        
        revealViewsInOrder(
            R.id.layoutCheckoutTopBar,
            R.id.scrollCheckoutContent,
            R.id.layoutCheckoutBottomBar
        )
        
        // System back button handling
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        })

        applyPressFeedback(
            R.id.tvCheckoutBack,
            R.id.tvCheckoutModifyAddress,
            R.id.cardCheckoutAddress,
            R.id.cardDeliveryStandard,
            R.id.cardDeliveryExpress,
            R.id.btnCheckoutContinue
        )
    }

    private fun setupActions() {
        // Back navigation
        findViewById<View>(R.id.tvCheckoutBack)?.setOnClickListener {
            handleBackNavigation()
        }

        // Modify address — placeholder
        findViewById<View>(R.id.tvCheckoutModifyAddress)?.setOnClickListener {
            showMotionSnackbar(getString(R.string.coming_soon))
        }

        // Delivery method toggle
        findViewById<View>(R.id.cardDeliveryStandard)?.setOnClickListener {
            if (!isStandardSelected) {
                isStandardSelected = true
                applyDeliverySelection()
            }
        }
        findViewById<View>(R.id.cardDeliveryExpress)?.setOnClickListener {
            if (isStandardSelected) {
                isStandardSelected = false
                applyDeliverySelection()
            }
        }

        // Payment Method toggles
        findViewById<View>(R.id.cardPayCard)?.setOnClickListener {
            selectedPaymentMethod = PaymentMethod.CARD
            applyPaymentSelection()
        }
        
        findViewById<View>(R.id.tvAddNewCard)?.setOnClickListener {
            isUsingSavedCard = false
            applyPaymentSelection()
        }
        findViewById<View>(R.id.cardPayEdinar)?.setOnClickListener {
            selectedPaymentMethod = PaymentMethod.EDINAR
            applyPaymentSelection()
        }
        findViewById<View>(R.id.cardPayCash)?.setOnClickListener {
            selectedPaymentMethod = PaymentMethod.CASH
            applyPaymentSelection()
        }

        // CTA
        findViewById<View>(R.id.btnCheckoutContinue)?.setOnClickListener {
            if (currentStep == 1) {
                transitionToStep2()
            } else {
                confirmOrder()
            }
        }
    }
    
    private fun confirmOrder() {
        if (selectedPaymentMethod == PaymentMethod.CARD) {
            val hasSavedCard = getSharedPreferences("AppPrefs", android.content.Context.MODE_PRIVATE).getBoolean("has_saved_card", false)
            if (isUsingSavedCard && hasSavedCard) {
                saveOrderAndProceed()
            } else {
                val switchSave = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchSaveCard)
                if (switchSave?.isChecked == true) {
                    getSharedPreferences("AppPrefs", android.content.Context.MODE_PRIVATE)
                        .edit().putBoolean("has_saved_card", true).apply()
                }
                saveOrderAndProceed()
            }
        } else {
            saveOrderAndProceed()
        }
    }

    private fun saveOrderAndProceed() {
        val uid = FirebaseAuthManager.currentUser?.uid ?: "anonymous_${java.util.UUID.randomUUID()}"
        val cart = CartStore.getCart(this)
        if (cart.isEmpty()) {
            showMotionSnackbar("Votre panier est vide")
            return
        }
        val subtotal = CartStore.subtotal(this)
        val shippingFee = if (isStandardSelected) CartStore.LIVRAISON_FEE else EXPRESS_FEE
        val order = AppOrder(
            items         = cart,
            subtotal      = subtotal,
            shippingFee   = shippingFee,
            total         = subtotal + shippingFee,
            deliveryType  = if (isStandardSelected) "standard" else "express",
            paymentMethod = when (selectedPaymentMethod) {
                PaymentMethod.CARD   -> "card"
                PaymentMethod.EDINAR -> "edinar"
                PaymentMethod.CASH   -> "cash"
            }
        )

        val btnContinue = findViewById<TextView>(R.id.btnCheckoutContinue)
        val originalText = btnContinue?.text
        btnContinue?.text = "Enregistrement..."
        btnContinue?.isEnabled = false

        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                kotlin.runCatching { FirestoreService.saveOrder(uid, order) }
            }
            btnContinue?.isEnabled = true
            btnContinue?.text = originalText

            result.onSuccess {
                transitionToStep3()
            }.onFailure { e ->
                showMotionSnackbar("Erreur: ${e.localizedMessage}")
            }
        }
    }

    private fun transitionToStep2() {
        if (currentStep == 2) return
        currentStep = 2

        val layoutStep1 = findViewById<View>(R.id.layoutStep1Content)
        val layoutStep2 = findViewById<View>(R.id.layoutStep2Content)
        val tvTitle = findViewById<TextView>(R.id.tvCheckoutTitle)
        val btnContinue = findViewById<TextView>(R.id.btnCheckoutContinue)

        // Title and Button
        tvTitle?.text = "Méthode de Paiement"
        btnContinue?.text = "Confirmer la commande"

        // Step Indicators
        findViewById<View>(R.id.bgStep1)?.setBackgroundResource(R.drawable.bg_checkout_step_active)
        findViewById<TextView>(R.id.tvStep1)?.apply {
            text = "1"
            setTextColor(android.graphics.Color.parseColor("#CDAA7D")) // Faded gold
            setTypeface(null, android.graphics.Typeface.NORMAL)
        }

        findViewById<View>(R.id.bgStep2)?.setBackgroundResource(R.drawable.bg_checkout_step_active)
        findViewById<TextView>(R.id.tvStep2)?.apply {
            text = "2"
            setTextColor(android.graphics.Color.parseColor("#111111")) // Active black
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        findViewById<View>(R.id.lineStep1to2)?.setBackgroundColor(android.graphics.Color.parseColor("#CDAA7D"))

        // Crossfade Animation
        layoutStep2?.visibility = View.VISIBLE
        layoutStep2?.alpha = 0f

        layoutStep1?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
            layoutStep1.visibility = View.GONE
            layoutStep2?.animate()?.alpha(1f)?.setDuration(300)?.start()
        }?.start()
        
        applyPaymentSelection()
    }

    private fun transitionToStep3() {
        if (currentStep == 3) return
        currentStep = 3

        val layoutStep2 = findViewById<View>(R.id.layoutStep2Content)
        val layoutStep3 = findViewById<View>(R.id.layoutStep3Content)
        val tvTitle = findViewById<TextView>(R.id.tvCheckoutTitle)
        val bottomBar = findViewById<View>(R.id.layoutCheckoutBottomBar)

        // Title
        tvTitle?.text = "Confirmation"

        // Step Indicators
        findViewById<View>(R.id.bgStep1)?.setBackgroundResource(R.drawable.bg_checkout_step_active)
        findViewById<TextView>(R.id.tvStep1)?.apply {
            text = "1"
            setTextColor(android.graphics.Color.parseColor("#CDAA7D")) // Faded gold
            setTypeface(null, android.graphics.Typeface.NORMAL)
        }

        findViewById<View>(R.id.bgStep2)?.setBackgroundResource(R.drawable.bg_checkout_step_active)
        findViewById<TextView>(R.id.tvStep2)?.apply {
            text = "2"
            setTextColor(android.graphics.Color.parseColor("#CDAA7D")) // Faded gold
            setTypeface(null, android.graphics.Typeface.NORMAL)
        }

        findViewById<View>(R.id.bgStep3)?.setBackgroundResource(R.drawable.bg_checkout_step_active)
        findViewById<TextView>(R.id.tvStep3)?.apply {
            text = "3"
            setTextColor(android.graphics.Color.parseColor("#111111")) // Active black
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        findViewById<View>(R.id.lineStep2to3)?.setBackgroundColor(android.graphics.Color.parseColor("#CDAA7D"))

        // Populate Step 3 Items Summary
        val container = findViewById<LinearLayout>(R.id.layoutStep3Items)
        container?.removeAllViews()
        val cart = CartStore.getCart(this)
        val inflater = LayoutInflater.from(this)

        cart.forEach { (id, qty) ->
            val product = ProductCatalog.byId(id) ?: return@forEach
            val itemView = inflater.inflate(R.layout.item_confirmation_product, container, false)
            
            itemView.findViewById<ImageView>(R.id.ivConfirmItemImage)?.setImageResource(product.imageRes)
            itemView.findViewById<TextView>(R.id.tvConfirmItemName)?.text = product.title
            itemView.findViewById<TextView>(R.id.tvConfirmItemDetails)?.text = "Qté: $qty • ${product.subtitle}"
            itemView.findViewById<TextView>(R.id.tvConfirmItemPrice)?.text = formatDt(product.price * qty).replace(" ", "\n")
            
            container?.addView(itemView)
        }

        // Update Total Paid to reflect exact shipping choice
        val subtotal = CartStore.subtotal(this)
        val finalTotal = subtotal + if (isStandardSelected) CartStore.LIVRAISON_FEE else EXPRESS_FEE
        findViewById<TextView>(R.id.tvConfirmationTotal)?.text = formatDt(finalTotal)

        // Clear Cart only after successful order submission
        CartStore.clear(this)

        // Buttons in Step 3
        findViewById<View>(R.id.btnTrackOrder)?.setOnClickListener {
            showMotionSnackbar("Suivi de commande indisponible pour le moment.")
        }
        findViewById<View>(R.id.btnBackHome)?.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }

        // Crossfade Animation
        layoutStep3?.visibility = View.VISIBLE
        layoutStep3?.alpha = 0f

        // Hide bottom bar
        bottomBar?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
            bottomBar.visibility = View.GONE
        }?.start()

        layoutStep2?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
            layoutStep2.visibility = View.GONE
            layoutStep3?.animate()?.alpha(1f)?.setDuration(300)?.start()
        }?.start()
    }

    private fun bindDynamicData() {
        // 1. Address
        val addressList = AddressBookStore.getAddresses(this)
        val address = addressList.firstOrNull() ?: "Tunis, Tunisie"
        
        val tvName = findViewById<TextView>(R.id.tvCheckoutAddressName)
        val tvLine1 = findViewById<TextView>(R.id.tvCheckoutAddressLine1)
        
        tvName?.text = getString(R.string.user_guest_name)
        tvLine1?.text = address
        findViewById<TextView>(R.id.tvCheckoutAddressLine2)?.visibility = View.GONE
        findViewById<TextView>(R.id.tvCheckoutAddressPhone)?.visibility = View.GONE

        // Auto-resolve if it's the default
        if (address == "Tunis, Tunisie" && LocationHelper.hasPermission(this)) {
            LocationHelper.resolveCurrentLocation(this) { resolved ->
                tvLine1?.text = resolved
            }
        }

        // 2. Articles Thumbnails
        val tray = findViewById<LinearLayout>(R.id.layoutCheckoutArticles)
        tray?.removeAllViews()
        val cart = CartStore.getCart(this)
        val items = ProductCatalog.orderedFavorites(cart.keys)
        
        val inflater = LayoutInflater.from(this)
        items.forEach { product ->
            val card = inflater.inflate(R.layout.item_checkout_thumbnail, tray, false) as MaterialCardView
            card.findViewById<ImageView>(R.id.ivThumbnail)?.setImageResource(product.imageRes)
            tray?.addView(card)
        }

        // 3. Summary
        updateSummary()
    }

    private fun updateSummary() {
        val subtotal = CartStore.subtotal(this)
        val shipping = if (isStandardSelected) CartStore.LIVRAISON_FEE else EXPRESS_FEE
        val total = subtotal + shipping

        findViewById<TextView>(R.id.tvCheckoutSubtotal)?.text = formatDt(subtotal)
        
        val tvShippingLabel = findViewById<TextView>(R.id.tvCheckoutShippingLabel)
        val tvShippingValue = findViewById<TextView>(R.id.tvCheckoutShippingValue)
        
        if (isStandardSelected) {
            tvShippingLabel?.text = "Frais de port (Standard)"
            tvShippingValue?.text = formatDt(CartStore.LIVRAISON_FEE)
        } else {
            tvShippingLabel?.text = "Frais de port (Express)"
            tvShippingValue?.text = formatDt(EXPRESS_FEE)
        }

        findViewById<TextView>(R.id.tvCheckoutTotal)?.text = formatDt(total)
    }

    private fun applyDeliverySelection() {
        val cardStandard = findViewById<MaterialCardView>(R.id.cardDeliveryStandard)
        val cardExpress  = findViewById<MaterialCardView>(R.id.cardDeliveryExpress)
        val checkStandard = findViewById<View>(R.id.ivStandardCheck)
        val radioExpress  = findViewById<View>(R.id.ivExpressRadio)

        val colorSelected = android.graphics.Color.parseColor("#CDAA7D")
        val colorUnselected = android.graphics.Color.parseColor("#EFEBE4")
        val strokeSelected = resources.getDimensionPixelSize(R.dimen.checkout_selected_stroke)
        val strokeUnselected = resources.getDimensionPixelSize(R.dimen.checkout_unselected_stroke)

        if (isStandardSelected) {
            cardStandard?.strokeColor = colorSelected
            cardStandard?.strokeWidth = strokeSelected
            checkStandard?.visibility = View.VISIBLE

            cardExpress?.strokeColor = colorUnselected
            cardExpress?.strokeWidth = strokeUnselected
            radioExpress?.visibility = View.VISIBLE
        } else {
            cardStandard?.strokeColor = colorUnselected
            cardStandard?.strokeWidth = strokeUnselected
            checkStandard?.visibility = View.GONE

            cardExpress?.strokeColor = colorSelected
            cardExpress?.strokeWidth = strokeSelected
            radioExpress?.visibility = View.GONE
        }
        
        updateSummary()
    }

    private fun applyPaymentSelection() {
        val cardPayCard = findViewById<MaterialCardView>(R.id.cardPayCard)
        val cardPayEdinar = findViewById<MaterialCardView>(R.id.cardPayEdinar)
        val cardPayCash = findViewById<MaterialCardView>(R.id.cardPayCash)

        val ivCard = findViewById<ImageView>(R.id.ivPayCardRadio)
        val ivEdinar = findViewById<ImageView>(R.id.ivPayEdinarRadio)
        val ivCash = findViewById<ImageView>(R.id.ivPayCashRadio)

        val layoutCardForm = findViewById<View>(R.id.layoutCardForm)
        val layoutSavedCard = findViewById<View>(R.id.layoutSavedCard)
        val layoutEdinarMessage = findViewById<View>(R.id.layoutEdinarMessage)

        val colorSelected = android.graphics.Color.parseColor("#CDAA7D")
        val colorUnselected = android.graphics.Color.parseColor("#EFEBE4")
        val strokeSelected = resources.getDimensionPixelSize(R.dimen.checkout_selected_stroke)
        val strokeUnselected = resources.getDimensionPixelSize(R.dimen.checkout_unselected_stroke)

        // Reset all
        arrayOf(cardPayCard, cardPayEdinar, cardPayCash).forEach {
            it?.strokeColor = colorUnselected
            it?.strokeWidth = strokeUnselected
        }
        arrayOf(ivCard, ivEdinar, ivCash).forEach {
            it?.setImageResource(R.drawable.ic_checkout_radio_empty)
        }
        layoutCardForm?.visibility = View.GONE
        layoutSavedCard?.visibility = View.GONE
        layoutEdinarMessage?.visibility = View.GONE

        // Apply selected
        when (selectedPaymentMethod) {
            PaymentMethod.CARD -> {
                cardPayCard?.strokeColor = colorSelected
                cardPayCard?.strokeWidth = strokeSelected
                ivCard?.setImageResource(R.drawable.ic_checkout_radio_filled)
                
                val hasSavedCard = getSharedPreferences("AppPrefs", android.content.Context.MODE_PRIVATE).getBoolean("has_saved_card", false)
                if (hasSavedCard && isUsingSavedCard) {
                    layoutSavedCard?.visibility = View.VISIBLE
                } else {
                    layoutCardForm?.visibility = View.VISIBLE
                }
            }
            PaymentMethod.EDINAR -> {
                cardPayEdinar?.strokeColor = colorSelected
                cardPayEdinar?.strokeWidth = strokeSelected
                ivEdinar?.setImageResource(R.drawable.ic_checkout_radio_filled)
                layoutEdinarMessage?.visibility = View.VISIBLE
            }
            PaymentMethod.CASH -> {
                cardPayCash?.strokeColor = colorSelected
                cardPayCash?.strokeWidth = strokeSelected
                ivCash?.setImageResource(R.drawable.ic_checkout_radio_filled)
            }
        }
    }

    private fun handleBackNavigation() {
        when (currentStep) {
            3 -> transitionBackToStep2()
            2 -> transitionBackToStep1()
            else -> finishWithMotion()
        }
    }

    private fun transitionBackToStep1() {
        if (currentStep == 1) return
        currentStep = 1

        val layoutStep1 = findViewById<View>(R.id.layoutStep1Content)
        val layoutStep2 = findViewById<View>(R.id.layoutStep2Content)
        val tvTitle = findViewById<TextView>(R.id.tvCheckoutTitle)
        val btnContinue = findViewById<TextView>(R.id.btnCheckoutContinue)

        // Reset Title and Button
        tvTitle?.text = "Détails de la commande"
        btnContinue?.text = "Continuer vers le paiement →"

        // Step Indicators
        findViewById<View>(R.id.bgStep1)?.setBackgroundResource(R.drawable.bg_checkout_step_active)
        findViewById<TextView>(R.id.tvStep1)?.apply {
            text = "1"
            setTextColor(android.graphics.Color.parseColor("#111111")) // Back to active black
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        findViewById<View>(R.id.bgStep2)?.setBackgroundResource(R.drawable.bg_checkout_step_inactive)
        findViewById<TextView>(R.id.tvStep2)?.apply {
            text = "2"
            setTextColor(android.graphics.Color.parseColor("#A0A0A0")) // Back to inactive grey
            setTypeface(null, android.graphics.Typeface.NORMAL)
        }
        findViewById<View>(R.id.lineStep1to2)?.setBackgroundColor(android.graphics.Color.parseColor("#EFEBE4"))

        // Crossfade Animation
        layoutStep1?.visibility = View.VISIBLE
        layoutStep1?.alpha = 0f

        layoutStep2?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
            layoutStep2.visibility = View.GONE
            layoutStep1?.animate()?.alpha(1f)?.setDuration(300)?.start()
        }?.start()
    }

    private fun transitionBackToStep2() {
        if (currentStep == 2) return
        currentStep = 2

        val layoutStep2 = findViewById<View>(R.id.layoutStep2Content)
        val layoutStep3 = findViewById<View>(R.id.layoutStep3Content)
        val tvTitle = findViewById<TextView>(R.id.tvCheckoutTitle)
        val bottomBar = findViewById<View>(R.id.layoutCheckoutBottomBar)
        val btnContinue = findViewById<TextView>(R.id.btnCheckoutContinue)

        // Reset Title
        tvTitle?.text = "Méthode de Paiement"
        btnContinue?.text = "Confirmer la commande"

        // Step Indicators
        findViewById<View>(R.id.bgStep1)?.setBackgroundResource(R.drawable.bg_checkout_step_active)
        findViewById<TextView>(R.id.tvStep1)?.apply {
            text = "1"
            setTextColor(android.graphics.Color.parseColor("#CDAA7D")) // Keep as faded gold
            setTypeface(null, android.graphics.Typeface.NORMAL)
        }

        findViewById<View>(R.id.bgStep3)?.setBackgroundResource(R.drawable.bg_checkout_step_inactive)
        findViewById<TextView>(R.id.tvStep3)?.apply {
            text = "3"
            setTextColor(android.graphics.Color.parseColor("#A0A0A0"))
            setTypeface(null, android.graphics.Typeface.NORMAL)
        }
        findViewById<TextView>(R.id.tvStep2)?.apply {
            text = "2"
            setTextColor(android.graphics.Color.parseColor("#111111")) // Back to active black
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        findViewById<View>(R.id.bgStep2)?.setBackgroundResource(R.drawable.bg_checkout_step_active)
        findViewById<View>(R.id.lineStep2to3)?.setBackgroundColor(android.graphics.Color.parseColor("#EFEBE4"))

        // Show bottom bar again
        bottomBar?.visibility = View.VISIBLE
        bottomBar?.alpha = 0f
        bottomBar?.animate()?.alpha(1f)?.setDuration(300)?.start()

        // Crossfade Animation
        layoutStep2?.visibility = View.VISIBLE
        layoutStep2?.alpha = 0f

        layoutStep3?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
            layoutStep3.visibility = View.GONE
            layoutStep2?.animate()?.alpha(1f)?.setDuration(300)?.start()
        }?.start()

        applyPaymentSelection()
    }
}
