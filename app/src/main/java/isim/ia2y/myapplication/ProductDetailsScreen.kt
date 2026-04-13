package isim.ia2y.myapplication

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import com.google.android.material.card.MaterialCardView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class ProductDetailsScreen : AppCompatActivity() {
    private var quantity: Int = 1
    private lateinit var product: Product

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_product_details)

        val topBar = findViewById<View>(R.id.layoutTopBar)
        val bottomBar = findViewById<View>(R.id.layoutBottomBar)
        val topBarBaseHeight = resources.getDimensionPixelSize(R.dimen.app_top_bar_height)
        val topBarBasePaddingStart = topBar.paddingStart
        val topBarBasePaddingEnd = topBar.paddingEnd
        val topBarBasePaddingBottom = topBar.paddingBottom
        val bottomBarBasePaddingLeft = bottomBar.paddingLeft
        val bottomBarBasePaddingTop = bottomBar.paddingTop
        val bottomBarBasePaddingRight = bottomBar.paddingRight
        val bottomBarBasePaddingBottom = bottomBar.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, 0)
            topBar.updateLayoutParams<ViewGroup.LayoutParams> {
                height = topBarBaseHeight + systemBars.top
            }
            topBar.setPadding(
                topBarBasePaddingStart,
                systemBars.top,
                topBarBasePaddingEnd,
                topBarBasePaddingBottom
            )
            bottomBar.setPadding(
                bottomBarBasePaddingLeft,
                bottomBarBasePaddingTop,
                bottomBarBasePaddingRight,
                bottomBarBasePaddingBottom + systemBars.bottom
            )
            insets
        }

        val productId = intent.getStringExtra(EXTRA_PRODUCT_ID).orEmpty()
        val loadedProduct = ProductCatalog.byId(productId)
        
        if (loadedProduct == null) {
            findViewById<View>(R.id.layoutContent)?.visibility = View.GONE
            findViewById<View>(R.id.layoutBottomBar)?.visibility = View.GONE
            findViewById<View>(R.id.loadingIndicator)?.visibility = View.VISIBLE
            lifecycleScope.launch {
                val remoteProduct = runCatching { ProductService.fetchProduct(productId) }.getOrNull()
                if (remoteProduct == null) {
                    findViewById<View>(R.id.loadingIndicator)?.visibility = View.GONE
                    showToast(getString(R.string.details_product_not_found))
                    finishWithMotion()
                } else {
                    ProductCatalog.upsert(remoteProduct)
                    product = remoteProduct
                    findViewById<View>(R.id.loadingIndicator)?.visibility = View.GONE
                    findViewById<View>(R.id.layoutContent)?.visibility = View.VISIBLE
                    findViewById<View>(R.id.layoutBottomBar)?.visibility = View.VISIBLE
                    applyResponsiveLayout()
                    bindUi()
                    bindActions()
                    animateEntry()
                }
            }
            return
        }
        product = loadedProduct

        findViewById<View>(R.id.loadingIndicator)?.visibility = View.GONE
        applyResponsiveLayout()
        bindUi()
        bindActions()
        animateEntry()
    }

    private fun bindUi() {
        findViewById<TextView>(R.id.tvTopTitle)?.text = getString(R.string.details_top_title)
        findViewById<ImageView>(R.id.ivProductImage)
            ?.loadCatalogImage(product.imageUrl, product.imageRes)
        findViewById<TextView>(R.id.tvTag1)?.text =
            product.tags.getOrNull(0)?.takeIf { it.isNotBlank() } ?: getString(R.string.product_default_tag)

        val tag2 = findViewById<TextView>(R.id.tvTag2)
        val secondTag = product.tags.getOrNull(1)
        if (secondTag.isNullOrBlank()) {
            tag2?.visibility = View.GONE
        } else {
            tag2?.visibility = View.VISIBLE
            tag2?.text = secondTag
            val backgroundRes = if (product.stock <= 0) {
                R.drawable.bg_details_chip_premium
            } else {
                R.drawable.bg_details_chip_success
            }
            tag2?.setBackgroundResource(backgroundRes)
            val textColor = if (product.stock <= 0) {
                R.color.home_text_primary
            } else {
                R.color.colorAccentDeep
            }
            tag2?.setTextColor(ContextCompat.getColor(this, textColor))
        }

        findViewById<TextView>(R.id.tvRating)?.text = String.format(Locale.US, "%.1f", product.rating)
        findViewById<TextView>(R.id.tvReviews)?.apply {
            text = getString(R.string.details_reviews_count, product.reviewsCount)
            visibility = if (product.reviewsCount > 0) View.VISIBLE else View.GONE
        }
        findViewById<TextView>(R.id.tvTitle)?.text = product.title
        findViewById<TextView>(R.id.tvPrice)?.text = formatDt(product.price)
        findViewById<TextView>(R.id.tvDescription)?.text = product.description
        findViewById<TextView>(R.id.tvStockHelper)?.apply {
            text = when {
                product.stock <= 0 -> getString(R.string.product_state_out_of_stock)
                product.stock <= 5 -> getString(R.string.product_state_low_stock, product.stock)
                else -> getString(R.string.details_stock_helper)
            }
            setTextColor(
                ContextCompat.getColor(
                    context,
                    if (product.stock <= 0) R.color.colorError else R.color.home_text_primary
                )
            )
        }

        val bullets = product.bullets
        bindBullet(R.id.tvBullet1, bullets.getOrNull(0))
        bindBullet(R.id.tvBullet2, bullets.getOrNull(1))
        bindBullet(R.id.tvBullet3, bullets.getOrNull(2))
        val hasHighlights = bullets.any { it.isNotBlank() }
        findViewById<View>(R.id.cardHighlights)?.visibility = if (hasHighlights) View.VISIBLE else View.GONE

        if (product.stock <= 0) {
            quantity = 0
        } else if (quantity < 1) {
            quantity = 1
        }

        updateQuantityUi()

        findViewById<TextView>(R.id.btnAddToCart)?.apply {
            isEnabled = product.stock > 0
            alpha = if (product.stock > 0) 1f else 0.6f
            text = context.getString(
                if (product.stock > 0) R.string.details_add_to_cart else R.string.product_state_out_of_stock_short
            )
        }
        
        // Favorite state setup (Lottie Heart)
        val lottieHeart = findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.ivFavoriteLottie)
        val isFav = FavoritesStore.isFavorite(this, product.id)
        if (isFav) {
            lottieHeart?.progress = 1f // Full heart
        } else {
            lottieHeart?.progress = 0f // Empty heart
        }
    }

    private fun bindActions() {
        findViewById<View>(R.id.ivBack)?.setOnClickListener { finishWithMotion() }

        findViewById<View>(R.id.ivShare)?.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    "${product.title}\n${formatDt(product.price)}\n${product.description}"
                )
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.details_share)))
        }

        findViewById<View>(R.id.btnMinus)?.setOnClickListener {
            it.performLightHapticFeedback()
            quantity = (quantity - 1).coerceAtLeast(1)
            updateQuantityUi()
        }

        findViewById<View>(R.id.btnPlus)?.setOnClickListener {
            it.performLightHapticFeedback()
            quantity = (quantity + 1).coerceAtMost(product.stock.coerceAtLeast(1))
            updateQuantityUi()
        }

        findViewById<View>(R.id.btnAddToCart)?.setOnClickListener {
            it.performLightHapticFeedback()
            val addedQuantity = CartStore.add(this, product.id, quantity)
            when {
                addedQuantity == quantity -> {
                    showMotionSnackbar(getString(R.string.details_added_to_cart, quantity), R.id.layoutBottomBar)
                }
                addedQuantity > 0 -> {
                    showMotionSnackbar(
                        getString(R.string.details_added_partial_to_cart, addedQuantity),
                        R.id.layoutBottomBar
                    )
                }
                else -> {
                    showMotionSnackbar(getString(R.string.product_stock_limit_reached), R.id.layoutBottomBar)
                }
            }
        }

        val lottieHeart = findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.ivFavoriteLottie)
        findViewById<View>(R.id.btnFavoriteDetails)?.setOnClickListener {
            it.performLightHapticFeedback()
            val isNowFav = FavoritesStore.toggleFavorite(this, product.id)
            if (isNowFav) {
                lottieHeart?.apply {
                    speed = 1f // Play forward
                    playAnimation()
                }
            } else {
                lottieHeart?.apply {
                    speed = -2f // Play backward quickly
                    playAnimation()
                }
            }
        }

        applyPressFeedback(R.id.ivBack, R.id.ivShare, R.id.btnFavoriteDetails, R.id.btnMinus, R.id.btnPlus, R.id.btnAddToCart)
    }

    private fun bindBullet(viewId: Int, bullet: String?) {
        findViewById<TextView>(viewId)?.apply {
            val value = bullet?.trim().orEmpty()
            text = value
            visibility = if (value.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun updateQuantityUi() {
        val inStock = product.stock > 0
        findViewById<TextView>(R.id.tvQuantity)?.text = if (inStock) quantity.toString() else "0"
        findViewById<TextView>(R.id.tvTotalValue)?.text = formatDt(product.price * quantity.coerceAtLeast(0))
        findViewById<View>(R.id.btnMinus)?.apply {
            isEnabled = inStock && quantity > 1
            alpha = if (isEnabled) 1f else 0.45f
        }
        findViewById<View>(R.id.btnPlus)?.apply {
            isEnabled = inStock && quantity < product.stock
            alpha = if (isEnabled) 1f else 0.45f
        }
    }

    private fun animateEntry() {
        if (isReducedMotionEnabled()) return

        val content = findViewById<View>(R.id.layoutContent) ?: return
        content.alpha = 0f
        content.translationY = resources.getDimension(R.dimen.details_content_entry_translation_y)
        content.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(MotionTokens.EMPHASIS)
            .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
            .start()
    }

    private fun applyResponsiveLayout() {
        val imageHeader = findViewById<View>(R.id.layoutImageHeader) ?: return
        val imageCard = findViewById<MaterialCardView>(R.id.cardProductImage) ?: return
        val quantitySelector = findViewById<View>(R.id.layoutQuantitySelector) ?: return

        val res = resources
        val imageHeaderParams = imageHeader.layoutParams
        val quantityParams = quantitySelector.layoutParams as? ViewGroup.LayoutParams

        when (screenWidthClass()) {
            ScreenWidthClass.EXPANDED -> {
                imageHeaderParams.height = res.getDimensionPixelSize(R.dimen.details_responsive_image_header_height_expanded)
                imageCard.radius = res.getDimension(R.dimen.corner_radius_large)
                quantityParams?.width = res.getDimensionPixelSize(R.dimen.details_responsive_quantity_selector_width_expanded)
            }
            ScreenWidthClass.MEDIUM -> {
                imageHeaderParams.height = res.getDimensionPixelSize(R.dimen.details_responsive_image_header_height_medium)
                imageCard.radius = res.getDimension(R.dimen.corner_radius_large)
                quantityParams?.width = res.getDimensionPixelSize(R.dimen.details_responsive_quantity_selector_width_medium)
            }
            ScreenWidthClass.COMPACT -> {
                imageHeaderParams.height = res.getDimensionPixelSize(R.dimen.details_responsive_image_header_height_compact)
                imageCard.radius = res.getDimension(R.dimen.corner_radius_large)
                quantityParams?.width = res.getDimensionPixelSize(R.dimen.details_responsive_quantity_selector_width_compact)
            }
        }

        imageHeader.layoutParams = imageHeaderParams
        quantitySelector.layoutParams = quantityParams
    }

    companion object {
        const val ROUTE_PATTERN = "details/{productId}"
        private const val EXTRA_PRODUCT_ID = "extra_product_id"

        fun route(productId: String): String = "details/$productId"

        fun createIntent(context: Context, productId: String): Intent {
            return Intent(context, ProductDetailsScreen::class.java)
                .putExtra(EXTRA_PRODUCT_ID, productId)
        }
    }
}
