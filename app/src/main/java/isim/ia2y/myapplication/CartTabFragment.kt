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
import com.google.android.material.button.MaterialButton

class CartTabFragment : Fragment(R.layout.fragment_cart_tab) {
    private lateinit var emptyText: TextView
    private lateinit var itemsContainer: LinearLayout
    private lateinit var summaryGap: View
    private lateinit var summaryCard: View
    private lateinit var subtotalValue: TextView
    private lateinit var livraisonValue: TextView
    private lateinit var totalValue: TextView
    private var shouldAnimateListOnNextRender = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View?>(R.id.layoutBottomNav)?.isGone = true
        view.findViewById<View?>(R.id.viewBottomDivider)?.isGone = true
        view.findViewById<View?>(R.id.layoutTopSection)?.isGone = true
        setupPanierActions(view)
        bindViews(view)
        renderCart()
        (activity as? AppCompatActivity)?.applyPressFeedback(
            R.id.ivHomeLogo,
            R.id.tvBrand,
            R.id.ivTopCart,
            R.id.ivTopNotifications,
            R.id.btnCheckout
        )
        (activity as? AppCompatActivity)?.revealViewsInOrder(
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
        root.findViewById<View>(R.id.ivTopCart)?.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateFromTop(favoris::class.java)
        }
        (activity as? AppCompatActivity)?.bindNotificationEntry(R.id.ivTopNotifications)

        root.findViewById<View>(R.id.btnCheckout)?.setOnClickListener {
            if (CartStore.itemCount(requireContext()) > 0) {
                (activity as? AppCompatActivity)?.navigateWithMotion(CheckoutDetailsActivity::class.java)
            }
        }
    }

    private fun bindViews(root: View) {
        emptyText = root.findViewById(R.id.tvEmptyCart)
        itemsContainer = root.findViewById(R.id.layoutCartItemsContainer)
        summaryGap = root.findViewById(R.id.spaceBeforeSummary)
        summaryCard = root.findViewById(R.id.cardSummary)
        subtotalValue = root.findViewById(R.id.tvSubtotalValue)
        livraisonValue = root.findViewById(R.id.tvLivraisonValue)
        totalValue = root.findViewById(R.id.tvTotalValue)
    }

    private fun renderCart() {
        (activity as? MainActivity)?.updateHostCartBadge()
        itemsContainer.removeAllViews()

        val cart = CartStore.getCart(requireContext())
        val lines = ProductCatalog.orderedFavorites(cart.keys).mapNotNull { product ->
            val qty = cart[product.id] ?: 0
            if (qty <= 0) null else product to qty
        }

        val hasItems = lines.isNotEmpty()
        emptyText.visibility = if (hasItems) View.GONE else View.VISIBLE
        itemsContainer.visibility = if (hasItems) View.VISIBLE else View.GONE
        summaryGap.visibility = if (hasItems) View.VISIBLE else View.GONE
        summaryCard.visibility = if (hasItems) View.VISIBLE else View.GONE
        view?.findViewById<MaterialButton?>(R.id.btnCheckout)?.isEnabled = hasItems

        if (!hasItems) return

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

            row.findViewById<ImageView>(R.id.ivCartItemImage)?.setImageResource(product.imageRes)
            row.findViewById<TextView>(R.id.tvCartItemTitle)?.text = product.title
            row.findViewById<TextView>(R.id.tvCartItemSubtitle)?.text = product.subtitle
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
        val livraison = CartStore.LIVRAISON_FEE
        subtotalValue.text = formatDt(subtotal)
        livraisonValue.text = formatDt(livraison)
        totalValue.text = formatDt(subtotal + livraison)
        if (shouldAnimateListOnNextRender) {
            (activity as? AppCompatActivity)?.revealSingleView(R.id.cardSummary)
        }
        shouldAnimateListOnNextRender = false
    }
}
