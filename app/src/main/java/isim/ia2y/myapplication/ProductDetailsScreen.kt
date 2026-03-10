package isim.ia2y.myapplication

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class ProductDetailsScreen : AppCompatActivity() {
    private var quantity: Int = 1
    private lateinit var product: Product

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_product_details)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
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

        bindUi()
        bindActions()
        animateEntry()
    }

    private fun bindUi() {
        findViewById<ImageView>(R.id.ivProductImage)?.setImageResourceSafe(product.imageRes)
        findViewById<TextView>(R.id.tvTag1)?.text = product.tags.getOrNull(0).orEmpty()

        val tag2 = findViewById<TextView>(R.id.tvTag2)
        val secondTag = product.tags.getOrNull(1)
        if (secondTag.isNullOrBlank()) {
            tag2?.visibility = View.GONE
        } else {
            tag2?.visibility = View.VISIBLE
            tag2?.text = secondTag
        }

        findViewById<TextView>(R.id.tvRating)?.text = String.format("%.1f", product.rating)
        findViewById<TextView>(R.id.tvReviews)?.text = getString(R.string.details_reviews_count, product.reviewsCount)
        findViewById<TextView>(R.id.tvTitle)?.text = product.title
        findViewById<TextView>(R.id.tvPrice)?.text = formatDt(product.price)
        findViewById<TextView>(R.id.tvDescription)?.text = product.description

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
            showMotionSnackbar(getString(R.string.details_share_todo))
        }

        findViewById<View>(R.id.btnMinus)?.setOnClickListener {
            quantity = (quantity - 1).coerceAtLeast(1)
            updateQuantityUi()
        }

        findViewById<View>(R.id.btnPlus)?.setOnClickListener {
            quantity += 1
            updateQuantityUi()
        }

        findViewById<View>(R.id.btnAddToCart)?.setOnClickListener {
            repeat(quantity) { CartStore.addOne(this, product.id) }
            showMotionSnackbar(getString(R.string.details_added_to_cart, quantity), R.id.layoutBottomBar)
        }

        val lottieHeart = findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.ivFavoriteLottie)
        lottieHeart?.setOnClickListener {
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
    }

    private fun animateEntry() {
        if (isReducedMotionEnabled()) return

        val content = findViewById<View>(R.id.layoutContent) ?: return
        content.alpha = 0f
        content.translationY = 22f * resources.displayMetrics.density
        content.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(340L)
            .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
            .start()
    }

    private fun ImageView.setImageResourceSafe(imageRes: Int) {
        runCatching { setImageResource(imageRes) }
            .onFailure { setImageResource(R.drawable.placeholder) }
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

