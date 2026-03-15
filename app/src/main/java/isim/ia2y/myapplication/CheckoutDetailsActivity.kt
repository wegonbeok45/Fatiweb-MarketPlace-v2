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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// Cette classe organise cette partie de l'app.
class CheckoutDetailsActivity : AppCompatActivity() {

    private val EXPRESS_FEE = 12.500

    /** true = Standard selected, false = Express selected */
    private var isStandardSelected = true
    
    // Payment State
    private var currentStep = 1
    private var selectedPaymentMethod = PaymentMethod.CARD // Default
    private var isUsingSavedCard = true
    private var confirmationOrderNumber = ""
    private var confirmationDeliveryEstimate = ""
    private val appPreferences by lazy(LazyThreadSafetyMode.NONE) {
        getSharedPreferences("AppPrefs", MODE_PRIVATE)
    }

    // Cette classe organise cette partie de l'app.
    enum class PaymentMethod { CARD, EDINAR, CASH }

    // Cette fonction fait une action de cette partie de l'app.
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
        updateCheckoutChrome()
        renderStepState()
        
        revealViewsInOrder(
            R.id.layoutCheckoutTopBar,
            R.id.scrollCheckoutContent,
            R.id.layoutCheckoutBottomBar
        )
        
        // System back button handling
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            // Cette fonction fait une action de cette partie de l'app.
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
            R.id.cardPayCard,
            R.id.cardPayEdinar,
            R.id.cardPayCash,
            R.id.btnCheckoutContinue
        )
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun setupActions() {
        // Back navigation
        findViewById<View>(R.id.tvCheckoutBack)?.setOnClickListener {
            handleBackNavigation()
        }

        // Modify address placeholder
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
    
    // Cette fonction fait une action de cette partie de l'app.
    private fun confirmOrder() {
        if (selectedPaymentMethod == PaymentMethod.CARD) {
            val hasSavedCard = appPreferences.getBoolean("has_saved_card", false)
            if (isUsingSavedCard && hasSavedCard) {
                saveOrderAndProceed()
            } else {
                val switchSave = findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchSaveCard)
                if (switchSave?.isChecked == true) {
                    appPreferences.edit().putBoolean("has_saved_card", true).apply()
                }
                saveOrderAndProceed()
            }
        } else {
            saveOrderAndProceed()
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun saveOrderAndProceed() {
        val uid = FirebaseAuthManager.currentUser?.uid ?: "anonymous_${UUID.randomUUID()}"
        val cart = CartStore.getCart(this)
        if (cart.isEmpty()) {
            showMotionSnackbar("Votre panier est vide.")
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
        
        val loadingOverlay = findViewById<View>(R.id.layoutLottieLoading)
        loadingOverlay?.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.Main) {
            val result = withContext(Dispatchers.IO) {
                kotlin.runCatching { FirestoreService.saveOrder(uid, order) }
            }
            btnContinue?.isEnabled = true
            btnContinue?.text = originalText
            loadingOverlay?.visibility = View.GONE

            result.onSuccess {
                transitionToStep3()
            }.onFailure { e ->
                showMotionSnackbar("Erreur de validation : ${e.localizedMessage}")
            }
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun transitionToStep2() {
        if (currentStep == 2) return
        currentStep = 2

        val layoutStep1 = findViewById<View>(R.id.layoutStep1Content)
        val layoutStep2 = findViewById<View>(R.id.layoutStep2Content)
        updateCheckoutChrome()
        renderStepState()

        // Crossfade Animation
        layoutStep2?.visibility = View.VISIBLE
        layoutStep2?.alpha = 0f

        layoutStep1?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
            layoutStep1.visibility = View.GONE
            layoutStep2?.animate()?.alpha(1f)?.setDuration(300)?.start()
        }?.start()
        
        applyPaymentSelection()
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun transitionToStep3() {
        if (currentStep == 3) return
        currentStep = 3

        val layoutStep2 = findViewById<View>(R.id.layoutStep2Content)
        val layoutStep3 = findViewById<View>(R.id.layoutStep3Content)
        val bottomBar = findViewById<View>(R.id.layoutCheckoutBottomBar)
        updateCheckoutChrome()
        renderStepState()

        // Populate Step 3 Items Summary
        val container = findViewById<LinearLayout>(R.id.layoutStep3Items)
        container?.removeAllViews()
        val cart = CartStore.getCart(this)
        val inflater = LayoutInflater.from(this)

        cart.forEach { (id, qty) ->
            val product = ProductCatalog.byId(id) ?: return@forEach
            val itemView = inflater.inflate(R.layout.item_confirmation_product, container, false)
            
            itemView.findViewById<ImageView>(R.id.ivConfirmItemImage)?.loadCatalogImage(product.imageRes)
            itemView.findViewById<TextView>(R.id.tvConfirmItemName)?.text = product.title
            itemView.findViewById<TextView>(R.id.tvConfirmItemDetails)?.text = "Qté : $qty • ${product.subtitle}"
            itemView.findViewById<TextView>(R.id.tvConfirmItemPrice)?.text = formatDt(product.price * qty).replace(" ", "\n")
            
            container?.addView(itemView)
        }

        // Update Total Paid to reflect exact shipping choice
        val subtotal = CartStore.subtotal(this)
        val finalTotal = subtotal + if (isStandardSelected) CartStore.LIVRAISON_FEE else EXPRESS_FEE
        findViewById<TextView>(R.id.tvConfirmationTotal)?.text = formatDt(finalTotal)
        ensureConfirmationMetadata()
        findViewById<TextView>(R.id.tvConfirmationOrderNumber)?.text = confirmationOrderNumber
        findViewById<TextView>(R.id.tvConfirmationEta)?.text = confirmationDeliveryEstimate

        // Clear Cart only after successful order submission
        CartStore.clear(this)

        // Buttons in Step 3
        findViewById<View>(R.id.btnTrackOrder)?.setOnClickListener {
            showMotionSnackbar("Le suivi détaillé sera disponible très bientôt.")
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

    // Cette fonction fait une action de cette partie de l'app.
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
            card.findViewById<ImageView>(R.id.ivThumbnail)?.loadCatalogImage(product.imageRes)
            tray?.addView(card)
        }

        // 3. Summary
        updateSummary()
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun updateSummary() {
        val subtotal = CartStore.subtotal(this)
        val shipping = if (isStandardSelected) CartStore.LIVRAISON_FEE else EXPRESS_FEE
        val total = subtotal + shipping

        findViewById<TextView>(R.id.tvCheckoutSubtotal)?.text = formatDt(subtotal)
        
        val tvShippingLabel = findViewById<TextView>(R.id.tvCheckoutShippingLabel)
        val tvShippingValue = findViewById<TextView>(R.id.tvCheckoutShippingValue)
        
        if (isStandardSelected) {
            tvShippingLabel?.text = "Livraison standard"
            tvShippingValue?.text = if (CartStore.LIVRAISON_FEE <= 0.0) "Gratuite" else formatDt(CartStore.LIVRAISON_FEE)
        } else {
            tvShippingLabel?.text = "Livraison express"
            tvShippingValue?.text = formatDt(EXPRESS_FEE)
        }

        findViewById<TextView>(R.id.tvCheckoutTotal)?.text = formatDt(total)
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun applyDeliverySelection() {
        val cardStandard = findViewById<MaterialCardView>(R.id.cardDeliveryStandard)
        val cardExpress  = findViewById<MaterialCardView>(R.id.cardDeliveryExpress)
        val checkStandard = findViewById<View>(R.id.ivStandardCheck)
        val radioExpress  = findViewById<View>(R.id.ivExpressRadio)

        val colorSelected = ContextCompat.getColor(this, R.color.colorPrimary)
        val colorUnselected = ContextCompat.getColor(this, R.color.colorBorderLight)
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

    // Cette fonction fait une action de cette partie de l'app.
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

        val colorSelected = ContextCompat.getColor(this, R.color.colorPrimary)
        val colorUnselected = ContextCompat.getColor(this, R.color.colorBorderLight)
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
                
                val hasSavedCard = appPreferences.getBoolean("has_saved_card", false)
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

    // Cette fonction fait une action de cette partie de l'app.
    private fun handleBackNavigation() {
        when (currentStep) {
            3 -> transitionBackToStep2()
            2 -> transitionBackToStep1()
            else -> finishWithMotion()
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun transitionBackToStep1() {
        if (currentStep == 1) return
        currentStep = 1

        val layoutStep1 = findViewById<View>(R.id.layoutStep1Content)
        val layoutStep2 = findViewById<View>(R.id.layoutStep2Content)
        updateCheckoutChrome()
        renderStepState()

        // Crossfade Animation
        layoutStep1?.visibility = View.VISIBLE
        layoutStep1?.alpha = 0f

        layoutStep2?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
            layoutStep2.visibility = View.GONE
            layoutStep1?.animate()?.alpha(1f)?.setDuration(300)?.start()
        }?.start()
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun transitionBackToStep2() {
        if (currentStep == 2) return
        currentStep = 2

        val layoutStep2 = findViewById<View>(R.id.layoutStep2Content)
        val layoutStep3 = findViewById<View>(R.id.layoutStep3Content)
        val bottomBar = findViewById<View>(R.id.layoutCheckoutBottomBar)
        updateCheckoutChrome()
        renderStepState()

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

    private fun updateCheckoutChrome() {
        val title = findViewById<TextView>(R.id.tvCheckoutTitle)
        val btnContinue = findViewById<TextView>(R.id.btnCheckoutContinue)
        when (currentStep) {
            1 -> {
                title?.text = "Détails de la commande"
                btnContinue?.text = "Continuer vers le paiement"
            }
            2 -> {
                title?.text = "Méthode de paiement"
                btnContinue?.text = "Confirmer la commande"
            }
            3 -> {
                title?.text = "Confirmation"
            }
        }
    }

    private fun renderStepState() {
        updateStepIndicator(stepNumber = 1, isActive = currentStep == 1, isComplete = currentStep > 1)
        updateStepIndicator(stepNumber = 2, isActive = currentStep == 2, isComplete = currentStep > 2)
        updateStepIndicator(stepNumber = 3, isActive = currentStep == 3, isComplete = false)

        findViewById<View>(R.id.lineStep1to2)?.setBackgroundColor(
            ContextCompat.getColor(this, if (currentStep >= 2) R.color.colorPrimary else R.color.colorBorderLight)
        )
        findViewById<View>(R.id.lineStep2to3)?.setBackgroundColor(
            ContextCompat.getColor(this, if (currentStep >= 3) R.color.colorPrimary else R.color.colorBorderLight)
        )
    }

    private fun updateStepIndicator(stepNumber: Int, isActive: Boolean, isComplete: Boolean) {
        val bgId = when (stepNumber) {
            1 -> R.id.bgStep1
            2 -> R.id.bgStep2
            else -> R.id.bgStep3
        }
        val textId = when (stepNumber) {
            1 -> R.id.tvStep1
            2 -> R.id.tvStep2
            else -> R.id.tvStep3
        }
        findViewById<View>(bgId)?.setBackgroundResource(
            if (isActive || isComplete) R.drawable.bg_checkout_step_active else R.drawable.bg_checkout_step_inactive
        )
        findViewById<TextView>(textId)?.apply {
            text = stepNumber.toString()
            setTextColor(
                ContextCompat.getColor(
                    this@CheckoutDetailsActivity,
                    when {
                        isActive -> R.color.colorOnSurface
                        isComplete -> R.color.colorPrimary
                        else -> R.color.profile_text_muted
                    }
                )
            )
            setTypeface(null, if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun ensureConfirmationMetadata() {
        if (confirmationOrderNumber.isBlank()) {
            confirmationOrderNumber = buildOrderNumber()
        }
        confirmationDeliveryEstimate = buildDeliveryEstimate()
    }

    private fun buildOrderNumber(): String {
        val suffix = (System.currentTimeMillis() % 1_000_000).toString().padStart(6, '0')
        return "#FW-$suffix"
    }

    private fun buildDeliveryEstimate(): String {
        val today = LocalDate.now()
        val start = if (isStandardSelected) today.plusDays(3) else today.plusDays(1)
        val end = if (isStandardSelected) today.plusDays(5) else today.plusDays(2)
        val formatter = DateTimeFormatter.ofPattern("d MMM", Locale.FRENCH)
        return if (start.month == end.month) {
            "${start.dayOfMonth} - ${end.format(formatter)}"
        } else {
            "${start.format(formatter)} - ${end.format(formatter)}"
        }
    }
}

