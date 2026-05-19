package isim.ia2y.myapplication

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.airbnb.lottie.LottieAnimationView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class CartTabFragment : Fragment(R.layout.fragment_cart_tab) {
    private lateinit var emptyState: View
    private lateinit var loadingState: View
    private lateinit var emptyText: TextView
    private lateinit var emptyAnimation: LottieAnimationView
    private lateinit var itemsContainer: LinearLayout
    private lateinit var syncBanner: View
    private lateinit var syncBannerTitle: TextView
    private lateinit var syncBannerMessage: TextView
    private lateinit var syncBannerAction: MaterialButton
    private var summaryGap: View? = null
    private var summaryCard: View? = null
    private lateinit var subtotalValue: TextView
    private lateinit var livraisonValue: TextView
    private lateinit var totalValue: TextView
    private var checkoutButton: MaterialButton? = null
    private var cartCountChip: TextView? = null
    private var shouldAnimateListOnNextRender = true
    private var displayedShippingFee = CartStore.LIVRAISON_FEE
    private var lastCartSyncErrorVersion = 0L
    private var hasRenderedCartOnce = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View?>(R.id.layoutBottomNav)?.isGone = true
        view.findViewById<View?>(R.id.viewBottomDivider)?.isGone = true
        setupPanierActions(view)
        bindViews(view)
        observeCartSyncState()
        showCartLoadingState()
        view.post {
            if (isAdded && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                renderCart()
            }
        }
        loadCommerceConfig()
        (activity as? AppCompatActivity)?.applyPressFeedback(
            R.id.ivHomeLogo,
            R.id.tvBrand,
            R.id.chatContainer,
            R.id.ivTopNotifications,
            R.id.btnCheckout,
            R.id.btnEmptyCartBrowse
        )
        (activity as? AppCompatActivity)?.revealViewsInOrder(
            R.id.layoutTopSection,
            R.id.scrollPanierContent
        )
    }

    override fun onStart() {
        super.onStart()
        // Reset animation flag each time the fragment becomes visible again
        shouldAnimateListOnNextRender = true
    }

    override fun onResume() {
        super.onResume()
        loadCommerceConfig()
        AnalyticsTracker.viewCart(
            itemCount = CartStore.itemCount(requireContext()),
            value = CartStore.subtotal(requireContext())
        )
        renderCart()
        (activity as? MainActivity)?.updateHostCartBadge()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) return
        if (!isAdded || view == null) return
        if (!::itemsContainer.isInitialized) return
        renderCart()
        (activity as? MainActivity)?.updateHostCartBadge()
    }

    private fun setupPanierActions(root: View) {
        root.findViewById<View>(R.id.ivHomeLogo)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.HOME, animate = false)
        }
        root.findViewById<View>(R.id.tvBrand)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.HOME, animate = false)
        }
        root.findViewById<View>(R.id.chatContainer)?.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateNoShift(ChatActivity::class.java)
        }
        (activity as? AppCompatActivity)?.bindNotificationEntry(R.id.ivTopNotifications)
        root.findViewById<View>(R.id.btnEmptyCartBrowse)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.HOME, animate = false)
        }

        root.findViewById<View>(R.id.btnCheckout)?.setOnClickListener {
            if (CartStore.itemCount(requireContext()) > 0) {
                (activity as? AppCompatActivity)?.navigateWithMotion(CheckoutDetailsActivity::class.java)
            }
        }
    }

    private fun bindViews(root: View) {
        emptyState = root.findViewById(R.id.layoutEmptyCartState)
        loadingState = root.findViewById(R.id.layoutCartLoadingState)
        emptyText = root.findViewById(R.id.tvEmptyCart)
        emptyAnimation = root.findViewById(R.id.ivEmptyCartAnimation)
        itemsContainer = root.findViewById(R.id.layoutCartItemsContainer)
        syncBanner = root.findViewById(R.id.cardCartSyncBanner)
        syncBannerTitle = root.findViewById(R.id.tvCartSyncTitle)
        syncBannerMessage = root.findViewById(R.id.tvCartSyncMessage)
        syncBannerAction = root.findViewById(R.id.btnCartSyncRetry)
        syncBannerAction.setOnClickListener {
            CartStore.retryCloudSync(requireContext())
        }
        summaryGap = root.findViewById(R.id.spaceBeforeSummary)
        summaryCard = root.findViewById(R.id.cardSummary)
        subtotalValue = root.findViewById(R.id.tvSubtotalValue)
        livraisonValue = root.findViewById(R.id.tvLivraisonValue)
        totalValue = root.findViewById(R.id.tvTotalValue)
        checkoutButton = root.findViewById(R.id.btnCheckout)
        cartCountChip = root.findViewById(R.id.tvCartCountChip)
    }

    private fun observeCartSyncState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                CartStore.syncState.collect { state ->
                    renderCartSyncBanner(state)
                    val hasNewError = state.errorMessage != null &&
                        !state.pendingRetry &&
                        state.version != lastCartSyncErrorVersion
                    if (hasNewError) {
                        lastCartSyncErrorVersion = state.version
                        (activity as? AppCompatActivity)?.showMotionSnackbar(getString(R.string.cart_sync_failed))
                    }
                }
            }
        }
    }

    private fun renderCartSyncBanner(state: CartStore.CartSyncState) {
        if (!::syncBanner.isInitialized) return
        val shouldShow = state.pendingRetry || state.errorMessage != null
        syncBanner.visibility = if (shouldShow) View.VISIBLE else View.GONE
        if (!shouldShow) return

        val isRetrying = state.pendingRetry
        syncBannerTitle.setText(
            if (isRetrying) R.string.cart_sync_banner_retrying_title else R.string.cart_sync_banner_failed_title
        )
        syncBannerMessage.setText(
            if (isRetrying) R.string.cart_sync_banner_retrying_message else R.string.cart_sync_banner_failed_message
        )
        syncBannerAction.visibility = if (isRetrying) View.GONE else View.VISIBLE
    }

    private fun loadCommerceConfig() {
        lifecycleScope.launch {
            runCatching { FirestoreService.fetchCommerceConfig() }
                .onSuccess { config ->
                    val nextFee = config.standardShippingFee.coerceAtLeast(0.0)
                    if (displayedShippingFee != nextFee) {
                        displayedShippingFee = nextFee
                        if (isAdded && view != null) {
                            renderCart()
                        }
                    }
                }
        }
    }

    private fun renderCart() {
        (activity as? MainActivity)?.updateHostCartBadge()
        hasRenderedCartOnce = true
        loadingState.visibility = View.GONE
        itemsContainer.removeAllViews()

        var cart = CartStore.getCart(requireContext())
        val knownProductIds = ProductCatalog.orderedFavorites(cart.keys).mapTo(linkedSetOf()) { it.id }
        val staleProductIds = cart.keys.filterNot { it in knownProductIds }
        if (staleProductIds.isNotEmpty()) {
            staleProductIds.forEach { CartStore.remove(requireContext(), it) }
            cart = CartStore.getCart(requireContext())
        }
        val lines = ProductCatalog.orderedFavorites(cart.keys).mapNotNull { product ->
            val qty = cart[product.id] ?: 0
            if (qty <= 0) null else product to qty
        }

        val hasItems = lines.isNotEmpty()
        val itemCount = cart.values.sum()
        cartCountChip?.text = resources.getQuantityString(
            R.plurals.cart_count_chip,
            itemCount,
            itemCount
        )
        emptyState.visibility = if (hasItems) View.GONE else View.VISIBLE
        itemsContainer.visibility = if (hasItems) View.VISIBLE else View.GONE
        summaryGap?.visibility = if (hasItems) View.VISIBLE else View.GONE
        summaryCard?.visibility = if (hasItems) View.VISIBLE else View.GONE
        checkoutButton?.isEnabled = hasItems
        checkoutButton?.alpha = if (hasItems) 1f else 0.52f

        if (!hasItems) {
            emptyText.text = getString(R.string.auto_text_097)
            emptyAnimation.playAnimation()
            return
        }

        emptyAnimation.pauseAnimation()

        val inflater = LayoutInflater.from(requireContext())
        lines.forEachIndexed { index, (product, qty) ->
            val row = inflater.inflate(R.layout.item_panier_product_dynamic, itemsContainer, false)
            val params = (row.layoutParams as? LinearLayout.LayoutParams)
                ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            params.topMargin = if (index == 0) 0 else resources.getDimensionPixelSize(R.dimen.panier_product_card_spacing)
            row.layoutParams = params

            row.findViewById<ImageView>(R.id.ivCartItemImage)
                ?.loadCatalogImage(product.previewImageUrl(), product.catalogFallbackImageRes())
            row.findViewById<TextView>(R.id.tvCartItemTitle)?.text = product.title
            row.findViewById<TextView>(R.id.tvCartItemSubtitle)?.apply {
                text = product.subtitle
                visibility = if (product.subtitle.isBlank()) View.GONE else View.VISIBLE
            }
            row.findViewById<TextView>(R.id.tvCartItemPrice)?.text = formatDt(product.unitPrice * qty)
            row.findViewById<TextView>(R.id.tvQtyValue)?.text = qty.toString()

            row.findViewById<View>(R.id.btnRemoveItem)?.setOnClickListener {
                CartStore.remove(requireContext(), product.id)
                shouldAnimateListOnNextRender = false
                renderCart()
            }
            row.findViewById<View>(R.id.btnQtyMinus)?.setOnClickListener {
                CartStore.decrement(requireContext(), product.id)
                shouldAnimateListOnNextRender = false
                renderCart()
            }
            row.findViewById<View>(R.id.btnQtyPlus)?.setOnClickListener {
                CartStore.increment(requireContext(), product.id)
                shouldAnimateListOnNextRender = false
                renderCart()
            }
            row.setOnClickListener {
                (activity as? AppCompatActivity)?.navigateToProductDetails(product.id)
            }

            itemsContainer.addView(row)
            if (shouldAnimateListOnNextRender) {
                (activity as? AppCompatActivity)?.animateListItemEntry(row, index, startDelayMs = 45L)
            }
        }

        val subtotal = lines.sumOf { (product, qty) -> product.unitPrice * qty }
        val livraison = displayedShippingFee
        subtotalValue.text = formatDt(subtotal)
        livraisonValue.text = formatDt(livraison)
        totalValue.text = formatDt(subtotal + livraison)
        if (shouldAnimateListOnNextRender) {
            (activity as? AppCompatActivity)?.revealSingleView(R.id.cardSummary)
        }
        shouldAnimateListOnNextRender = false
    }

    private fun showCartLoadingState() {
        if (!::loadingState.isInitialized || hasRenderedCartOnce) return
        loadingState.visibility = View.VISIBLE
        emptyState.visibility = View.GONE
        itemsContainer.visibility = View.GONE
        summaryGap?.visibility = View.GONE
        summaryCard?.visibility = View.GONE
        checkoutButton?.isEnabled = false
        checkoutButton?.alpha = 0.52f
    }
}
