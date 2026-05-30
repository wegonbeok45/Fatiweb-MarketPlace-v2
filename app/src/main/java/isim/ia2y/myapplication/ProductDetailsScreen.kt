package isim.ia2y.myapplication

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RatingBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Source
import com.google.firebase.functions.FirebaseFunctionsException
import isim.ia2y.myapplication.databinding.ActivityProductDetailsBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProductDetailsScreen : AppCompatActivity() {
    private lateinit var binding: ActivityProductDetailsBinding
    private var quantity: Int = 1
    private var product: Product? = null
    private var galleryUrls: List<String> = emptyList()
    private var galleryIndex: Int = 0
    private lateinit var galleryAdapter: ProductImagePagerAdapter
    private var reviewCursor: DocumentSnapshot? = null
    private var reviewsReachedEnd: Boolean = true
    private var productId: String = ""
    private var isOpeningSellerConversation: Boolean = false
    private var loadedReviews: List<ProductReview> = emptyList()
    private var selectedRating: Int = 0
    private var selectedColorName: String? = null
    private var selectedSize: String? = null
    private val reviewStarIds = intArrayOf(
        R.id.ivReviewStar1,
        R.id.ivReviewStar2,
        R.id.ivReviewStar3,
        R.id.ivReviewStar4,
        R.id.ivReviewStar5
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityProductDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val bottomBar = binding.layoutBottomBar
        val bottomBarBasePaddingLeft = bottomBar.paddingLeft
        val bottomBarBasePaddingTop = bottomBar.paddingTop
        val bottomBarBasePaddingRight = bottomBar.paddingRight
        val bottomBarBasePaddingBottom = bottomBar.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            bottomBar.setPadding(
                bottomBarBasePaddingLeft,
                bottomBarBasePaddingTop,
                bottomBarBasePaddingRight,
                bottomBarBasePaddingBottom + systemBars.bottom
            )
            insets
        }

        productId = intent.getStringExtra(EXTRA_PRODUCT_ID).orEmpty()
        val loadedProduct = ProductCatalog.byId(productId)
        binding.ivBack.setOnClickListener { finishWithMotion() }
        binding.btnDetailsRetry.setOnClickListener {
            it.performLightHapticFeedback()
            loadRemoteProduct(productId, isRetry = true)
        }
        
        galleryAdapter = ProductImagePagerAdapter(R.drawable.placeholder)
        binding.pagerProductImages.adapter = galleryAdapter
        binding.pagerProductImages.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                galleryIndex = position
                renderGalleryDots(galleryUrls.size)
            }
        })

        if (loadedProduct == null) {
            loadRemoteProduct(productId)
            return
        }
        showLoadedProduct(loadedProduct)
    }

    private fun loadRemoteProduct(productId: String, isRetry: Boolean = false) {
        showDetailsLoading()
        lifecycleScope.launch {
            val result = runCatching { ProductService.fetchProduct(productId) }
            if (isFinishing || isDestroyed) return@launch
            val remoteProduct = result.getOrNull()
            when {
                remoteProduct != null -> {
                    ProductCatalog.upsert(remoteProduct)
                    showLoadedProduct(remoteProduct)
                }
                result.isSuccess && isRetry -> {
                    showMotionSnackbar(getString(R.string.details_product_not_found))
                    finishWithMotion()
                }
                result.isSuccess -> {
                    showDetailsError(
                        title = getString(R.string.details_product_not_found),
                        subtitle = getString(R.string.details_product_missing_retry_subtitle)
                    )
                }
                else -> {
                    val error = result.exceptionOrNull()
                        ?: IllegalStateException("Product load failed.")
                    CrashlyticsHelper.recordNonFatal(
                        "ProductDetails",
                        "Product load failed productId=$productId",
                        error
                    )
                    showDetailsError(
                        title = getString(R.string.details_load_error_title),
                        subtitle = getString(R.string.details_load_error_subtitle)
                    )
                }
            }
        }
    }

    private fun showLoadedProduct(loadedProduct: Product) {
        product = loadedProduct
        RecentlyViewedStore.record(this, loadedProduct.id)
        AnalyticsTracker.viewItem(loadedProduct)
        binding.loadingIndicator.visibility = View.GONE
        binding.layoutDetailsSkeleton.visibility = View.GONE
        binding.layoutDetailsErrorState.visibility = View.GONE
        binding.layoutContent.visibility = View.VISIBLE
        binding.layoutBottomBar.visibility = View.VISIBLE
        binding.actionCluster.visibility = View.VISIBLE
        applyResponsiveLayout()
        bindUi()
        bindActions()
        loadReviews()
        animateEntry()
    }

    private fun showDetailsLoading() {
        binding.layoutContent.visibility = View.GONE
        binding.layoutBottomBar.visibility = View.GONE
        binding.layoutDetailsErrorState.visibility = View.GONE
        binding.layoutDetailsSkeleton.visibility = View.VISIBLE
        binding.loadingIndicator.visibility = View.GONE
        binding.actionCluster.visibility = View.GONE
    }

    private fun showDetailsError(title: String, subtitle: String) {
        binding.layoutDetailsSkeleton.visibility = View.GONE
        binding.loadingIndicator.visibility = View.GONE
        binding.layoutContent.visibility = View.GONE
        binding.layoutBottomBar.visibility = View.GONE
        binding.tvDetailsErrorTitle.text = title
        binding.tvDetailsErrorSubtitle.text = subtitle
        binding.layoutDetailsErrorState.visibility = View.VISIBLE
        binding.actionCluster.visibility = View.GONE
    }

    private fun bindUi() {
        val product = product ?: return
        binding.tvTopTitle.text = getString(R.string.details_top_title)
        bindGallery()
        binding.tvTag1.text =
            product.tags.getOrNull(0)?.takeIf { it.isNotBlank() } ?: getString(R.string.product_default_tag)

        val tag2 = binding.tvTag2
        val secondTag = product.tags.getOrNull(1)
        if (secondTag.isNullOrBlank()) {
            tag2.visibility = View.GONE
        } else {
            tag2.visibility = View.VISIBLE
            tag2.text = secondTag
            val backgroundRes = if (product.stock <= 0) {
                R.drawable.bg_details_chip_premium
            } else {
                R.drawable.bg_details_chip_success
            }
            tag2.setBackgroundResource(backgroundRes)
            val textColor = if (product.stock <= 0) {
                R.color.home_text_primary
            } else {
                R.color.colorAccentDeep
            }
            tag2.setTextColor(ContextCompat.getColor(this, textColor))
        }

        binding.tvRating.text = if (product.reviewsCount > 0 && product.rating > 0.0) {
            String.format(Locale.US, "%.1f", product.rating)
        } else {
            getString(R.string.details_no_rating_short)
        }
        binding.tvReviews.apply {
            text = getString(R.string.details_reviews_count, product.reviewsCount)
            visibility = if (product.reviewsCount > 0) View.VISIBLE else View.GONE
        }
        binding.tvTitle.text = product.title
        binding.tvPrice.text = formatDt(product.unitPrice)
        if (product.hasDiscount) {
            binding.tvPriceOriginal.apply {
                text = formatDt(product.effectivePrice)
                paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                visibility = View.VISIBLE
            }
            binding.tvDiscountBadge.apply {
                text = context.getString(R.string.product_price_discount_badge, product.discountPercentClamped)
                visibility = View.VISIBLE
            }
        } else {
            binding.tvPriceOriginal.visibility = View.GONE
            binding.tvDiscountBadge.visibility = View.GONE
        }
        binding.tvSellerName.text = product.sellerDisplayName
        bindSellerTrust(product)
        binding.ivSellerAvatar.loadAvatarImage(product.sellerAvatarUrl.takeIf { it.isNotBlank() })
        refreshSellerAvatarIfNeeded(product)
        setupDescriptionCollapse(product.description)
        binding.tvStockHelper.apply {
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
        bindBullet(binding.tvBullet1, bullets.getOrNull(0))
        bindBullet(binding.tvBullet2, bullets.getOrNull(1))
        bindBullet(binding.tvBullet3, bullets.getOrNull(2))
        val hasHighlights = bullets.any { it.isNotBlank() }
        binding.cardHighlights.visibility = if (hasHighlights) View.VISIBLE else View.GONE

        bindSpecs(product)
        bindVariantSelectors(product)

        if (product.stock <= 0) {
            quantity = 0
        } else if (quantity < 1) {
            quantity = 1
        }

        updateQuantityUi()

        binding.btnAddToCart.apply {
            isEnabled = product.stock > 0
            alpha = if (product.stock > 0) 1f else 0.6f
            text = context.getString(
                if (product.stock > 0) R.string.details_add_to_cart else R.string.product_state_out_of_stock_short
            )
        }
        
        // Favorite state setup (Lottie Heart)
        val isFav = FavoritesStore.isFavorite(this, product.id)
        if (isFav) {
            binding.ivFavoriteLottie.progress = 1f // Full heart
        } else {
            binding.ivFavoriteLottie.progress = 0f // Empty heart
        }
    }

    private fun bindActions() {
        val product = product ?: return
        binding.ivBack.setOnClickListener { finishWithMotion() }
        binding.btnGalleryPrev.setOnClickListener {
            it.performLightHapticFeedback()
            showGalleryImage(galleryIndex - 1)
        }
        binding.btnGalleryNext.setOnClickListener {
            it.performLightHapticFeedback()
            showGalleryImage(galleryIndex + 1)
        }
        binding.tvReviewCounter.text = getString(R.string.details_review_counter, 0, 280)
        binding.etReviewComment.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                binding.tvReviewCounter.text = getString(R.string.details_review_counter, s?.length ?: 0, 280)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        setupReviewStars()
        binding.btnWriteReview.setOnClickListener {
            val rating = selectedRating
            val comment = binding.etReviewComment.text?.toString()?.trim().orEmpty()
            when {
                rating < 1 -> {
                    binding.tvReviewStarsError.text = getString(R.string.details_review_rating_required)
                    binding.tvReviewStarsError.visibility = View.VISIBLE
                }
                comment.length < 3 -> {
                    binding.tvReviewStarsError.visibility = View.GONE
                    binding.etReviewComment.error = getString(R.string.details_review_comment_required)
                }
                else -> {
                    binding.tvReviewStarsError.visibility = View.GONE
                    submitReview(rating.coerceIn(1, 5), comment)
                }
            }
        }

        binding.ivShare.setOnClickListener {
            val deepLink = "https://fatiweb.app/p/${product.id}"
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    "${product.title}\n${formatMinorDt(product.priceMinor)}\n$deepLink\n\n${product.description}"
                )
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.details_share)))
        }

        val canContactSeller = product.sellerId.isNotBlank() &&
            FirebaseAuthManager.currentUser?.uid != product.sellerId
        binding.btnContactSeller.apply {
            visibility = if (canContactSeller) View.VISIBLE else View.GONE
            setOnClickListener {
                it.performLightHapticFeedback()
                openSellerConversation(product)
            }
        }

        binding.btnMinus.setOnClickListener {
            it.performLightHapticFeedback()
            quantity = (quantity - 1).coerceAtLeast(1)
            updateQuantityUi()
        }

        binding.btnPlus.setOnClickListener {
            it.performLightHapticFeedback()
            quantity = (quantity + 1).coerceAtMost(availableStock().coerceAtLeast(1))
            updateQuantityUi()
        }

        binding.btnAddToCart.setOnClickListener {
            it.performLightHapticFeedback()
            val selectionError = variantSelectionError(product)
            if (selectionError != null) {
                showMotionSnackbar(selectionError, R.id.layoutBottomBar)
                return@setOnClickListener
            }
            val variant = selectedVariant()
            val addedQuantity = CartStore.add(
                this,
                product.id,
                quantity,
                variantId = variant?.variantId,
                selectedColor = if (product.requiresColorSelection) selectedColorName else null,
                selectedSize = if (product.requiresSizeSelection) selectedSize else null
            )
            if (addedQuantity > 0) {
                AnalyticsTracker.addToCart(product, addedQuantity)
            }
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

        binding.btnFavoriteDetails.setOnClickListener {
            it.performLightHapticFeedback()
            val isNowFav = FavoritesStore.toggleFavorite(this, product.id)
            if (isNowFav) {
                binding.ivFavoriteLottie.apply {
                    speed = 1f // Play forward
                    playAnimation()
                }
            } else {
                binding.ivFavoriteLottie.apply {
                    speed = -2f // Play backward quickly
                    playAnimation()
                }
            }
        }
        applyPressFeedback(
            R.id.ivBack,
            R.id.ivShare,
            R.id.btnGalleryPrev,
            R.id.btnGalleryNext,
            R.id.btnContactSeller,
            R.id.btnFavoriteDetails,
            R.id.btnMinus,
            R.id.btnPlus,
            R.id.btnAddToCart
        )
    }

    private fun openSellerConversation(product: Product) {
        if (isOpeningSellerConversation) return
        if (FirebaseAuthManager.currentUser == null) {
            showMotionSnackbar("Sign in to contact the seller", R.id.layoutBottomBar)
            navigateNoShift(LoginActivity::class.java)
            return
        }
        isOpeningSellerConversation = true
        binding.btnContactSeller.apply {
            isEnabled = false
            alpha = 0.58f
        }
        lifecycleScope.launch {
            val result = runCatching {
                withTimeoutOrNull(12_000L) {
                    MessagingRepository.openOrCreateProductConversation(product)
                } ?: throw IllegalStateException("Opening seller chat timed out.")
            }
            if (isFinishing || isDestroyed) return@launch
            isOpeningSellerConversation = false
            binding.btnContactSeller.apply {
                isEnabled = true
                alpha = 1f
            }
            result
                .onSuccess { conversationId ->
                    startActivity(ConversationActivity.createIntent(this@ProductDetailsScreen, conversationId))
                }
                .onFailure {
                    CrashlyticsHelper.recordNonFatal("ProductDetails", "Open seller conversation failed productId=${product.id}", it)
                    showMotionSnackbar("Could not open seller chat", R.id.layoutBottomBar)
                }
        }
    }

    private fun bindGallery() {
        val product = product ?: return
        galleryUrls = product.imageUrls
            .filter { it.isNotBlank() }
            .distinct()
            .take(5)
            .ifEmpty { listOfNotNull(product.mainImageUrl()) }
        galleryIndex = galleryIndex.coerceIn(0, galleryUrls.lastIndex.coerceAtLeast(0))
        galleryAdapter = ProductImagePagerAdapter(product.catalogFallbackImageRes())
        binding.pagerProductImages.adapter = galleryAdapter
        galleryAdapter.submitList(galleryUrls.ifEmpty { listOf("") })
        binding.pagerProductImages.setCurrentItem(galleryIndex, false)
        showGalleryImage(galleryIndex)
    }

    private fun showGalleryImage(index: Int) {
        val product = product ?: return
        val count = galleryUrls.size
        val hasGallery = count > 1
        galleryIndex = when {
            count <= 0 -> 0
            index < 0 -> count - 1
            index >= count -> 0
            else -> index
        }
        if (binding.pagerProductImages.currentItem != galleryIndex) {
            binding.pagerProductImages.setCurrentItem(galleryIndex, true)
        }
        binding.btnGalleryPrev.apply {
            visibility = if (hasGallery) View.VISIBLE else View.GONE
            isEnabled = hasGallery
        }
        binding.btnGalleryNext.apply {
            visibility = if (hasGallery) View.VISIBLE else View.GONE
            isEnabled = hasGallery
        }
        renderGalleryDots(count)
    }

    private fun bindSellerTrust(product: Product) {
        binding.tvSellerVerifiedBadge.visibility = if (product.sellerVerifiedAt != null) View.VISIBLE else View.GONE
        val parts = buildList {
            if (product.sellerRating > 0.0) {
                add(getString(R.string.details_seller_rating, product.sellerRating))
            }
            if (product.sellerTotalSold > 0) {
                add(getString(R.string.details_seller_total_sold, product.sellerTotalSold))
            }
            if (product.sellerMemberSince != null) {
                add(getString(R.string.details_seller_member_since))
            }
        }
        binding.tvSellerTrustMeta.apply {
            text = parts.joinToString(" - ")
            visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun renderGalleryDots(count: Int) {
        val dots = binding.layoutGalleryDots
        dots.removeAllViews()
        dots.visibility = if (count > 1) View.VISIBLE else View.GONE
        repeat(count) { index ->
            dots.addView(View(this).apply {
                setBackgroundResource(
                    if (index == galleryIndex) {
                        R.drawable.bg_home_banner_dot_active
                    } else {
                        R.drawable.bg_home_banner_dot_inactive
                    }
                )
                layoutParams = LinearLayout.LayoutParams(8.dp, 8.dp).apply {
                    marginStart = 4.dp
                    marginEnd = 4.dp
                }
            })
        }
    }

    private fun loadReviews() {
        val product = product ?: return
        val progress = binding.progressReviews
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            reviewCursor = null
            reviewsReachedEnd = true
            val cachedPage = runCatching {
                ReviewService.fetchReviewsPage(product.id, pageSize = 10, source = Source.CACHE)
            }.getOrNull()
            if (isFinishing || isDestroyed) return@launch
            if (cachedPage != null) {
                progress.visibility = View.GONE
                applyReviewsPage(cachedPage)
            }

            val result = withTimeoutOrNull(4_500L) {
                runCatching {
                    ReviewService.fetchReviewsPage(product.id, pageSize = 10, source = Source.SERVER)
                }
            }
            if (isFinishing || isDestroyed) return@launch
            progress.visibility = View.GONE
            result?.getOrNull()?.let { page ->
                applyReviewsPage(page)
                return@launch
            }
            if (cachedPage == null) {
                renderReviews(emptyList())
            }
            result?.exceptionOrNull()?.let { error ->
                CrashlyticsHelper.recordNonFatal("ProductDetails", "Failed to refresh reviews productId=${product.id}", error)
            }
        }
    }

    private fun applyReviewsPage(page: ProductReviewPage) {
        reviewCursor = page.nextCursor
        reviewsReachedEnd = page.reachedEnd
        loadedReviews = page.reviews
        renderReviews(page.reviews)
    }

    private fun refreshSellerAvatarIfNeeded(product: Product) {
        if (product.sellerId.isBlank()) return
        lifecycleScope.launch {
            val profile = runCatching { UserService.fetchUserProfile(product.sellerId) }.getOrNull()
            if (isFinishing || isDestroyed) return@launch
            profile?.avatarUrl?.takeIf { it.isNotBlank() }?.let { avatarUrl ->
                binding.ivSellerAvatar.loadAvatarImage(avatarUrl)
            }
            if (!profile?.name.isNullOrBlank()) {
                binding.tvSellerName.text = profile?.name.orEmpty()
            }
        }
    }

    private fun renderReviews(reviews: List<ProductReview>) {
        loadedReviews = reviews
        val summary = binding.tvReviewsSummary
        val list = binding.layoutReviewsList
        list.removeAllViews()
        if (reviews.isEmpty()) {
            summary.text = getString(R.string.details_reviews_empty)
            return
        }
        val average = reviews.map { it.rating.coerceIn(1, 5) }.average()
        summary.text = getString(R.string.details_reviews_summary, average, reviews.size)
        reviews.take(3).forEachIndexed { index, review ->
            list.addView(buildReviewRow(review))
        }
        if (reviews.size > 3 || !reviewsReachedEnd) {
            list.addView(TextView(this).apply {
                text = getString(R.string.details_reviews_see_all, product?.reviewsCount?.takeIf { it > 0 } ?: reviews.size)
                setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
                typeface = resources.getFont(R.font.manrope_semibold)
                textSize = 13f
                setPadding(0, 14.dp, 0, 0)
                setOnClickListener { showAllReviewsSheet(reviews) }
            })
        }
    }

    private fun buildReviewRow(review: ProductReview): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(TextView(context).apply {
                text = "${"★".repeat(review.rating.coerceIn(1, 5))}  ${review.userName.ifBlank { "Client" }}"
                setTextColor(ContextCompat.getColor(context, R.color.home_text_primary))
                typeface = resources.getFont(R.font.manrope_semibold)
                textSize = 13f
            })
            review.createdAt.toReviewDateLabel()?.let { label ->
                addView(TextView(context).apply {
                    text = label
                    setTextColor(ContextCompat.getColor(context, R.color.home_text_secondary))
                    typeface = resources.getFont(R.font.manrope_regular)
                    textSize = 11f
                    setPadding(0, 3.dp, 0, 0)
                })
            }
            addView(TextView(context).apply {
                text = review.comment
                setTextColor(ContextCompat.getColor(context, R.color.home_text_secondary))
                typeface = resources.getFont(R.font.manrope_regular)
                textSize = 13f
                setPadding(0, 8.dp, 0, 0)
            })
        }
        return MaterialCardView(this).apply {
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.colorSurface))
            radius = 12.dp.toFloat()
            cardElevation = 0f
            strokeWidth = 1.dp
            setStrokeColor(ContextCompat.getColor(context, R.color.colorBorderLight))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8.dp }
            addView(content.apply { setPadding(14.dp, 12.dp, 14.dp, 12.dp) })
        }
    }

    private fun Any?.toReviewDateLabel(): String? {
        val millis = when (this) {
            is Long -> this
            is Int -> toLong()
            is com.google.firebase.Timestamp -> toDate().time
            is Map<*, *> -> ((this["seconds"] ?: this["_seconds"]) as? Number)?.toLong()?.times(1000L)
            else -> null
        } ?: return null
        if (millis <= 0L) return null
        return SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
    }

    private fun showReviewDialog() {
        if (FirebaseAuthManager.currentUser == null) {
            showMotionSnackbar(getString(R.string.details_review_login_required), R.id.layoutBottomBar)
            return
        }
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 20.dp, 24.dp, 24.dp)
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.details_review_dialog_title)
            setTextColor(ContextCompat.getColor(context, R.color.home_text_primary))
            typeface = resources.getFont(R.font.manrope_semibold)
            textSize = 18f
        })
        val ratingBar = RatingBar(this, null, android.R.attr.ratingBarStyle).apply {
            numStars = 5
            stepSize = 1f
            rating = 5f
            setOnRatingBarChangeListener { _, _, fromUser ->
                if (fromUser) performLightHapticFeedback()
            }
        }
        val commentInput = EditText(this).apply {
            hint = getString(R.string.details_review_comment_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 5
        }
        val counter = TextView(this).apply {
            text = getString(R.string.details_review_counter, 0, 280)
            setTextColor(ContextCompat.getColor(context, R.color.home_text_secondary))
            textSize = 12f
        }
        commentInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                counter.text = getString(R.string.details_review_counter, s?.length ?: 0, 280)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        container.addView(ratingBar)
        container.addView(commentInput)
        container.addView(counter)
        container.addView(TextView(this).apply {
            text = getString(R.string.details_review_verified_hint)
            setTextColor(ContextCompat.getColor(context, R.color.home_text_secondary))
            textSize = 12f
            setPadding(0, 10.dp, 0, 12.dp)
        })
        container.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.details_review_submit)
            isAllCaps = false
            setOnClickListener {
                val comment = commentInput.text?.toString()?.trim().orEmpty()
                if (comment.length < 3) {
                    commentInput.error = getString(R.string.details_review_comment_required)
                    return@setOnClickListener
                }
                dialog.dismiss()
                submitReview(ratingBar.rating.toInt().coerceIn(1, 5), comment)
            }
        })
        dialog.setContentView(container)
        dialog.show()
    }

    private fun showAllReviewsSheet(reviews: List<ProductReview>) {
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 20.dp, 24.dp, 24.dp)
        }
        container.addView(TextView(this).apply {
            text = getString(R.string.details_reviews_all_title)
            setTextColor(ContextCompat.getColor(context, R.color.home_text_primary))
            typeface = resources.getFont(R.font.manrope_semibold)
            textSize = 18f
        })
        val loaded = reviews.toMutableList()
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        fun renderRows() {
            rows.removeAllViews()
            loaded.forEach { review -> rows.addView(buildReviewRow(review)) }
        }
        renderRows()
        container.addView(rows)
        container.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.details_reviews_load_more)
            isAllCaps = false
            visibility = if (reviewsReachedEnd) View.GONE else View.VISIBLE
            setOnClickListener {
                val currentProduct = product ?: return@setOnClickListener
                isEnabled = false
                lifecycleScope.launch {
                    val page = runCatching {
                        ReviewService.fetchReviewsPage(currentProduct.id, pageSize = 10, cursor = reviewCursor)
                    }.getOrElse { error ->
                        CrashlyticsHelper.recordNonFatal("ProductDetails", "Failed to page reviews productId=${currentProduct.id}", error)
                        isEnabled = true
                        return@launch
                    }
                    reviewCursor = page.nextCursor
                    reviewsReachedEnd = page.reachedEnd
                    loaded += page.reviews
                    renderRows()
                    visibility = if (reviewsReachedEnd) View.GONE else View.VISIBLE
                    isEnabled = true
                }
            }
        })
        dialog.setContentView(container)
        dialog.show()
    }

    private fun setupReviewStars() {
        reviewStarIds.forEachIndexed { index, id ->
            findViewById<ImageView>(id)?.setOnClickListener { star ->
                star.performLightHapticFeedback()
                selectedRating = index + 1
                refreshReviewStars()
                binding.tvReviewStarsError.visibility = View.GONE
            }
        }
        refreshReviewStars()
    }

    private fun refreshReviewStars() {
        val activeColor = ContextCompat.getColor(this, R.color.home_ref_gold)
        val inactiveColor = ContextCompat.getColor(this, R.color.colorBorderLight)
        reviewStarIds.forEachIndexed { index, id ->
            val star = findViewById<ImageView>(id) ?: return@forEachIndexed
            val isActive = index < selectedRating
            star.setImageResource(if (isActive) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
            star.setColorFilter(if (isActive) activeColor else inactiveColor)
        }
    }

    private fun submitReview(rating: Int, comment: String) {
        val product = product ?: return
        lifecycleScope.launch {
            val review = ProductReview(productId = product.id, rating = rating, comment = comment)
            runCatching { ReviewService.addReview(product.id, review) }
                .onSuccess {
                    showMotionSnackbar(getString(R.string.details_review_saved), R.id.layoutBottomBar)
                    selectedRating = 0
                    refreshReviewStars()
                    binding.etReviewComment.text?.clear()
                    loadReviews()
                }
                .onFailure { error ->
                    val backendError = error as? BackendFunctionException
                    if (backendError?.code != FirebaseFunctionsException.Code.FAILED_PRECONDITION) {
                        CrashlyticsHelper.recordNonFatal(
                            "ProductDetails",
                            "Review submit failed productId=${product.id}",
                            error
                        )
                    }
                    showMotionSnackbar(
                        backendError?.message ?: getString(R.string.details_review_failed),
                        R.id.layoutBottomBar
                    )
                }
        }
    }

    private fun bindBullet(textView: TextView, bullet: String?) {
        textView.apply {
            val value = bullet?.trim().orEmpty()
            text = value
            visibility = if (value.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun setupDescriptionCollapse(description: String) {
        val tvDescription = binding.tvDescription
        val maxLines = 4
        tvDescription.text = description

        if (description.length > 150) {
            tvDescription.post {
                tvDescription.maxLines = maxLines
            }

            val readMoreText = getString(R.string.details_read_more)
            val readMoreClickable = TextView(this).apply {
                text = readMoreText
                setTextColor(ContextCompat.getColor(context, R.color.colorPrimary))
                typeface = resources.getFont(R.font.manrope_semibold)
                textSize = 13f
                setPadding(0, 8.dp, 0, 0)
                setOnClickListener {
                    if (tvDescription.maxLines == maxLines) {
                        tvDescription.maxLines = Int.MAX_VALUE
                        text = getString(R.string.details_read_less)
                    } else {
                        tvDescription.maxLines = maxLines
                        text = getString(R.string.details_read_more)
                    }
                }
            }

            binding.layoutContent.findViewById<LinearLayout>(R.id.layoutContent)?.let { parent ->
                val descriptionTitleIndex = parent.indexOfChild(binding.tvDescriptionTitle)
                if (descriptionTitleIndex >= 0) {
                    parent.addView(readMoreClickable, descriptionTitleIndex + 2)
                }
            }
        }
    }

    private fun updateQuantityUi() {
        val product = product ?: return
        val maxStock = availableStock()
        val inStock = maxStock > 0
        binding.tvQuantity.text = if (inStock) quantity.toString() else "0"
        binding.tvTotalValue.text = formatDt(currentUnitPrice() * quantity.coerceAtLeast(0))
        binding.btnMinus.apply {
            isEnabled = inStock && quantity > 1
            alpha = if (isEnabled) 1f else 0.45f
        }
        binding.btnPlus.apply {
            isEnabled = inStock && quantity < maxStock
            alpha = if (isEnabled) 1f else 0.45f
        }
    }

    private fun selectedVariant(): ProductVariant? {
        val p = product ?: return null
        if (!p.hasVariants) return null
        val color = if (p.requiresColorSelection) selectedColorName ?: return null else ""
        val size = if (p.requiresSizeSelection) selectedSize ?: return null else ""
        return p.variantFor(color, size)
    }

    private fun currentUnitPrice(): Double {
        val p = product ?: return 0.0
        return p.unitPriceForVariant(selectedVariant())
    }

    /** Quantity ceiling for the current selection. Variant products require a resolved variant. */
    private fun availableStock(): Int {
        val p = product ?: return 0
        if (!p.hasVariants) return p.stock
        return selectedVariant()?.stock ?: 0
    }

    private fun bindSpecs(product: Product) {
        val container = binding.llDetailSpecs
        container.removeAllViews()
        val config = ProductTypeCatalog.byKey(product.productType)
        val labelByKey = config?.attributeFields?.associate { it.key to getString(it.labelRes) } ?: emptyMap()
        var added = 0
        product.attributes.forEach { (key, value) ->
            val display = formatAttributeValue(value) ?: return@forEach
            if (display.isBlank()) return@forEach
            val label = when (key) {
                "dimensions" -> getString(R.string.pa_section_dimensions)
                else -> labelByKey[key] ?: prettifyKey(key)
            }
            addSpecRow(container, label, display)
            added++
        }
        binding.cardDetailSpecs.visibility = if (added > 0) View.VISIBLE else View.GONE
    }

    private fun addSpecRow(container: android.view.ViewGroup, label: String, value: String) {
        val row = TextView(this).apply {
            text = getString(R.string.pa_spec_row, label, value)
            setTextColor(ContextCompat.getColor(context, R.color.home_text_primary))
            textSize = resources.getDimension(R.dimen.text_size_caption) / resources.displayMetrics.scaledDensity
            setLineSpacing(0f, 1.2f)
            if (container.childCount > 0) {
                setPadding(0, resources.getDimensionPixelSize(R.dimen.space_8), 0, 0)
            }
        }
        container.addView(row)
    }

    @Suppress("UNCHECKED_CAST")
    private fun formatAttributeValue(value: Any?): String? = when (value) {
        null -> null
        is Boolean -> if (value) getString(R.string.common_yes) else getString(R.string.common_no)
        is Map<*, *> -> {
            val w = value["width"]; val h = value["height"]; val d = value["depth"]
            listOf(w, h, d).mapNotNull { (it as? Number)?.let { n -> numberToText(n) } }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" x ")
                ?.let { "$it cm" }
        }
        is Number -> numberToText(value)
        else -> value.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun numberToText(n: Number): String {
        val d = n.toDouble()
        return if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
    }

    private fun prettifyKey(key: String): String =
        key.replace(Regex("([a-z])([A-Z])"), "$1 $2").replaceFirstChar { it.uppercase() }

    private fun bindVariantSelectors(product: Product) {
        val colors = product.colorOptions
        val showColors = colors.isNotEmpty()
        binding.tvDetailColorsTitle.visibility = if (showColors) View.VISIBLE else View.GONE
        binding.cgDetailColors.visibility = if (showColors) View.VISIBLE else View.GONE
        binding.cgDetailColors.removeAllViews()
        selectedColorName = null
        if (showColors) {
            colors.forEach { color ->
                val chip = com.google.android.material.chip.Chip(this).apply {
                    text = color.name
                    isCheckable = true
                    isCheckedIconVisible = true
                    chipIcon = colorSwatch(color.hex)
                    isChipIconVisible = color.hex.isNotBlank()
                    setOnClickListener {
                        selectedColorName = if (isChecked) color.name else null
                        refreshVariantAvailability()
                    }
                }
                binding.cgDetailColors.addView(chip)
            }
            if (colors.size == 1) {
                (binding.cgDetailColors.getChildAt(0) as? com.google.android.material.chip.Chip)?.apply {
                    isChecked = true
                    selectedColorName = colors.first().name
                }
            }
        }

        val sizes = product.sizeOptions
        val showSizes = sizes.isNotEmpty()
        binding.tvDetailSizesTitle.visibility = if (showSizes) View.VISIBLE else View.GONE
        binding.cgDetailSizes.visibility = if (showSizes) View.VISIBLE else View.GONE
        binding.cgDetailSizes.removeAllViews()
        selectedSize = null
        if (showSizes) {
            sizes.forEach { size ->
                val chip = com.google.android.material.chip.Chip(this).apply {
                    text = size
                    isCheckable = true
                    isCheckedIconVisible = true
                    setOnClickListener {
                        selectedSize = if (isChecked) size else null
                        refreshVariantAvailability()
                    }
                }
                binding.cgDetailSizes.addView(chip)
            }
            if (sizes.size == 1) {
                (binding.cgDetailSizes.getChildAt(0) as? com.google.android.material.chip.Chip)?.apply {
                    isChecked = true
                    selectedSize = sizes.first()
                }
            }
        }
        refreshVariantAvailability()
    }

    /** Disables size chips that have no stock for the chosen color, then refreshes price/qty. */
    private fun refreshVariantAvailability() {
        val product = product ?: return
        if (product.requiresSizeSelection) {
            for (i in 0 until binding.cgDetailSizes.childCount) {
                val chip = binding.cgDetailSizes.getChildAt(i) as? com.google.android.material.chip.Chip ?: continue
                val size = chip.text.toString()
                val color = if (product.requiresColorSelection) selectedColorName else ""
                val available = if (color == null) {
                    product.activeVariants.any { it.size.equals(size, true) && it.stock > 0 }
                } else {
                    val v = product.variantFor(color, size)
                    v != null && v.stock > 0
                }
                chip.isEnabled = available
                if (!available && chip.isChecked) {
                    chip.isChecked = false
                    selectedSize = null
                }
            }
        }
        val variant = selectedVariant()
        binding.tvPrice.text = formatDt(currentUnitPrice())
        if (variant != null) {
            binding.tvStockHelper.apply {
                text = when {
                    variant.stock <= 0 -> getString(R.string.pa_variant_out_of_stock)
                    variant.stock <= 5 -> getString(R.string.product_state_low_stock, variant.stock)
                    else -> getString(R.string.details_stock_helper)
                }
                setTextColor(
                    ContextCompat.getColor(
                        context,
                        if (variant.stock <= 0) R.color.colorError else R.color.home_text_primary
                    )
                )
            }
        }
        if (quantity < 1 && availableStock() > 0) quantity = 1
        quantity = quantity.coerceAtMost(availableStock().coerceAtLeast(if (availableStock() > 0) 1 else 0))
        updateQuantityUi()
    }

    private fun colorSwatch(hex: String): android.graphics.drawable.GradientDrawable? {
        val parsed = runCatching { android.graphics.Color.parseColor(hex) }.getOrNull() ?: return null
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(parsed)
            setStroke(
                resources.getDimensionPixelSize(R.dimen.ms_stroke_hairline),
                android.graphics.Color.parseColor("#33000000")
            )
        }
    }

    /** Validates required variant selection. Returns an error message, or null when OK to add. */
    private fun variantSelectionError(product: Product): String? {
        if (!product.hasVariants) return null
        val needColor = product.requiresColorSelection && selectedColorName == null
        val needSize = product.requiresSizeSelection && selectedSize == null
        return when {
            needColor && needSize -> getString(R.string.pa_select_required_both)
            needColor -> getString(R.string.pa_select_required_color)
            needSize -> getString(R.string.pa_select_required_size)
            selectedVariant() == null -> getString(R.string.pa_variant_out_of_stock)
            else -> null
        }
    }

    private fun animateEntry() {
        if (isReducedMotionEnabled()) return

        val content = binding.layoutContent
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
        val imageHeader = binding.layoutImageHeader
        val imageCard = binding.cardProductImage
        val quantitySelector = binding.layoutQuantitySelector

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
