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
import com.google.android.material.card.MaterialCardView
import java.util.Locale

class ProductDetailsScreen : AppCompatActivity() {
    private var quantity: Int = 1
    private lateinit var product: Product

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_product_details)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, 0)
            
            findViewById<View>(R.id.layoutTopBar)?.apply {
                setPadding(paddingLeft, systemBars.top, paddingRight, paddingBottom)
            }
            
            findViewById<View>(R.id.layoutBottomBar)?.apply {
                setPadding(paddingLeft, paddingTop, paddingRight, systemBars.bottom + 12)
            }
            
            insets
        }

        val productId = intent.getStringExtra(EXTRA_PRODUCT_ID).orEmpty()
        val loadedProduct = ProductCatalog.byId(productId)
        if (loadedProduct == null) {
            showToast(getString(R.string.details_product_not_found))
            finishWithMotion()
            return
        }
        product = loadedProduct

        applyResponsiveLayout()
        bindUi()
        bindActions()
        animateEntry()
    }

    private fun bindUi() {
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
        findViewById<TextView>(R.id.tvReviews)?.text = getString(R.string.details_reviews_count, product.reviewsCount)
        findViewById<TextView>(R.id.tvTitle)?.text = product.title
        findViewById<TextView>(R.id.tvPrice)?.text = formatDt(product.price)
        findViewById<TextView>(R.id.tvDescription)?.text = product.description
        findViewById<TextView>(R.id.tvStockHelper)?.text = when {
            product.stock <= 0 -> getString(R.string.product_state_out_of_stock)
            product.stock <= 5 -> getString(R.string.product_state_low_stock, product.stock)
            else -> getString(R.string.details_stock_helper)
        }

        val bullets = product.bullets
        findViewById<TextView>(R.id.tvBullet1)?.text = bullets.getOrNull(0).orEmpty()
        findViewById<TextView>(R.id.tvBullet2)?.text = bullets.getOrNull(1).orEmpty()
        findViewById<TextView>(R.id.tvBullet3)?.text = bullets.getOrNull(2).orEmpty()

        updateQuantityUi()
        
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
        lottieHeart?.setOnClickListener {
            it.performLightHapticFeedback()
            val isNowFav = FavoritesStore.toggleFavorite(this, product.id)
            if (isNowFav) {
                lottieHeart.apply {
                    speed = 1f // Play forward
                    playAnimation()
                }
            } else {
                lottieHeart.apply {
                    speed = -2f // Play backward quickly
                    playAnimation()
                }
            }
        }

        applyPressFeedback(R.id.ivBack, R.id.ivShare, R.id.btnMinus, R.id.btnPlus, R.id.btnAddToCart)
    }

    private fun updateQuantityUi() {
        findViewById<TextView>(R.id.tvQuantity)?.text = quantity.toString()
        findViewById<TextView>(R.id.tvTotalValue)?.text = formatDt(product.price * quantity)
        findViewById<View>(R.id.btnPlus)?.alpha = if (quantity >= product.stock.coerceAtLeast(1)) 0.45f else 1f
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
                imageCard.radius = res.getDimension(R.dimen.details_responsive_product_card_radius_expanded)
                quantityParams?.width = res.getDimensionPixelSize(R.dimen.details_responsive_quantity_selector_width_expanded)
            }
            ScreenWidthClass.MEDIUM -> {
                imageHeaderParams.height = res.getDimensionPixelSize(R.dimen.details_responsive_image_header_height_medium)
                imageCard.radius = res.getDimension(R.dimen.details_responsive_product_card_radius_medium)
                quantityParams?.width = res.getDimensionPixelSize(R.dimen.details_responsive_quantity_selector_width_medium)
            }
            ScreenWidthClass.COMPACT -> {
                imageHeaderParams.height = res.getDimensionPixelSize(R.dimen.details_responsive_image_header_height_compact)
                imageCard.radius = res.getDimension(R.dimen.details_responsive_product_card_radius_compact)
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

