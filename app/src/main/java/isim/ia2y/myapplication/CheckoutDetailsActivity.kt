package isim.ia2y.myapplication

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import isim.ia2y.myapplication.databinding.ActivityCheckoutDetailsBinding

class CheckoutDetailsActivity : AppCompatActivity() {
    private companion object {
        const val TAG = "CheckoutDetails"
        const val STATE_CONFIRMATION_ORDER_NUMBER = "confirmationOrderNumber"
        const val STATE_CONFIRMATION_DELIVERY_ESTIMATE = "confirmationDeliveryEstimate"
        const val STATE_STANDARD_SHIPPING_FEE = "standardShippingFee"
        const val STATE_EXPRESS_SHIPPING_FEE = "expressShippingFee"
        const val STATE_GUEST_CHOICE_SHOWN = "guestCheckoutChoiceShown"
        const val STATE_LAST_SAVED_ORDER_ID = "lastSavedOrderId"
        const val STATE_LAST_DRAFT_ORDER_ID = "lastDraftOrderId"
        const val STATE_CURRENT_STEP = "currentStep"
        const val STATE_IS_STANDARD_SHIPPING = "isStandardShipping"
        const val MAX_NAME_LENGTH = 80
        const val MAX_PHONE_LENGTH = 24
        const val MAX_CITY_LENGTH = 80
        const val MAX_ADDRESS_LENGTH = 180
        const val MAX_NOTE_LENGTH = 240
    }

    private val viewModel: CheckoutViewModel by viewModels()
    private lateinit var binding: ActivityCheckoutDetailsBinding
    private var selectedPaymentMethod = PaymentMethod.CASH
    private var confirmationOrderNumber = ""
    private var confirmationDeliveryEstimate = ""
    private var standardShippingFee = CartStore.LIVRAISON_FEE
    private var expressShippingFee = 12.500
    private var lastSavedOrder: AppOrder? = null
    private var lastDraftOrder: AppOrder? = null
    private var guestCheckoutChoiceShown = false
    private val btnContinue: MaterialButton
        get() = binding.btnCheckoutContinue

