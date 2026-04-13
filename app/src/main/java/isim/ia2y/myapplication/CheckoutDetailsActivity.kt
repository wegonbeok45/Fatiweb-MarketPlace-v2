package isim.ia2y.myapplication

import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import androidx.core.view.updatePadding
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import isim.ia2y.myapplication.databinding.ActivityCheckoutDetailsBinding

class CheckoutDetailsActivity : AppCompatActivity() {
    private companion object {
        const val TAG = "CheckoutDetails"
    }

    private val viewModel: CheckoutViewModel by viewModels()
    private lateinit var binding: ActivityCheckoutDetailsBinding
    private var selectedPaymentMethod = PaymentMethod.CASH
    private var confirmationOrderNumber = ""
    private var confirmationDeliveryEstimate = ""
    private var standardShippingFee = CartStore.LIVRAISON_FEE
    private var expressShippingFee = 12.500
    private var lastSavedOrder: AppOrder? = null
    private val btnContinue: MaterialButton
        get() = binding.btnCheckoutContinue

    enum class PaymentMethod { CARD, EDINAR, CASH }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityCheckoutDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)
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
        bindDynamicData()
        applyPaymentSelection()
        updateCheckoutChrome()
        renderStepState()
        loadCommerceConfig()

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
                    LocalOrderStore.upsert(this, order)
                    btnContinue.isEnabled = true
                    binding.layoutLottieLoading.visibility = View.GONE
                    transitionToStep3()
                    viewModel.resetOrderResult()
                },
                onFailure = { e ->
                    Log.e(TAG, "Order confirmation failed", e)
                    btnContinue.isEnabled = true
                    btnContinue.text = getString(R.string.checkout_confirm_order)
                    binding.layoutLottieLoading.visibility = View.GONE
                    showMotionSnackbar(getString(R.string.checkout_order_failed))
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

    private fun loadCommerceConfig() {
        lifecycleScope.launch {
            runCatching { FirestoreService.fetchCommerceConfig() }
                .onSuccess { config ->
                    standardShippingFee = config.standardShippingFee
                    expressShippingFee = config.expressShippingFee
                    updateSummary()
                }
                .onFailure {
                    updateSummary()
                }
        }
    }

    private fun confirmOrder() {
        if (FirebaseAuthManager.currentUser == null) {
            Log.w(TAG, "Order confirmation blocked: no authenticated user")
            updateCheckoutActionCard(requestFocus = true)
            return
        }
        if (AddressBookStore.getCurrent(this) == null) {
            Log.w(TAG, "Order confirmation blocked: no active address")
            updateCheckoutActionCard(requestFocus = true)
            return
        }
        if (selectedPaymentMethod != PaymentMethod.CASH) {
            Log.w(TAG, "Order confirmation blocked: unsupported payment=$selectedPaymentMethod")
            showMotionSnackbar(getString(R.string.checkout_cod_only))
            return
        }
        Log.d(TAG, "Order confirmation passed guards, preparing submit")
        saveOrderAndProceed()
    }

    private fun saveOrderAndProceed() {
        val cart = CartStore.getCart(this)
        if (cart.isEmpty()) {
            Log.w(TAG, "Order confirmation blocked: cart is empty")
            showMotionSnackbar(getString(R.string.checkout_cart_empty))
            return
        }

        val subtotal = CartStore.subtotal(this)
        val isStandard = viewModel.isStandardSelected.value ?: true
        val shippingFee = if (isStandard) standardShippingFee else expressShippingFee
        val deliveryAddress = AddressBookStore.getCurrent(this) ?: run {
            Log.w(TAG, "Order confirmation blocked during save: address missing")
            showMotionSnackbar(getString(R.string.checkout_add_address_first))
            return
        }
        val estimatedDeliveryDate = buildEstimatedDeliveryTimestamp()

        val orderItems = cart.map { (productId, quantity) ->
            val product = ProductCatalog.byId(productId)
            OrderItem(
                productId = productId,
                name = product?.title ?: productId.toString(),
                priceAtPurchase = product?.price ?: 0.0,
                quantity = quantity,
                thumbnailUrl = product?.imageUrls?.firstOrNull() ?: product?.imageUrl ?: ""
            )
        }

        val uid = FirebaseAuthManager.currentUser?.uid ?: return
        
        val order = AppOrder(
            id = "local_${System.currentTimeMillis()}",
            uid = uid,
            items = orderItems,
            subtotal = subtotal,
            deliveryFee = shippingFee,
            total = subtotal + shippingFee,
            paymentMethod = "COD",
            shippingAddress = deliveryAddress.toSnapshot(),
            createdAt = com.google.firebase.Timestamp.now()
        )

        Log.d(
            TAG,
            "Submitting order with ${orderItems.size} items, subtotal=$subtotal, shippingFee=$shippingFee"
        )
        viewModel.submitOrder(uid, order, if (isStandard) "standard" else "express")
    }

    private fun transitionToStep2() {
        if (viewModel.currentStep.value == 2) return
        if (FirebaseAuthManager.currentUser == null || AddressBookStore.getCurrent(this) == null) {
            updateCheckoutActionCard(requestFocus = true)
            return
        }
        viewModel.setStep(2)

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
        viewModel.setStep(3)

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
                itemView.findViewById<TextView>(R.id.tvConfirmItemDetails)?.text =
                    getString(R.string.order_details_item_qty, item.quantity)
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

        layoutStep2.animate().alpha(0f).setDuration(MotionTokens.EMPHASIS).withEndAction {
            layoutStep2.visibility = View.GONE
            layoutStep3.animate().alpha(1f).setDuration(MotionTokens.EMPHASIS).withStartAction {
                playConfirmationAnimation()
            }.start()
        }.start()
    }

    private fun bindDynamicData() {
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

        firebaseUser?.uid?.let { uid ->
            lifecycleScope.launch {
                runCatching { FirestoreService.fetchUserProfile(uid) }
                    .onSuccess { profile ->
                        if (!isFinishing && !isDestroyed && profile != null) {
                            tvName.text = profile.name.ifBlank { tvName.text }
                        }
                    }
                    .onFailure {
                        showMotionSnackbar(getString(R.string.profile_load_failed))
                    }
            }
        }

        val tray = binding.layoutCheckoutArticles
        tray.removeAllViews()
        val cart = CartStore.getCart(this)
        val items = ProductCatalog.orderedFavorites(cart.keys)
        tray.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE

        val inflater = LayoutInflater.from(this)
        items.take(4).forEach { product ->
            val card = inflater.inflate(R.layout.item_checkout_thumbnail, tray, false) as MaterialCardView
            card.findViewById<ImageView>(R.id.ivThumbnail)
                ?.loadCatalogImage(product.imageUrl, product.imageRes)
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
        val shouldShow = needsLogin || needsAddress

        binding.cardCheckoutActionNeeded.visibility = if (shouldShow) View.VISIBLE else View.GONE
        if (!shouldShow) return

        if (needsLogin) {
            binding.tvCheckoutActionTitle.text = getString(R.string.checkout_login_required_title)
            binding.tvCheckoutActionMessage.text = getString(R.string.checkout_login_required_message)
            binding.btnCheckoutAction.text = getString(R.string.checkout_login_action)
            binding.btnCheckoutAction.setOnClickListener {
                showAuthChoiceDialog(
                    onCreateAccount = {
                        startActivity(
                            RegisterActivity.createIntent(
                                this,
                                returnToRoute = AUTH_RETURN_ROUTE_CHECKOUT
                            )
                        )
                    },
                    onExistingClient = {
                        startActivity(
                            LoginActivity.createIntent(
                                this,
                                returnToRoute = AUTH_RETURN_ROUTE_CHECKOUT
                            )
                        )
                    }
                )
            }
        } else {
            binding.tvCheckoutActionTitle.text = getString(R.string.checkout_address_required_title)
            binding.tvCheckoutActionMessage.text = getString(R.string.checkout_add_address_first)
            binding.btnCheckoutAction.text = getString(R.string.checkout_address_action)
            binding.btnCheckoutAction.setOnClickListener {
                navigateNoShift(AddressesActivity::class.java)
            }
        }

        if (requestFocus) {
            binding.scrollCheckoutContent.post {
                binding.scrollCheckoutContent.smoothScrollTo(0, binding.cardCheckoutActionNeeded.top)
            }
            showMotionSnackbar(binding.tvCheckoutActionMessage.text.toString())
        }
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

    private fun handleBackNavigation() {
        when (viewModel.currentStep.value ?: 1) {
            3 -> transitionBackToStep2()
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

    private fun transitionBackToStep2() {
        if (viewModel.currentStep.value == 2) return
        viewModel.setStep(2)

        val layoutStep2 = binding.layoutStep2Content
        val layoutStep3 = binding.layoutStep3Content
        val bottomBar = binding.layoutCheckoutBottomBar
        updateCheckoutChrome()
        renderStepState()

        bottomBar.visibility = View.VISIBLE
        bottomBar.alpha = 0f
        bottomBar.animate().alpha(1f).setDuration(MotionTokens.EMPHASIS).start()

        layoutStep2.visibility = View.VISIBLE
        layoutStep2.alpha = 0f

        layoutStep3.animate().alpha(0f).setDuration(MotionTokens.EMPHASIS).withEndAction {
            layoutStep3.visibility = View.GONE
            layoutStep2.animate().alpha(1f).setDuration(MotionTokens.EMPHASIS).start()
        }.start()

        resetConfirmationAnimation()
        applyPaymentSelection()
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
            ContextCompat.getColor(this, if (step >= 2) R.color.colorPrimary else R.color.colorBorderLight)
        )
        binding.lineStep2to3.setBackgroundColor(
            ContextCompat.getColor(this, if (step >= 3) R.color.colorPrimary else R.color.colorBorderLight)
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
                        isActive -> R.color.colorOnSurface
                        isComplete -> R.color.colorPrimary
                        else -> R.color.profile_text_muted
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
        return java.text.SimpleDateFormat("dd MMM", Locale.FRENCH).format(estimate)
    }

    private fun buildEstimatedDeliveryTimestamp(): Long {
        val isStandard = viewModel.isStandardSelected.value ?: true
        val daysToAdd = if (isStandard) 4L else 2L
        return System.currentTimeMillis() + daysToAdd * 24L * 60L * 60L * 1000L
    }

    private fun String?.ifNullOrBlank(fallback: () -> String): String {
        return if (this.isNullOrBlank()) fallback() else this
    }
}