    private val requestLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        val permanentlyDenied = LocationHelper.isPermanentlyDenied(this)
        LocationPermissionStore.markPermissionResult(this, granted, permanentlyDenied)
        Log.d("LocationFlow", if (granted) "Permission accepted" else "Permission rejected")
        if (granted) {
            fetchAndUseCheckoutLocation()
        } else if (permanentlyDenied) {
            updateCheckoutActionCard(requestFocus = true)
        }
    }

    enum class PaymentMethod { CASH }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCheckoutDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        restoreCheckoutState(savedInstanceState)
        val scrollBaseBottomPadding = binding.scrollCheckoutContent.paddingBottom
        val bottomBarBaseBottomPadding = binding.layoutCheckoutBottomBar.paddingBottom
        val extraBottomSpacing = resources.getDimensionPixelSize(R.dimen.space_24)

        ViewCompat.setOnApplyWindowInsetsListener(binding.layoutCheckoutRoot) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            binding.scrollCheckoutContent.updatePadding(
                bottom = scrollBaseBottomPadding + systemBars.bottom + extraBottomSpacing
            )
            binding.layoutCheckoutBottomBar.updatePadding(
                bottom = bottomBarBaseBottomPadding + systemBars.bottom
            )
            insets
        }

        setupActions()
        observeShippingSelection()
        observeOrderResult()
        observeUserProfile()
        bindDynamicData()
        applyPaymentSelection()
        updateCheckoutChrome()
        renderStepState()
        loadCommerceConfig()

        if (FirebaseAuthManager.currentUser == null) {
            binding.root.post { showCheckoutContinuationChoice() }
        }

        revealViewsInOrder(
            R.id.layoutCheckoutTopBar,
            R.id.scrollCheckoutContent,
            R.id.layoutCheckoutBottomBar
        )

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackNavigation()
            }
        })

        applyPressFeedback(
            R.id.ivCheckoutBack,
            R.id.tvCheckoutModifyAddress,
            R.id.cardCheckoutAddress,
            R.id.cardDeliveryStandard,
            R.id.cardDeliveryExpress,
            R.id.cardPayCash,
            R.id.btnCheckoutContinue
        )
    }

    override fun onResume() {
        super.onResume()
        bindDynamicData()
        applyPaymentSelection()
        updateCheckoutActionCard()
    }

    private fun setupActions() {
        binding.ivCheckoutBack.setOnClickListener {
            handleBackNavigation()
        }

        binding.tvCheckoutModifyAddress.setOnClickListener {
            navigateNoShift(AddressesActivity::class.java)
        }

        binding.cardDeliveryStandard.setOnClickListener {
            it.performLightHapticFeedback()
            if (viewModel.isStandardSelected.value == false) {
                viewModel.setShippingType(true)
            }
        }
        binding.cardDeliveryExpress.setOnClickListener {
            it.performLightHapticFeedback()
            if (viewModel.isStandardSelected.value == true) {
                viewModel.setShippingType(false)
            }
        }

        binding.cardPayCash.setOnClickListener {
            it.performLightHapticFeedback()
            selectedPaymentMethod = PaymentMethod.CASH
            applyPaymentSelection()
        }

        binding.btnCheckoutContinue.setOnClickListener {
            it.performLightHapticFeedback()
            Log.d(TAG, "Continue tapped on step=${viewModel.currentStep.value ?: 1}")
            if (FirebaseAuthManager.currentUser == null) {
                showCheckoutContinuationChoice(force = true)
                return@setOnClickListener
            }
        if ((viewModel.currentStep.value ?: 1) == 1) {
            transitionToStep2()
        } else {
                confirmOrder()
            }
        }
    }

    private fun observeShippingSelection() {
        viewModel.isStandardSelected.observe(this) { applyDeliverySelection() }
    }

    private fun observeOrderResult() {
        viewModel.orderResult.observe(this) { result ->
            result ?: return@observe
            result.fold(
                onSuccess = { order ->
                    lastSavedOrder = order
                    AnalyticsTracker.purchase(order)
                    AnalyticsTracker.checkoutStepCompleted(3, "confirmation")
                    val draftId = lastDraftOrder?.id
                    if (!draftId.isNullOrBlank()) {
                        LocalOrderStore.replaceTemp(this, draftId, order)
                    } else {
                        LocalOrderStore.upsert(this, order)
                    }
                    lastDraftOrder = null
                    btnContinue.isEnabled = true
                    binding.layoutLottieLoading.visibility = View.GONE
                    transitionToStep3()
                    viewModel.resetOrderResult()
                },
                onFailure = { e ->
                    Log.e(TAG, "Order confirmation failed", e)
                    CrashlyticsHelper.recordNonFatal(TAG, "Order confirmation failed", e)
                    if (shouldKeepDraftForRetry(e)) {
                        Log.w(TAG, "Keeping draft order id for retry-safe checkout.")
                        lastDraftOrder?.let { LocalOrderStore.upsert(this, it) }
                    } else {
                        lastDraftOrder?.id?.let { draftId ->
                            LocalOrderStore.remove(this, draftId)
                        }
                        lastDraftOrder = null
                    }
                    btnContinue.isEnabled = true
                    btnContinue.text = getString(R.string.checkout_order_retry)
                    binding.layoutLottieLoading.visibility = View.GONE
                    showMotionSnackbar(checkoutErrorMessage(e))
                    viewModel.resetOrderResult()
                }
            )
        }

        viewModel.isProcessing.observe(this) { processing ->
            if (processing) {
                btnContinue.isEnabled = false
                btnContinue.text = getString(R.string.checkout_processing)
                binding.layoutLottieLoading.visibility = View.VISIBLE
            } else {
                binding.layoutLottieLoading.visibility = View.GONE
                if ((viewModel.currentStep.value ?: 1) < 3) {
                    btnContinue.isEnabled = true
                    updateCheckoutChrome()
                }
            }
        }
    }

    private fun observeUserProfile() {
        viewModel.userProfile.observe(this) { profile ->
            if (isFinishing || isDestroyed || profile == null) return@observe
            if (profile.name.isNotBlank()) {
                binding.tvCheckoutAddressName.text = profile.name
            }
            applyProfileLocation(profile)
        }
    }

    private fun loadCommerceConfig() {
        ConfigService.cachedCommerceConfig(this)?.let { cached ->
            standardShippingFee = cached.standardShippingFee
            expressShippingFee = cached.expressShippingFee
            updateSummary()
        }
        lifecycleScope.launch {
            runCatching { FirestoreService.fetchCommerceConfig() }
                .onSuccess { config ->
                    ConfigService.cacheCommerceConfig(this@CheckoutDetailsActivity, config)
                    standardShippingFee = config.standardShippingFee
                    expressShippingFee = config.expressShippingFee
                    updateSummary()
                }
                .onFailure {
                    CrashlyticsHelper.recordNonFatal(TAG, "Commerce config load failed", it)
                    if (ConfigService.cachedCommerceConfig(this@CheckoutDetailsActivity) != null) {
                        showMotionSnackbar(getString(R.string.checkout_cached_config_notice))
                    }
                    updateSummary()
                }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CONFIRMATION_ORDER_NUMBER, confirmationOrderNumber)
        outState.putString(STATE_CONFIRMATION_DELIVERY_ESTIMATE, confirmationDeliveryEstimate)
        outState.putDouble(STATE_STANDARD_SHIPPING_FEE, standardShippingFee)
        outState.putDouble(STATE_EXPRESS_SHIPPING_FEE, expressShippingFee)
        outState.putBoolean(STATE_GUEST_CHOICE_SHOWN, guestCheckoutChoiceShown)
        outState.putString(STATE_LAST_SAVED_ORDER_ID, lastSavedOrder?.id)
        outState.putString(STATE_LAST_DRAFT_ORDER_ID, lastDraftOrder?.id)
        outState.putInt(STATE_CURRENT_STEP, viewModel.currentStep.value ?: 1)
        outState.putBoolean(STATE_IS_STANDARD_SHIPPING, viewModel.isStandardSelected.value != false)
    }

    private fun restoreCheckoutState(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        confirmationOrderNumber = savedInstanceState.getString(STATE_CONFIRMATION_ORDER_NUMBER).orEmpty()
        confirmationDeliveryEstimate = savedInstanceState.getString(STATE_CONFIRMATION_DELIVERY_ESTIMATE).orEmpty()
        standardShippingFee = savedInstanceState.getDouble(STATE_STANDARD_SHIPPING_FEE, CartStore.LIVRAISON_FEE)
        expressShippingFee = savedInstanceState.getDouble(STATE_EXPRESS_SHIPPING_FEE, 12.500)
        guestCheckoutChoiceShown = savedInstanceState.getBoolean(STATE_GUEST_CHOICE_SHOWN, false)
        viewModel.setStep(savedInstanceState.getInt(STATE_CURRENT_STEP, 1))
        viewModel.setShippingType(savedInstanceState.getBoolean(STATE_IS_STANDARD_SHIPPING, true))
        savedInstanceState.getString(STATE_LAST_SAVED_ORDER_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { lastSavedOrder = LocalOrderStore.findById(this, it) }
        savedInstanceState.getString(STATE_LAST_DRAFT_ORDER_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { lastDraftOrder = LocalOrderStore.findById(this, it) }
    }

    private fun confirmOrder() {
        if (viewModel.isProcessing.value == true) return
        if (FirebaseAuthManager.currentUser == null) {
            showPhoneCheckoutDialog()
            return
        }
        if (AddressBookStore.getCurrent(this) == null) {
            Log.w(TAG, "Order confirmation blocked: no active address")
            updateCheckoutActionCard(requestFocus = true)
            return
        }
        if (!requireCompleteCheckoutAddress { confirmOrder() }) return
        if (selectedPaymentMethod != PaymentMethod.CASH) {
            Log.w(TAG, "Order confirmation blocked: unsupported payment=$selectedPaymentMethod")
            showMotionSnackbar(getString(R.string.checkout_cod_only))
            return
        }
        Log.d(TAG, "Order confirmation passed guards, preparing submit")
        saveOrderAndProceed()
    }

    private fun saveOrderAndProceed() {
        if (viewModel.isProcessing.value == true) return
        val cart = CartStore.getCart(this)
        if (cart.isEmpty()) {
            Log.w(TAG, "Order confirmation blocked: cart is empty")
            showMotionSnackbar(getString(R.string.checkout_cart_empty))
            return
        }
        if (!validateCartStock(cart)) return

        val subtotal = CartStore.subtotal(this)
        val isStandard = viewModel.isStandardSelected.value ?: true
        val shippingFee = if (isStandard) standardShippingFee else expressShippingFee
        val deliveryFeeMinor = toMinorUnits(shippingFee)
        val subtotalMinor = CartStore.subtotalMinor(this)
        val totalMinor = subtotalMinor + deliveryFeeMinor
        val deliveryAddress = AddressBookStore.getCurrent(this) ?: run {
            Log.w(TAG, "Order confirmation blocked during save: address missing")
            showMotionSnackbar(getString(R.string.checkout_add_address_first))
            return
        }
        if (!deliveryAddress.isCompleteForBackend()) {
            Log.w(TAG, "Order confirmation blocked during save: address incomplete")
            showCompleteDeliveryAddressDialog(deliveryAddress) { saveOrderAndProceed() }
            return
        }
        val addressSnapshot = deliveryAddress.toSnapshot()

        val orderItems = cart.map { (key, quantity) ->
            val productId = CartKey.productId(key)
            val variantId = CartKey.variantId(key)
            val product = ProductCatalog.byId(productId)
            val variant = product?.variantById(variantId)
            val unitPrice = product?.unitPriceForVariant(variant) ?: 0.0
            OrderItem(
                productId = productId,
                variantId = variantId ?: "",
                selectedColor = variant?.colorName ?: "",
                selectedSize = variant?.size ?: "",
                variantLabel = variant?.label ?: "",
                name = product?.title ?: productId,
                priceAtPurchase = unitPrice,
                priceAtPurchaseMinor = toMinorUnits(unitPrice),
                quantity = quantity,
                thumbnailUrl = variant?.imageUrl?.takeIf { it.isNotBlank() }
                    ?: product?.previewImageUrl() ?: ""
            )
        }

        val uid = FirebaseAuthManager.currentUser?.uid ?: return
        val draftId = reusableDraftOrderId(
            uid = uid,
            items = orderItems,
            subtotalMinor = subtotalMinor,
            deliveryFeeMinor = deliveryFeeMinor,
            totalMinor = totalMinor,
            addressSnapshot = addressSnapshot
        ) ?: buildLocalOrderAttemptId()
        
        val order = AppOrder(
            id = draftId,
            uid = uid,
            items = orderItems,
            subtotal = subtotal,
            subtotalMinor = subtotalMinor,
            deliveryFee = shippingFee,
            deliveryFeeMinor = deliveryFeeMinor,
            total = subtotal + shippingFee,
            totalMinor = totalMinor,
            paymentMethod = "COD",
            shippingAddress = addressSnapshot,
            createdAt = com.google.firebase.Timestamp.now()
        )

        Log.d(
            TAG,
            "Submitting order with ${orderItems.size} items, subtotal=$subtotal, shippingFee=$shippingFee"
        )
        lastDraftOrder = order
        LocalOrderStore.upsert(this, order)
        viewModel.submitOrder(uid, order, if (isStandard) "standard" else "express")
    }

    private fun buildLocalOrderAttemptId(): String {
        return "local_${System.currentTimeMillis()}"
    }

    private fun reusableDraftOrderId(
        uid: String,
        items: List<OrderItem>,
        subtotalMinor: Long,
        deliveryFeeMinor: Long,
        totalMinor: Long,
        addressSnapshot: DeliveryAddressSnapshot
    ): String? {
        val draft = lastDraftOrder ?: return null
        if (!draft.id.startsWith("local_")) return null
        if (draft.uid != uid || draft.paymentMethod != "COD") return null
        if (draft.subtotalMinor != subtotalMinor) return null
        if (draft.deliveryFeeMinor != deliveryFeeMinor) return null
        if (draft.totalMinor != totalMinor) return null
        if (draft.shippingAddress != addressSnapshot) return null
        if (!draft.items.sameCheckoutItems(items)) return null
        return draft.id
    }

    private fun List<OrderItem>.sameCheckoutItems(other: List<OrderItem>): Boolean {
        if (size != other.size) return false
        val sortOrder = compareBy<OrderItem> { it.productId }
            .thenBy { it.quantity }
            .thenBy { it.priceAtPurchaseMinor }
        val left = sortedWith(sortOrder)
        val right = other.sortedWith(sortOrder)
        return left.zip(right).all { (a, b) ->
            a.productId == b.productId &&
                a.quantity == b.quantity &&
                a.priceAtPurchaseMinor == b.priceAtPurchaseMinor
        }
    }

    private fun shouldKeepDraftForRetry(error: Throwable): Boolean {
        val backendError = error as? BackendFunctionException ?: return true
        return backendError.code in setOf(
            FirebaseFunctionsException.Code.UNAVAILABLE,
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED,
            FirebaseFunctionsException.Code.INTERNAL,
            FirebaseFunctionsException.Code.UNKNOWN,
            FirebaseFunctionsException.Code.ABORTED
        )
    }

    private fun transitionToStep2() {
        if (viewModel.currentStep.value == 2) return
        if (FirebaseAuthManager.currentUser == null) {
            showCheckoutContinuationChoice(force = true)
            return
        }
        if (AddressBookStore.getCurrent(this) == null) {
            updateCheckoutActionCard(requestFocus = true)
            return
        }
        if (!requireCompleteCheckoutAddress { transitionToStep2() }) return
        viewModel.setStep(2)
        AnalyticsTracker.checkoutStepCompleted(1, "delivery")
        AnalyticsTracker.beginCheckout(
            itemCount = CartStore.itemCount(this),
            value = CartStore.total(this, selectedShippingFee())
        )

        val layoutStep1 = binding.layoutStep1Content
        val layoutStep2 = binding.layoutStep2Content
        updateCheckoutChrome()
        renderStepState()

        layoutStep2.visibility = View.VISIBLE
        layoutStep2.alpha = 0f

        layoutStep1.animate().alpha(0f).setDuration(MotionTokens.EMPHASIS).withEndAction {
            layoutStep1.visibility = View.GONE
            layoutStep2.animate().alpha(1f).setDuration(MotionTokens.EMPHASIS).start()
        }.start()

        applyPaymentSelection()
    }

    private fun transitionToStep3() {
        if (viewModel.currentStep.value == 3) return
        val previousStep = viewModel.currentStep.value ?: 1
        viewModel.setStep(3)
        AnalyticsTracker.checkoutStepCompleted(2, "payment")

        val layoutStep2 = binding.layoutStep2Content
        val layoutStep3 = binding.layoutStep3Content
        val bottomBar = binding.layoutCheckoutBottomBar
        updateCheckoutChrome()
        renderStepState()

        val container = binding.layoutStep3Items
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        
        val savedOrder = lastSavedOrder
        if (savedOrder != null) {
            savedOrder.items.forEach { item ->
                val itemView = inflater.inflate(R.layout.item_confirmation_product, container, false)

                itemView.findViewById<ImageView>(R.id.ivConfirmItemImage)
                    ?.loadCatalogImage(item.thumbnailUrl, R.drawable.placeholder)
                itemView.findViewById<TextView>(R.id.tvConfirmItemName)?.text = item.name
                itemView.findViewById<TextView>(R.id.tvConfirmItemDetails)?.apply {
                    val variantPart = item.variantLabel.ifBlank {
                        listOf(item.selectedColor, item.selectedSize)
                            .filter { it.isNotBlank() }
                            .joinToString(" · ")
                    }
                    text = if (variantPart.isBlank()) {
                        getString(R.string.order_details_item_qty, item.quantity)
                    } else {
                        "${getString(R.string.order_details_item_qty, item.quantity)} · $variantPart"
                    }
                }
                itemView.findViewById<TextView>(R.id.tvConfirmItemPrice)?.text = formatDt(item.priceAtPurchase * item.quantity)

                container.addView(itemView)
            }
            binding.tvConfirmationTotal.text = formatDt(savedOrder.total)
        } else {
            binding.tvConfirmationTotal.text = formatDt(0.0)
        }
        ensureConfirmationMetadata()
        binding.tvConfirmationOrderNumber.text = confirmationOrderNumber
        binding.tvConfirmationEta.text = confirmationDeliveryEstimate

        CartStore.clear(this)

        binding.btnTrackOrder.setOnClickListener {
            val orderId = lastSavedOrder?.id
            if (orderId.isNullOrBlank()) {
                navigateNoShift(OrdersHistoryActivity::class.java)
            } else {
                startActivity(OrderDetailsActivity.createIntent(this, orderId))
            }
        }
        binding.btnBackHome.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
        }

        layoutStep3.visibility = View.VISIBLE
        layoutStep3.alpha = 0f

        bottomBar.animate().alpha(0f).setDuration(MotionTokens.EMPHASIS).withEndAction {
            bottomBar.visibility = View.GONE
        }.start()

        val sourceLayout = if (previousStep == 2) layoutStep2 else binding.layoutStep1Content
        sourceLayout.animate().alpha(0f).setDuration(MotionTokens.EMPHASIS).withEndAction {
            sourceLayout.visibility = View.GONE
            layoutStep3.animate().alpha(1f).setDuration(MotionTokens.EMPHASIS).withStartAction {
                playConfirmationAnimation()
            }.start()
        }.start()
    }

    private fun bindDynamicData() {
        ensureCheckoutAddressFromCachedLocation()
        val address = AddressBookStore.getCurrent(this)
        val tvName = binding.tvCheckoutAddressName
        val tvLine1 = binding.tvCheckoutAddressLine1
        val tvLine2 = binding.tvCheckoutAddressLine2
        val tvPhone = binding.tvCheckoutAddressPhone

        val firebaseUser = FirebaseAuthManager.currentUser
        tvName.text = address?.recipientName?.takeIf { it.isNotBlank() }
            ?: firebaseUser?.displayName?.takeIf { it.isNotBlank() }
            ?: getString(R.string.user_guest_name)
        tvLine1.text = address?.summaryLine ?: getString(R.string.checkout_address_placeholder)
        tvLine2.text = address?.detailsLine.orEmpty()
        tvLine2.visibility = if (address?.detailsLine.isNullOrBlank()) View.GONE else View.VISIBLE
        tvPhone.text = address?.phone.orEmpty()
        tvPhone.visibility = if (address?.phone.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.cardCheckoutAddress.alpha = if (address == null) 0.72f else 1f

        firebaseUser?.uid?.let { uid -> viewModel.loadUserProfile(uid) } ?: viewModel.clearUserProfile()

        val tray = binding.layoutCheckoutArticles
        tray.removeAllViews()
        val cart = CartStore.getCart(this)
        val productIds = cart.keys.mapTo(linkedSetOf()) { CartKey.productId(it) }
        val items = ProductCatalog.orderedFavorites(productIds)
        tray.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        val inflater = LayoutInflater.from(this)
        items.take(4).forEach { product ->
            val card = inflater.inflate(R.layout.item_checkout_thumbnail, tray, false) as MaterialCardView
            card.findViewById<ImageView>(R.id.ivThumbnail)
                ?.loadCatalogImage(product.previewImageUrl(), product.catalogFallbackImageRes())
            tray.addView(card)
        }

        updateSummary()
        updateCheckoutActionCard()
    }

    private fun updateSummary() {
        val subtotal = CartStore.subtotal(this)
        val isStandard = viewModel.isStandardSelected.value ?: true
        val shipping = if (isStandard) standardShippingFee else expressShippingFee
        val total = subtotal + shipping

        binding.tvCheckoutSubtotal.text = formatDt(subtotal)
        binding.tvDeliveryStandardPrice.text =
            if (standardShippingFee <= 0.0) getString(R.string.checkout_shipping_free) else formatDt(standardShippingFee)
        binding.tvDeliveryExpressPrice.text = formatDt(expressShippingFee)

        val tvShippingLabel = binding.tvCheckoutShippingLabel
        val tvShippingValue = binding.tvCheckoutShippingValue

        if (isStandard) {
            tvShippingLabel.text = getString(R.string.checkout_shipping_standard)
            tvShippingValue.text = if (standardShippingFee <= 0.0) getString(R.string.checkout_shipping_free) else formatDt(standardShippingFee)
        } else {
            tvShippingLabel.text = getString(R.string.checkout_shipping_express)
            tvShippingValue.text = formatDt(expressShippingFee)
        }

        binding.tvCheckoutTotal.text = formatDt(total)
    }

    private fun applyDeliverySelection() {
        val cardStandard = binding.cardDeliveryStandard
        val cardExpress = binding.cardDeliveryExpress
        val checkStandard = binding.ivStandardCheck
        val radioExpress = binding.ivExpressRadio

        val colorSelected = ContextCompat.getColor(this, R.color.colorPrimary)
        val colorUnselected = ContextCompat.getColor(this, R.color.colorBorderLight)
        val strokeSelected = resources.getDimensionPixelSize(R.dimen.checkout_selected_stroke)
        val strokeUnselected = resources.getDimensionPixelSize(R.dimen.checkout_unselected_stroke)

        val isStandard = viewModel.isStandardSelected.value ?: true
        if (isStandard) {
            cardStandard.strokeColor = colorSelected
            cardStandard.strokeWidth = strokeSelected
            checkStandard.visibility = View.VISIBLE

            cardExpress.strokeColor = colorUnselected
            cardExpress.strokeWidth = strokeUnselected
            radioExpress.visibility = View.VISIBLE
        } else {
            cardStandard.strokeColor = colorUnselected
            cardStandard.strokeWidth = strokeUnselected
            checkStandard.visibility = View.GONE

            cardExpress.strokeColor = colorSelected
            cardExpress.strokeWidth = strokeSelected
            radioExpress.visibility = View.GONE
        }

        updateSummary()
    }

    private fun updateCheckoutActionCard(requestFocus: Boolean = false) {
        val needsLogin = FirebaseAuthManager.currentUser == null
        val needsAddress = AddressBookStore.getCurrent(this) == null
        val shouldShow = if (needsLogin) viewModel.currentStep.value != 3 else needsAddress

        binding.cardCheckoutActionNeeded.visibility = if (shouldShow) View.VISIBLE else View.GONE
        if (!shouldShow) return

        if (needsLogin) {
            binding.tvCheckoutActionTitle.text = getString(R.string.checkout_choice_title)
            binding.tvCheckoutActionMessage.text = getString(R.string.checkout_guest_choice_message)
            binding.btnCheckoutAction.text = getString(R.string.checkout_continue_phone)
            binding.btnCheckoutAction.setOnClickListener {
                showCheckoutContinuationChoice(force = true)
            }
        } else {
            binding.tvCheckoutActionTitle.text = getString(R.string.checkout_address_required_title)
            binding.tvCheckoutActionMessage.text = getString(R.string.checkout_location_or_manual)
            binding.btnCheckoutAction.text = getString(
                if (LocationPermissionStore.isPermanentlyDenied(this) || LocationHelper.isPermanentlyDenied(this)) {
                    R.string.checkout_open_location_settings
                } else {
                    R.string.checkout_use_current_location
                }
            )
            binding.btnCheckoutAction.setOnClickListener {
                if (LocationPermissionStore.isPermanentlyDenied(this) || LocationHelper.isPermanentlyDenied(this)) {
                    LocationHelper.openAppSettings(this)
                } else if (LocationHelper.hasPermission(this)) {
                    fetchAndUseCheckoutLocation()
                } else {
                    Log.d("LocationFlow", "Location permission requested")
                    requestLocationLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        }

        if (requestFocus) {
            binding.scrollCheckoutContent.post {
                binding.scrollCheckoutContent.smoothScrollTo(0, binding.cardCheckoutActionNeeded.top)
            }
            showMotionSnackbar(binding.tvCheckoutActionMessage.text.toString())
        }
    }

    private fun applyProfileLocation(profile: FirestoreService.UserProfile) {
        val location = profile.location ?: return
        if (AddressBookStore.getCurrent(this) != null) return
        val address = location.toDeliveryAddress(
            name = profile.name.ifBlank { FirebaseAuthManager.currentUser?.displayName.orEmpty() },
            phone = AddressBookStore.getCurrent(this)?.phone.orEmpty()
        )
        AddressBookStore.upsert(this, address)
        UserLocationStore.save(this, location)
        Log.d("LocationFlow", "Checkout used saved location")
        bindDynamicData()
    }

    private fun ensureCheckoutAddressFromCachedLocation() {
        if (AddressBookStore.getCurrent(this) != null) return
        val location = UserLocationStore.load(this) ?: return
        val address = location.toDeliveryAddress(
            name = FirebaseAuthManager.currentUser?.displayName.orEmpty(),
            phone = ""
        )
        AddressBookStore.upsert(this, address)
        Log.d("LocationFlow", "Checkout used saved location")
    }

    private fun fetchAndUseCheckoutLocation() {
        lifecycleScope.launch {
            LocationHelper.fetchCurrentLocation(this@CheckoutDetailsActivity)
                .onSuccess { location ->
                    val address = location.toDeliveryAddress(
                        name = FirebaseAuthManager.currentUser?.displayName.orEmpty(),
                        phone = AddressBookStore.getCurrent(this@CheckoutDetailsActivity)?.phone.orEmpty()
                    )
                    AddressBookStore.upsert(this@CheckoutDetailsActivity, address)
                    LocationProfileSync.saveLocation(this@CheckoutDetailsActivity, location)
                    Log.d("LocationFlow", "Checkout used saved location")
                    bindDynamicData()
                    updateCheckoutActionCard()
                }
                .onFailure { error ->
                    Log.w(TAG, "Checkout location fetch failed", error)
                    showMotionSnackbar(getString(R.string.checkout_add_address_first))
                }
        }
    }

    private fun requireCompleteCheckoutAddress(onReady: () -> Unit): Boolean {
        val address = AddressBookStore.getCurrent(this) ?: return false
        if (address.isCompleteForBackend()) return true
        showCompleteDeliveryAddressDialog(address, onReady)
        return false
    }

    private fun showCompleteDeliveryAddressDialog(
        existing: DeliveryAddress,
        onReady: () -> Unit
    ) {
        val fullName = checkoutInput(R.string.checkout_phone_full_name, InputType.TYPE_CLASS_TEXT)
        val phone = checkoutInput(R.string.checkout_phone_number, InputType.TYPE_CLASS_PHONE)
        val address = checkoutInput(R.string.checkout_phone_address, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val city = checkoutInput(R.string.checkout_phone_city, InputType.TYPE_CLASS_TEXT)
        val note = checkoutInput(R.string.checkout_phone_note_optional, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)

        fullName.setText(
            existing.recipientName
                .ifBlank { viewModel.userProfile.value?.name.orEmpty() }
                .ifBlank { FirebaseAuthManager.currentUser?.displayName.orEmpty() }
        )
        phone.setText(existing.phone)
        address.setText(existing.addressLine1.ifBlank { existing.summaryLine })
        city.setText(existing.city.ifBlank { existing.governorate })
        note.setText(existing.deliveryNotes.orEmpty())

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.checkout_address_required_title)
            .setView(checkoutForm(fullName, phone, address, city, note))
            .setNegativeButton(R.string.profile_edit_cancel, null)
            .setPositiveButton(R.string.checkout_step1_continue, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val completed = buildCheckoutAddress(
                    fullName = fullName.text?.toString().orEmpty(),
                    phone = phone.text?.toString().orEmpty(),
                    address = address.text?.toString().orEmpty(),
                    city = city.text?.toString().orEmpty(),
                    note = note.text?.toString().orEmpty()
                )?.copy(id = existing.id, label = existing.label.ifBlank { getString(R.string.checkout_guest_address_label) })
                    ?: return@setOnClickListener

                AddressBookStore.upsert(this, completed)
                lifecycleScope.launch { LocationProfileSync.saveManualAddress(this@CheckoutDetailsActivity, completed) }
                Log.d("LocationFlow", "Checkout used manual location")
                dialog.dismiss()
                bindDynamicData()
                onReady()
            }
        }
        dialog.show()
    }

    private fun applyPaymentSelection() {
        val cardPayCash = binding.cardPayCash
        val ivCash = binding.ivPayCashRadio

        val colorSelected = ContextCompat.getColor(this, R.color.colorPrimary)
        val strokeSelected = resources.getDimensionPixelSize(R.dimen.checkout_selected_stroke)

        cardPayCash.alpha = 1f
        cardPayCash.strokeColor = colorSelected
        cardPayCash.strokeWidth = strokeSelected
        ivCash.setImageResource(R.drawable.ic_checkout_radio_filled)
        selectedPaymentMethod = PaymentMethod.CASH
        
        binding.tvPayCashTitle.text = getString(R.string.checkout_pay_cod_title)
        binding.tvPayCashSubtitle.text = getString(R.string.checkout_pay_cod_subtitle)
    }

    private fun showCheckoutContinuationChoice(force: Boolean = false) {
        if (!force && guestCheckoutChoiceShown) return
        if (FirebaseAuthManager.currentUser != null) return
        guestCheckoutChoiceShown = true

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.checkout_choice_title)
            .setMessage(R.string.checkout_guest_choice_message)
            .setNegativeButton(R.string.checkout_create_account) { _, _ ->
                showCreateAccountCheckoutDialog()
            }
            .setPositiveButton(R.string.checkout_continue_phone) { _, _ ->
                showPhoneCheckoutDialog()
            }
            .show()
    }

    private fun showPhoneCheckoutDialog() {
        val fullName = checkoutInput(R.string.checkout_phone_full_name, InputType.TYPE_CLASS_TEXT)
        val phone = checkoutInput(R.string.checkout_phone_number, InputType.TYPE_CLASS_PHONE)
        val address = checkoutInput(R.string.checkout_phone_address, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val city = checkoutInput(R.string.checkout_phone_city, InputType.TYPE_CLASS_TEXT)
        val note = checkoutInput(R.string.checkout_phone_note_optional, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val content = checkoutForm(fullName, phone, address, city, note)
        content.addView(TextView(this).apply {
            text = getString(R.string.checkout_cod_only)
            setTextAppearance(R.style.AppText_Caption)
            setPadding(0, 10.dp, 0, 0)
        })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.checkout_continue_phone)
            .setView(content)
            .setNegativeButton(R.string.profile_edit_cancel, null)
            .setPositiveButton(R.string.checkout_confirm_order, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val deliveryAddress = buildCheckoutAddress(
                    fullName = fullName.text?.toString().orEmpty(),
                    phone = phone.text?.toString().orEmpty(),
                    address = address.text?.toString().orEmpty(),
                    city = city.text?.toString().orEmpty(),
                    note = note.text?.toString().orEmpty()
                ) ?: return@setOnClickListener

                dialog.dismiss()
                AddressBookStore.upsert(this, deliveryAddress)
                lifecycleScope.launch { LocationProfileSync.saveManualAddress(this@CheckoutDetailsActivity, deliveryAddress) }
                bindDynamicData()
                saveGuestOrderAndProceed(deliveryAddress)
            }
        }
        dialog.show()
    }

    private fun showCreateAccountCheckoutDialog() {
        val fullName = checkoutInput(R.string.checkout_phone_full_name, InputType.TYPE_CLASS_TEXT)
        val phone = checkoutInput(R.string.checkout_phone_number, InputType.TYPE_CLASS_PHONE)
        val password = checkoutInput(
            R.string.checkout_account_password,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        val content = checkoutForm(fullName, phone, password)
        val cartBeforeAccount = CartStore.getCart(this)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.checkout_create_account)
            .setView(content)
            .setNegativeButton(R.string.profile_edit_cancel, null)
            .setPositiveButton(R.string.checkout_create_account, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val nameValue = fullName.text?.toString().orEmpty().trim()
                val phoneValue = DeliveryAddressValidator.normalizedPhone(phone.text?.toString().orEmpty())
                val passwordValue = password.text?.toString().orEmpty()
                when {
                    nameValue.length < 3 -> fullName.error = getString(R.string.checkout_validation_name)
                    phoneValue.length < 8 -> phone.error = getString(R.string.checkout_validation_phone)
                    passwordValue.length < 6 -> password.error = getString(R.string.checkout_validation_password)
                    else -> {
                        dialog.dismiss()
                        createPhoneAccountAndContinue(nameValue, phoneValue, passwordValue, cartBeforeAccount)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun createPhoneAccountAndContinue(
        fullName: String,
        phone: String,
        password: String,
        previousCart: Map<String, Int>
    ) {
        binding.layoutLottieLoading.visibility = View.VISIBLE
        lifecycleScope.launch {
            val generatedEmail = "phone_${phone.filter { it.isDigit() }}@internal.fatiweb.app"
            val result = FirebaseAuthManager.register(generatedEmail, password, fullName)
            binding.layoutLottieLoading.visibility = View.GONE
            result.fold(
                onSuccess = {
                    lifecycleScope.launch {
                        runCatching { UserService.markPhoneAccount(it.uid, phone) }
                    }
                    previousCart.forEach { (key, quantity) ->
                        CartStore.setQuantity(this@CheckoutDetailsActivity, key, quantity)
                    }
                    showAccountDeliveryDialog(fullName, phone)
                },
                onFailure = { error ->
                    showMotionSnackbar(FirebaseAuthManager.friendlyError(this@CheckoutDetailsActivity, error))
                }
            )
        }
    }

    private fun showAccountDeliveryDialog(fullName: String, phone: String) {
        val address = checkoutInput(R.string.checkout_phone_address, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val city = checkoutInput(R.string.checkout_phone_city, InputType.TYPE_CLASS_TEXT)
        val note = checkoutInput(R.string.checkout_phone_note_optional, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val content = checkoutForm(address, city, note)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.checkout_address_required_title)
            .setView(content)
            .setNegativeButton(R.string.profile_edit_cancel, null)
            .setPositiveButton(R.string.checkout_step1_continue, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val deliveryAddress = buildCheckoutAddress(
                    fullName = fullName,
                    phone = phone,
                    address = address.text?.toString().orEmpty(),
                    city = city.text?.toString().orEmpty(),
                    note = note.text?.toString().orEmpty()
                ) ?: return@setOnClickListener

                dialog.dismiss()
                AddressBookStore.upsert(this, deliveryAddress)
                lifecycleScope.launch { LocationProfileSync.saveManualAddress(this@CheckoutDetailsActivity, deliveryAddress) }
                bindDynamicData()
                transitionToStep2()
            }
        }
        dialog.show()
    }

    private fun buildCheckoutAddress(
        fullName: String,
        phone: String,
        address: String,
        city: String,
        note: String
    ): DeliveryAddress? {
        val input = DeliveryAddressInput(
            label = getString(R.string.checkout_guest_address_label),
            recipientName = sanitizeCheckoutField(fullName, MAX_NAME_LENGTH),
            phone = sanitizeCheckoutField(phone, MAX_PHONE_LENGTH),
            governorate = sanitizeCheckoutField(city, MAX_CITY_LENGTH),
            city = sanitizeCheckoutField(city, MAX_CITY_LENGTH),
            addressLine1 = sanitizeCheckoutField(address, MAX_ADDRESS_LENGTH),
            addressLine2 = "",
            postalCode = "",
            deliveryNotes = sanitizeCheckoutField(note, MAX_NOTE_LENGTH),
            isDefault = true
        )
        val error = DeliveryAddressValidator.validate(input)
        if (error != null) {
            showMotionSnackbar(error)
            return null
        }
        return DeliveryAddress(
            label = input.label,
            recipientName = input.recipientName,
            phone = DeliveryAddressValidator.normalizedPhone(input.phone),
            governorate = input.governorate,
            city = input.city,
            addressLine1 = input.addressLine1,
            deliveryNotes = input.deliveryNotes.takeIf { it.isNotBlank() },
            isDefault = true
        )
    }

    private fun saveGuestOrderAndProceed(address: DeliveryAddress? = AddressBookStore.getCurrent(this)) {
        val deliveryAddress = address ?: run {
            showPhoneCheckoutDialog()
            return
        }
        if (selectedPaymentMethod != PaymentMethod.CASH) {
            showMotionSnackbar(getString(R.string.checkout_cod_only))
            return
        }
        val cart = CartStore.getCart(this)
        if (cart.isEmpty()) {
            showMotionSnackbar(getString(R.string.checkout_cart_empty))
            return
        }
        if (!validateCartStock(cart)) return

        binding.layoutLottieLoading.visibility = View.VISIBLE
        btnContinue.isEnabled = false
        lifecycleScope.launch {
            val existingUser = FirebaseAuthManager.currentUser
            val userResult = if (existingUser != null) {
                Result.success(existingUser)
            } else {
                FirebaseAuthManager.signInAnonymously()
            }
            userResult.fold(
                onSuccess = { user ->
                    runCatching {
                        UserService.updateUserProfileName(user.uid, deliveryAddress.recipientName)
                        UserService.markPhoneAccount(user.uid, deliveryAddress.phone)
                    }
                    AddressBookStore.upsert(this@CheckoutDetailsActivity, deliveryAddress)
                    LocationProfileSync.saveManualAddress(this@CheckoutDetailsActivity, deliveryAddress)
                    bindDynamicData()
                    saveOrderAndProceed()
                },
                onFailure = { error ->
                    binding.layoutLottieLoading.visibility = View.GONE
                    btnContinue.isEnabled = true
                    showMotionSnackbar(FirebaseAuthManager.friendlyError(this@CheckoutDetailsActivity, error))
                }
            )
        }
    }

    private fun buildOrderFromCart(uid: String, deliveryAddress: DeliveryAddress, id: String): AppOrder? {
        val cart = CartStore.getCart(this)
        if (cart.isEmpty()) {
            showMotionSnackbar(getString(R.string.checkout_cart_empty))
            return null
        }
        if (!validateCartStock(cart)) return null
        val orderItems = cart.map { (key, quantity) ->
            val productId = CartKey.productId(key)
            val variantId = CartKey.variantId(key)
            val product = ProductCatalog.byId(productId)
            val variant = product?.variantById(variantId)
            val unitPrice = product?.unitPriceForVariant(variant) ?: 0.0
            OrderItem(
                productId = productId,
                variantId = variantId ?: "",
                selectedColor = variant?.colorName ?: "",
                selectedSize = variant?.size ?: "",
                variantLabel = variant?.label ?: "",
                name = product?.title ?: productId,
                priceAtPurchase = unitPrice,
                priceAtPurchaseMinor = toMinorUnits(unitPrice),
                quantity = quantity,
                thumbnailUrl = variant?.imageUrl?.takeIf { it.isNotBlank() }
                    ?: product?.previewImageUrl() ?: ""
            )
        }
        val subtotal = orderItems.sumOf { it.priceAtPurchase * it.quantity }
        val shippingFee = selectedShippingFee()
        return AppOrder(
            id = id,
            uid = uid,
            items = orderItems,
            subtotal = subtotal,
            deliveryFee = shippingFee,
            total = subtotal + shippingFee,
            paymentMethod = "COD",
            shippingAddress = deliveryAddress.toSnapshot(),
            createdAt = com.google.firebase.Timestamp.now()
        )
    }

    private fun checkoutForm(vararg fields: EditText): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp, 8.dp, 4.dp, 0)
            fields.forEach { field ->
                addView(field, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8.dp })
            }
        }
    }

    private fun checkoutInput(hintRes: Int, inputTypeValue: Int): EditText {
        return EditText(this).apply {
            hint = getString(hintRes)
            inputType = inputTypeValue
            minLines = if (inputTypeValue and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) 2 else 1
            maxLines = if (inputTypeValue and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) 4 else 1
            filters = arrayOf(InputFilter.LengthFilter(maxLengthForInput(inputTypeValue)))
        }
    }

    private fun validateCartStock(cart: Map<String, Int>): Boolean {
        val unavailable = cart.entries.firstOrNull { (key, quantity) ->
            val productId = CartKey.productId(key)
            val variantId = CartKey.variantId(key)
            val product = ProductCatalog.byId(productId)
            if (product == null || !product.isActive) return@firstOrNull true
            val availableStock = if (variantId != null) {
                product.variantById(variantId)?.stock ?: product.stock
            } else {
                product.effectiveStock
            }
            availableStock < quantity
        } ?: return true
        val productName = ProductCatalog.byId(CartKey.productId(unavailable.key))?.title ?: unavailable.key
        showMotionSnackbar(getString(R.string.checkout_error_stock) + " $productName")
        return false
    }

    private fun sanitizeCheckoutField(value: String, maxLength: Int): String {
        return value
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxLength)
    }

    private fun maxLengthForInput(inputTypeValue: Int): Int {
        return if (inputTypeValue and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) {
            MAX_ADDRESS_LENGTH
        } else {
            MAX_CITY_LENGTH
        }
    }

    private fun handleBackNavigation() {
        when (viewModel.currentStep.value ?: 1) {
            3 -> Unit
            2 -> transitionBackToStep1()
            else -> finishWithMotion()
        }
    }

    private fun transitionBackToStep1() {
        if (viewModel.currentStep.value == 1) return
        viewModel.setStep(1)

        val layoutStep1 = binding.layoutStep1Content
        val layoutStep2 = binding.layoutStep2Content
        updateCheckoutChrome()
        renderStepState()

        layoutStep1.visibility = View.VISIBLE
        layoutStep1.alpha = 0f

        layoutStep2.animate().alpha(0f).setDuration(MotionTokens.EMPHASIS).withEndAction {
            layoutStep2.visibility = View.GONE
            layoutStep1.animate().alpha(1f).setDuration(MotionTokens.EMPHASIS).start()
        }.start()
    }

    private fun playConfirmationAnimation() {
        binding.ivCheckoutSuccessAnimation.apply {
            cancelAnimation()
            progress = 0f
            playAnimation()
        }
    }

    private fun resetConfirmationAnimation() {
        binding.ivCheckoutSuccessAnimation.apply {
            cancelAnimation()
            progress = 0f
        }
    }

    private fun updateCheckoutChrome() {
        val title = binding.tvCheckoutTitle
        val step = viewModel.currentStep.value ?: 1
        when (step) {
            1 -> {
                title.text = getString(R.string.checkout_step1_title)
                btnContinue.text = getString(R.string.checkout_step1_continue)
            }
            2 -> {
                title.text = getString(R.string.checkout_step2_title)
                btnContinue.text = getString(R.string.checkout_confirm_order)
            }
            3 -> {
                title.text = getString(R.string.checkout_step3_title)
            }
        }
    }

    private fun renderStepState() {
        val step = viewModel.currentStep.value ?: 1
        updateStepIndicator(stepNumber = 1, isActive = step == 1, isComplete = step > 1)
        updateStepIndicator(stepNumber = 2, isActive = step == 2, isComplete = step > 2)
        updateStepIndicator(stepNumber = 3, isActive = step == 3, isComplete = false)

        binding.lineStep1to2.setBackgroundColor(
            ContextCompat.getColor(this, if (step >= 2) R.color.ms_surface_inverse else R.color.ms_border_default)
        )
        binding.lineStep2to3.setBackgroundColor(
            ContextCompat.getColor(this, if (step >= 3) R.color.ms_surface_inverse else R.color.ms_border_default)
        )
    }

    private fun updateStepIndicator(stepNumber: Int, isActive: Boolean, isComplete: Boolean) {
        val bgView = when (stepNumber) {
            1 -> binding.bgStep1
            2 -> binding.bgStep2
            else -> binding.bgStep3
        }
        val textView = when (stepNumber) {
            1 -> binding.tvStep1
            2 -> binding.tvStep2
            else -> binding.tvStep3
        }
        bgView.setBackgroundResource(
            if (isActive || isComplete) R.drawable.bg_checkout_step_active else R.drawable.bg_checkout_step_inactive
        )
        textView.apply {
            text = if (isComplete) {
                context.getString(R.string.details_check_symbol)
            } else {
                stepNumber.toString()
            }
            setTextColor(
                ContextCompat.getColor(
                    this@CheckoutDetailsActivity,
                    when {
                        isActive || isComplete -> R.color.ms_text_inverse
                        else -> R.color.ms_text_tertiary
                    }
                )
            )
            setTypeface(
                null,
                if (isActive || isComplete) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
            )
        }
    }

    private fun ensureConfirmationMetadata() {
        val order = lastSavedOrder
        if (confirmationOrderNumber.isBlank()) {
            confirmationOrderNumber = order?.displayId ?: buildOrderNumber()
        }
        confirmationDeliveryEstimate = buildDeliveryEstimate()
    }

    private fun buildOrderNumber(): String {
        val suffix = (System.currentTimeMillis() % 1_000_000).toString().padStart(6, '0')
        return "#FW-$suffix"
    }

    private fun buildDeliveryEstimate(): String {
        val estimate = java.util.Date(buildEstimatedDeliveryTimestamp())
        return java.text.SimpleDateFormat("dd MMM", resources.configuration.locales[0]).format(estimate)
    }

    private fun buildEstimatedDeliveryTimestamp(): Long {
        val isStandard = viewModel.isStandardSelected.value ?: true
        val daysToAdd = if (isStandard) 4L else 2L
        return System.currentTimeMillis() + daysToAdd * 24L * 60L * 60L * 1000L
    }

    private fun selectedShippingFee(): Double {
        val isStandard = viewModel.isStandardSelected.value != false
        return if (isStandard) standardShippingFee else expressShippingFee
    }

    private fun checkoutErrorMessage(error: Throwable): String {
        val backendError = error as? BackendFunctionException
        val backendMessage = backendError?.backendMessage ?: backendError?.message
        val shouldPreferBackendMessage = backendError?.code !in setOf(
            FirebaseFunctionsException.Code.UNAUTHENTICATED,
            FirebaseFunctionsException.Code.UNAVAILABLE,
            FirebaseFunctionsException.Code.DEADLINE_EXCEEDED
        )
        backendMessage
            ?.let(::sanitizeBackendCheckoutMessage)
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { shouldPreferBackendMessage }
            ?.let { return it }
        return when (backendError?.code) {
            com.google.firebase.functions.FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                getString(R.string.checkout_error_auth)
            com.google.firebase.functions.FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                getString(R.string.checkout_error_invalid)
            com.google.firebase.functions.FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
                getString(R.string.checkout_error_stock)
            com.google.firebase.functions.FirebaseFunctionsException.Code.UNAVAILABLE,
            com.google.firebase.functions.FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                getString(R.string.checkout_error_network)
            else -> getString(R.string.checkout_order_failed)
        }
    }

    private fun sanitizeBackendCheckoutMessage(message: String): String {
        return message
            .replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(180)
    }

    private fun DeliveryAddress.isCompleteForBackend(): Boolean =
        recipientName.trim().length >= 3 &&
            phone.trim().length >= 8 &&
            governorate.trim().length >= 2 &&
            city.trim().length >= 2 &&
            addressLine1.trim().length >= 5

}
