package isim.ia2y.myapplication

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.Locale
import kotlin.random.Random

class AdminProductEditorActivity : AppCompatActivity() {
    private val logTag = "AdminProductEditor"
    private val maxProductImages = 5

    private data class EditorImage(
        val remoteUrl: String? = null,
        val localUri: Uri? = null
    ) {
        val stableId: String get() = localUri?.toString() ?: remoteUrl.orEmpty()
    }

    private data class CategoryOption(val key: String, val label: String)
    private data class ProductDraftTemplate(
        val title: String,
        val subtitle: String,
        val tags: List<String>,
        val description: String,
        val bullets: List<String>,
        val origin: String,
        val bioFriendly: Boolean
    )
    private data class ProductGenerationImagePayload(
        val base64: String,
        val mimeType: String
    )
    private data class AutofillSnapshot(
        val title: String,
        val subtitle: String,
        val price: Double,
        val stock: Int,
        val categoryKey: String,
        val categoryLabel: String,
        val origin: String,
        val tags: List<String>,
        val description: String,
        val bullets: List<String>,
        val bioFriendly: Boolean
    )

    private val categories by lazy {
        MarketplaceCategories.items.map { category ->
            CategoryOption(category.id, category.name)
        }
    }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            addSelectedImages(uris)
            renderImagePreview()
        }
    private val cameraCapture =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
            val uri = pendingCameraImageUri
            pendingCameraImageUri = null
            if (captured && uri != null) {
                addSelectedImages(listOf(uri))
                renderImagePreview()
            } else {
                uri?.let(::deleteCachedCameraImage)
            }
        }

    private val editorImages = mutableListOf<EditorImage>()
    private var product: Product? = null
    private val random = Random(System.currentTimeMillis())
    private var initialFormSignature = ""
    private var isSaving = false
    private var isGeneratingProductInfo = false
    private var lastAutofillSnapshot: AutofillSnapshot? = null
    private var activeRole: String = UserRoles.CLIENT
    private var pendingCameraImageUri: Uri? = null
    private val isSellerMode: Boolean
        get() = intent.getBooleanExtra(AdminProduitsActivity.EXTRA_SELLER_MODE, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_product_editor)
        setupWindowInsets()
        setupTopBar()
        restoreState(savedInstanceState)

        val productId = intent.getStringExtra(EXTRA_PRODUCT_ID)
        product = productId?.let { ProductCatalog.byId(it) }
        restoreImages(savedInstanceState)
        if (editorImages.isEmpty()) {
            resetEditorImagesFromProduct(product)
        }

        setupCategoryDropdown()
        bindExistingProduct()
        bindActions()
        bindImagePreviewRefresh()
        renderImagePreview()
        initialFormSignature = captureFormSignature()
        productId?.let { loadExistingProduct(it, savedInstanceState == null) }
        verifyProductManagerAccess(productId)
        onBackPressedDispatcher.addCallback(this) {
            handleCloseRequest()
        }
    }

    private fun verifyProductManagerAccess(productId: String?) {
        lifecycleScope.launch {
            val role = requireAdminOrVendeurRole() ?: return@launch
            activeRole = role
            val uid = FirebaseAuthManager.currentUser?.uid.orEmpty()
            if (productId != null && role == UserRoles.VENDEUR) {
                val remote = runCatching { ProductService.fetchProduct(productId) }.getOrNull()
                if (remote != null) {
                    product = remote
                }
                val existingProduct = remote ?: product
                if (existingProduct != null && existingProduct.sellerId != uid) {
                    showMotionSnackbar(getString(R.string.admin_product_edit_own_only))
                    finish()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(
            STATE_EDITOR_IMAGES,
            ArrayList(editorImages.mapNotNull { image ->
                image.localUri?.let { "local:$it" } ?: image.remoteUrl?.let { "remote:$it" }
            })
        )
        outState.putString(STATE_PENDING_CAMERA_URI, pendingCameraImageUri?.toString())
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        restoreImages(savedInstanceState)
        pendingCameraImageUri = savedInstanceState
            ?.getString(STATE_PENDING_CAMERA_URI)
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::parse)
    }

    private fun restoreImages(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) return
        editorImages.clear()
        savedInstanceState.getStringArrayList(STATE_EDITOR_IMAGES)
            .orEmpty()
            .forEach { encoded ->
                when {
                    encoded.startsWith("remote:") -> verifiedRemoteImageUrl(encoded.removePrefix("remote:"))
                        ?.let { editorImages += EditorImage(remoteUrl = it) }
                    encoded.startsWith("local:") -> editorImages += EditorImage(localUri = Uri.parse(encoded.removePrefix("local:")))
                }
            }
    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminProductEditorAppBar)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.adminProductEditorBottomBar)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom + resources.getDimensionPixelSize(R.dimen.space_8))
            insets
        }
    }

    private fun setupTopBar() {
        findViewById<View>(R.id.adminProductEditorIvBack)?.setOnClickListener { handleCloseRequest() }
        applyPressFeedback(R.id.adminProductEditorIvBack, R.id.adminProductEditorBtnPickImage, R.id.adminProductEditorBtnRemoveImage)
    }

    private fun setupCategoryDropdown() {
        val categoryInput = findViewById<AutoCompleteTextView>(R.id.actvAdminProductCategory) ?: return
        val labels = categories.map { it.label }
        categoryInput.setAdapter(
            android.widget.ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                labels
            )
        )
        categoryInput.setOnClickListener { categoryInput.showDropDown() }
        categoryInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) categoryInput.showDropDown()
        }
    }

    private fun bindExistingProduct() {
        val title = findViewById<TextView>(R.id.adminProductEditorTvTitle)
        val save = findViewById<MaterialButton>(R.id.adminProductEditorBtnSave)
        val existing = product

        title?.text = if (existing == null) {
            getString(R.string.admin_product_editor_title_add)
        } else {
            getString(R.string.admin_product_editor_title_edit, existing.title)
        }
        save?.text = if (existing == null) {
            getString(R.string.admin_product_editor_action_add)
        } else {
            getString(R.string.admin_product_editor_action_save)
        }

        existing ?: return

        findViewById<TextInputEditText>(R.id.etAdminProductTitle)?.setText(existing.title)
        findViewById<TextInputEditText>(R.id.etAdminProductSubtitle)?.setText(existing.subtitle)
        findViewById<TextInputEditText>(R.id.etAdminProductPrice)?.setText(existing.price.toString())
        findViewById<TextInputEditText>(R.id.etAdminProductStock)?.setText(existing.stock.toString())
        findViewById<TextInputEditText>(R.id.etAdminProductDiscount)?.setText(
            if (existing.discountPercentClamped > 0) existing.discountPercentClamped.toString() else ""
        )
        findViewById<AutoCompleteTextView>(R.id.actvAdminProductCategory)?.setText(
            categoryLabel(existing.category),
            false
        )
        findViewById<TextInputEditText>(R.id.etAdminProductOrigin)?.setText(existing.origin.replace('_', ' '))
        findViewById<TextInputEditText>(R.id.etAdminProductTags)?.setText(existing.tags.joinToString(", "))
        findViewById<TextInputEditText>(R.id.etAdminProductDescription)?.setText(existing.description)
        findViewById<TextInputEditText>(R.id.etAdminProductBullets)?.setText(existing.bullets.joinToString("\n"))
        findViewById<SwitchMaterial>(R.id.switchAdminProductBio)?.isChecked = existing.isBio
        findViewById<SwitchMaterial>(R.id.switchAdminProductActive)?.isChecked = existing.isActive
    }

    private fun loadExistingProduct(productId: String, canRefreshImage: Boolean) {
        lifecycleScope.launch {
            runCatching { ProductService.fetchProduct(productId) }
                .onSuccess { remote ->
                    remote ?: return@onSuccess
                    val canRebindForm = !hasUnsavedChanges()
                    product = remote
                    if (canRebindForm) {
                        if (canRefreshImage) {
                            resetEditorImagesFromProduct(remote)
                        }
                        bindExistingProduct()
                        renderImagePreview()
                        initialFormSignature = captureFormSignature()
                    }
                }
                .onFailure { error ->
                    Log.w(logTag, "Unable to refresh product $productId before editing", error)
                }
        }
    }

    private fun bindActions() {
        findViewById<MaterialButton>(R.id.adminProductEditorBtnPickImage)?.setOnClickListener {
            if (editorImages.size >= maxProductImages) {
                showMotionSnackbar(getString(R.string.admin_product_editor_image_limit, maxProductImages))
                return@setOnClickListener
            }
            imagePicker.launch("image/*")
        }
        findViewById<MaterialButton>(R.id.adminProductEditorBtnCameraImage)?.setOnClickListener {
            launchCameraCapture()
        }
        findViewById<MaterialButton>(R.id.adminProductEditorBtnRemoveImage)?.setOnClickListener {
            removeImageAt(0)
            renderImagePreview()
        }
        findViewById<MaterialButton>(R.id.adminProductEditorBtnAutofill)?.let { button ->
            button.visibility = View.VISIBLE
            button.setOnClickListener {
                generateProductInfoFromImage()
            }
        }
        findViewById<MaterialButton>(R.id.adminProductEditorBtnSave)?.setOnClickListener {
            saveProduct()
        }
    }

    private fun bindImagePreviewRefresh() {
        findViewById<TextInputEditText>(R.id.etAdminProductTitle)?.doAfterTextChanged {
            if (editorImages.isEmpty()) {
                renderImagePreview()
            }
        }
        findViewById<AutoCompleteTextView>(R.id.actvAdminProductCategory)?.doAfterTextChanged {
            if (editorImages.isEmpty()) {
                renderImagePreview()
            }
        }
    }

    private fun fillRandomProductDraft() {
        clearErrors()

        val chosenCategory = categories.random(random)
        val template = randomTemplateFor(chosenCategory.key)
        val generatedPrice = randomPriceFor(chosenCategory.key)
        val generatedStock = random.nextInt(3, 28)
        lastAutofillSnapshot = AutofillSnapshot(
            title = template.title,
            subtitle = template.subtitle,
            price = generatedPrice,
            stock = generatedStock,
            categoryKey = chosenCategory.key,
            categoryLabel = chosenCategory.label,
            origin = template.origin,
            tags = template.tags,
            description = template.description,
            bullets = template.bullets,
            bioFriendly = template.bioFriendly
        )
        Log.d(logTag, "Prefill generated for ${chosenCategory.key}: ${template.title}")

        findViewById<TextInputEditText>(R.id.etAdminProductTitle)?.setText(template.title)
        findViewById<TextInputEditText>(R.id.etAdminProductSubtitle)?.setText(template.subtitle)
        findViewById<TextInputEditText>(R.id.etAdminProductPrice)?.setText(formatSuggestedPrice(generatedPrice))
        findViewById<TextInputEditText>(R.id.etAdminProductStock)?.setText(generatedStock.toString())
        findViewById<AutoCompleteTextView>(R.id.actvAdminProductCategory)?.setText(chosenCategory.label, false)
        findViewById<TextInputEditText>(R.id.etAdminProductOrigin)?.setText(template.origin)
        findViewById<TextInputEditText>(R.id.etAdminProductTags)?.setText(template.tags.joinToString(", "))
        findViewById<TextInputEditText>(R.id.etAdminProductDescription)?.setText(template.description)
        findViewById<TextInputEditText>(R.id.etAdminProductBullets)?.setText(template.bullets.joinToString("\n"))
        findViewById<SwitchMaterial>(R.id.switchAdminProductBio)?.isChecked = template.bioFriendly
        findViewById<SwitchMaterial>(R.id.switchAdminProductActive)?.isChecked = true

        renderImagePreview()
        showMotionSnackbar(getString(R.string.admin_product_editor_autofill_done))
    }

    private fun generateProductInfoFromImage() {
        clearErrors()
        val image = editorImages.firstOrNull()
        if (image == null) {
            showMotionSnackbar(getString(R.string.admin_product_editor_ai_no_image))
            scrollToError(findViewById(R.id.adminProductEditorImage))
            return
        }

        setGeneratingProductInfoState(true)
        lifecycleScope.launch {
            runCatching {
                val payload = buildProductGenerationImagePayload(image)
                BackendFunctionsService.generateProductInfo(payload.base64, payload.mimeType)
            }.onSuccess { draft ->
                applyGeneratedProductInfo(draft)
                showMotionSnackbar(getString(R.string.admin_product_editor_autofill_done))
            }.onFailure { error ->
                Log.w(logTag, "Product info generation failed", error)
                showMotionSnackbar(friendlyProductGenerationError(error))
            }
            setGeneratingProductInfoState(false)
        }
    }

    private fun applyGeneratedProductInfo(draft: GeneratedProductInfo) {
        val category = categories.firstOrNull { it.key == draft.categoryKey } ?: categories.first()
        val suggestedPrice = draft.suggestedPrice.takeIf { it > 0.0 } ?: randomPriceFor(category.key)
        lastAutofillSnapshot = AutofillSnapshot(
            title = draft.title,
            subtitle = draft.subtitle,
            price = suggestedPrice,
            stock = draft.suggestedStock,
            categoryKey = category.key,
            categoryLabel = category.label,
            origin = draft.origin,
            tags = draft.tags.ifEmpty { listOf(getString(R.string.product_default_tag)) },
            description = draft.description,
            bullets = draft.bullets,
            bioFriendly = draft.bioFriendly
        )

        findViewById<TextInputEditText>(R.id.etAdminProductTitle)?.setText(draft.title)
        findViewById<TextInputEditText>(R.id.etAdminProductSubtitle)?.setText(draft.subtitle)
        findViewById<TextInputEditText>(R.id.etAdminProductPrice)?.setText(formatSuggestedPrice(suggestedPrice))
        findViewById<AutoCompleteTextView>(R.id.actvAdminProductCategory)?.setText(category.label, false)
        findViewById<TextInputEditText>(R.id.etAdminProductOrigin)?.setText(draft.origin)
        findViewById<TextInputEditText>(R.id.etAdminProductTags)?.setText(draft.tags.joinToString(", "))
        findViewById<TextInputEditText>(R.id.etAdminProductDescription)?.setText(draft.description)
        findViewById<TextInputEditText>(R.id.etAdminProductBullets)?.setText(draft.bullets.joinToString("\n"))
        findViewById<TextInputEditText>(R.id.etAdminProductStock)?.setText(draft.suggestedStock.toString())
        findViewById<SwitchMaterial>(R.id.switchAdminProductBio)?.isChecked = draft.bioFriendly
        findViewById<SwitchMaterial>(R.id.switchAdminProductActive)?.isChecked = true
        renderImagePreview()
    }

    private suspend fun buildProductGenerationImagePayload(image: EditorImage): ProductGenerationImagePayload =
        withContext(Dispatchers.IO) {
            val rawBytes = image.localUri?.let { uri ->
                contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IOException("Unable to read selected image.")
            } ?: image.remoteUrl?.let { url ->
                URL(url).openConnection().run {
                    connectTimeout = 12_000
                    readTimeout = 20_000
                    getInputStream().use { it.readBytes() }
                }
            } ?: throw IOException("A product image is required.")

            if (rawBytes.size > 12_000_000) {
                throw IOException("Selected image is too large.")
            }

            val compressed = compressImageForGemini(rawBytes)
            ProductGenerationImagePayload(
                base64 = Base64.encodeToString(compressed, Base64.NO_WRAP),
                mimeType = "image/jpeg"
            )
        }

    private fun compressImageForGemini(rawBytes: ByteArray): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return rawBytes
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateImageSampleSize(bounds.outWidth, bounds.outHeight, 1280)
        }
        val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options) ?: return rawBytes
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }

    private fun calculateImageSampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sampleSize = 1
        var scaledWidth = width
        var scaledHeight = height
        while (scaledWidth / 2 >= maxSide || scaledHeight / 2 >= maxSide) {
            scaledWidth /= 2
            scaledHeight /= 2
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun renderImagePreview() {
        val image = findViewById<ImageView>(R.id.adminProductEditorImage)
        val empty = findViewById<TextView>(R.id.adminProductEditorImageEmpty)
        val remove = findViewById<MaterialButton>(R.id.adminProductEditorBtnRemoveImage)
        val source = editorImages.firstOrNull()?.source()

        image?.load(source ?: R.drawable.placeholder) {
            crossfade(180)
            placeholder(R.drawable.placeholder)
            error(R.drawable.placeholder)
        }

        val hasDisplayImage = source != null
        empty?.visibility = if (hasDisplayImage) View.GONE else View.VISIBLE
        remove?.visibility = if (editorImages.isNotEmpty()) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.adminProductEditorBtnPickImage)?.isEnabled =
            !isSaving && editorImages.size < maxProductImages
        findViewById<MaterialButton>(R.id.adminProductEditorBtnCameraImage)?.isEnabled =
            !isSaving && editorImages.size < maxProductImages
        renderImageStrip()
    }

    private fun launchCameraCapture() {
        if (editorImages.size >= maxProductImages) {
            showMotionSnackbar(getString(R.string.admin_product_editor_image_limit, maxProductImages))
            return
        }

        val outputUri = runCatching { createCameraImageUri() }
            .onFailure { error -> Log.w(logTag, "Unable to prepare camera image file", error) }
            .getOrNull()

        if (outputUri == null) {
            showMotionSnackbar(getString(R.string.admin_product_editor_camera_failed))
            return
        }

        pendingCameraImageUri = outputUri
        runCatching { cameraCapture.launch(outputUri) }
            .onFailure { error ->
                Log.w(logTag, "Unable to launch camera capture", error)
                pendingCameraImageUri = null
                deleteCachedCameraImage(outputUri)
                showMotionSnackbar(getString(R.string.admin_product_editor_camera_failed))
            }
    }

    private fun createCameraImageUri(): Uri {
        val dir = File(cacheDir, "product_images").apply { mkdirs() }
        val file = File.createTempFile("product_camera_", ".jpg", dir)
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    private fun deleteCachedCameraImage(uri: Uri) {
        runCatching {
            if (uri.scheme == "content" && uri.authority == "$packageName.fileprovider") {
                contentResolver.delete(uri, null, null)
            }
        }
    }

    private fun renderImageStrip() {
        val strip = findViewById<LinearLayout>(R.id.adminProductEditorImageStrip) ?: return
        val scroll = findViewById<View>(R.id.adminProductEditorImageStripScroll)
        strip.removeAllViews()
        scroll?.visibility = if (editorImages.isEmpty()) View.GONE else View.VISIBLE
        editorImages.forEachIndexed { index, editorImage ->
            strip.addView(buildImageThumb(index, editorImage))
        }
    }

    private fun buildImageThumb(index: Int, editorImage: EditorImage): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(72.dp, 72.dp).apply {
                marginEnd = 10.dp
            }
            radius = 14.dp.toFloat()
            cardElevation = 0f
            strokeWidth = if (index == 0) 2.dp else 1.dp
            setStrokeColor(getColor(if (index == 0) R.color.colorPrimary else R.color.colorBorderLight))
            setCardBackgroundColor(getColor(R.color.profile_body_bg))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                promoteImageToCover(index)
            }
            setOnLongClickListener {
                removeImageAt(index)
                true
            }
        }
        val frame = android.widget.FrameLayout(this)
        val thumb = ImageView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = getString(R.string.details_image_cd)
            load(editorImage.source() ?: R.drawable.placeholder) {
                crossfade(120)
                placeholder(R.drawable.placeholder)
                error(R.drawable.placeholder)
            }
        }
        val remove = TextView(this).apply {
            text = "x"
            gravity = android.view.Gravity.CENTER
            setTextColor(getColor(android.R.color.white))
            textSize = 12f
            setBackgroundColor(getColor(R.color.colorPrimary))
            layoutParams = android.widget.FrameLayout.LayoutParams(22.dp, 22.dp, android.view.Gravity.TOP or android.view.Gravity.END)
            setOnClickListener {
                removeImageAt(index)
            }
        }
        frame.addView(thumb)
        frame.addView(remove)
        card.addView(frame)
        return card
    }

    private fun addSelectedImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val remaining = maxProductImages - editorImages.size
        if (remaining <= 0) {
            showMotionSnackbar(getString(R.string.admin_product_editor_image_limit, maxProductImages))
            return
        }
        val uniqueUris = uris
            .distinctBy { it.toString() }
            .filterNot { candidate -> editorImages.any { it.localUri == candidate } }
            .take(remaining)
        editorImages += uniqueUris.map { EditorImage(localUri = it) }
        if (uris.size > uniqueUris.size) {
            showMotionSnackbar(getString(R.string.admin_product_editor_image_limit, maxProductImages))
        }
    }

    private fun promoteImageToCover(index: Int) {
        if (index !in editorImages.indices || index == 0) return
        val image = editorImages.removeAt(index)
        editorImages.add(0, image)
        renderImagePreview()
    }

    private fun removeImageAt(index: Int) {
        if (index !in editorImages.indices) return
        if (product != null && editorImages.size <= 1) {
            showMotionSnackbar(getString(R.string.admin_product_editor_error_image))
            return
        }
        editorImages.removeAt(index)
        renderImagePreview()
    }

    private fun resetEditorImagesFromProduct(sourceProduct: Product?) {
        editorImages.clear()
        val urls = sourceProduct?.orderedRemoteImageUrls().orEmpty().take(maxProductImages)
        editorImages += urls.map { EditorImage(remoteUrl = it) }
    }

    private fun clearErrors() {
        listOf(
            R.id.tilAdminProductTitle,
            R.id.tilAdminProductSubtitle,
            R.id.tilAdminProductPrice,
            R.id.tilAdminProductStock,
            R.id.tilAdminProductCategory,
            R.id.tilAdminProductOrigin,
            R.id.tilAdminProductDescription,
            R.id.tilAdminProductBullets
        ).forEach { id ->
            findViewById<TextInputLayout>(id)?.error = null
        }
    }

    private fun saveProduct() {
        if (isSaving || isGeneratingProductInfo) return
        clearErrors()
        val autofillSnapshot = lastAutofillSnapshot

        val title = findViewById<TextInputEditText>(R.id.etAdminProductTitle)?.text?.toString().orEmpty().trim()
            .ifBlank { autofillSnapshot?.title.orEmpty() }
        val subtitle = findViewById<TextInputEditText>(R.id.etAdminProductSubtitle)?.text?.toString().orEmpty().trim()
            .ifBlank { autofillSnapshot?.subtitle.orEmpty() }
        val price = findViewById<TextInputEditText>(R.id.etAdminProductPrice)?.text?.toString().orEmpty()
            .trim()
            .replace(',', '.')
            .toDoubleOrNull()
            ?: autofillSnapshot?.price
        val stock = findViewById<TextInputEditText>(R.id.etAdminProductStock)?.text?.toString().orEmpty().trim().toIntOrNull()
            ?: autofillSnapshot?.stock
        val discountInput = findViewById<TextInputEditText>(R.id.etAdminProductDiscount)?.text?.toString().orEmpty().trim()
        val discountPercent = if (discountInput.isBlank()) 0 else discountInput.toIntOrNull() ?: -1
        val categoryLabel = findViewById<AutoCompleteTextView>(R.id.actvAdminProductCategory)?.text?.toString().orEmpty()
            .ifBlank { autofillSnapshot?.categoryLabel.orEmpty() }
        val category = if (categoryLabel.isNotBlank()) {
            resolveCategoryKey(categoryLabel)
        } else {
            autofillSnapshot?.categoryKey ?: "electronics"
        }
        val originRaw = findViewById<TextInputEditText>(R.id.etAdminProductOrigin)?.text?.toString().orEmpty().trim()
            .ifBlank { autofillSnapshot?.origin.orEmpty() }
        val tags = findViewById<TextInputEditText>(R.id.etAdminProductTags)?.text?.toString().orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { autofillSnapshot?.tags ?: listOf(getString(R.string.product_default_tag)) }
        val descriptionInput = findViewById<TextInputEditText>(R.id.etAdminProductDescription)
        val bulletsInput = findViewById<TextInputEditText>(R.id.etAdminProductBullets)
        val rawDescription = descriptionInput?.text?.toString().orEmpty().trim()
            .ifBlank { autofillSnapshot?.description.orEmpty() }
        val rawBullets = bulletsInput?.text?.toString().orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { autofillSnapshot?.bullets ?: emptyList() }
        val description = rawDescription.ifBlank {
            buildFallbackDescription(title = title, subtitle = subtitle, category = category, origin = originRaw)
        }
        val bullets = rawBullets.ifEmpty {
            buildFallbackBullets(title = title, subtitle = subtitle, origin = originRaw, tags = tags)
        }
        val isBio = findViewById<SwitchMaterial>(R.id.switchAdminProductBio)?.isChecked ?: autofillSnapshot?.bioFriendly ?: false
        val isActive = findViewById<SwitchMaterial>(R.id.switchAdminProductActive)?.isChecked ?: true
        Log.d(
            logTag,
            "Save tapped title='${title}' subtitle='${subtitle}' price=$price stock=$stock categoryLabel='${categoryLabel}' origin='${originRaw}' tags=${tags.size} bullets=${bullets.size} hasAutofill=${autofillSnapshot != null} rawDescriptionLength=${rawDescription.length}"
        )

        var valid = true
        var firstErrorView: View? = null
        if (title.length < 3) {
            findViewById<TextInputLayout>(R.id.tilAdminProductTitle)?.error =
                getString(R.string.admin_product_editor_error_title)
            valid = false
            firstErrorView = firstErrorView ?: findViewById(R.id.tilAdminProductTitle)
        }
        if (subtitle.length < 3) {
            findViewById<TextInputLayout>(R.id.tilAdminProductSubtitle)?.error =
                getString(R.string.admin_product_editor_error_subtitle)
            valid = false
            firstErrorView = firstErrorView ?: findViewById(R.id.tilAdminProductSubtitle)
        }
        if (price == null || price <= 0.0) {
            findViewById<TextInputLayout>(R.id.tilAdminProductPrice)?.error =
                getString(R.string.admin_product_editor_error_price)
            valid = false
            firstErrorView = firstErrorView ?: findViewById(R.id.tilAdminProductPrice)
        }
        if (stock == null || stock < 0) {
            findViewById<TextInputLayout>(R.id.tilAdminProductStock)?.error =
                getString(R.string.admin_product_editor_error_stock)
            valid = false
            firstErrorView = firstErrorView ?: findViewById(R.id.tilAdminProductStock)
        }
        if (discountPercent < 0 || discountPercent > 90) {
            findViewById<TextInputLayout>(R.id.tilAdminProductDiscount)?.error =
                getString(R.string.admin_product_editor_error_discount)
            valid = false
            firstErrorView = firstErrorView ?: findViewById(R.id.tilAdminProductDiscount)
        }
        if (categoryLabel.isBlank()) {
            findViewById<TextInputLayout>(R.id.tilAdminProductCategory)?.error =
                getString(R.string.admin_product_editor_error_category)
            valid = false
            firstErrorView = firstErrorView ?: findViewById(R.id.tilAdminProductCategory)
        }
        if (originRaw.length < 2) {
            findViewById<TextInputLayout>(R.id.tilAdminProductOrigin)?.error =
                getString(R.string.admin_product_editor_error_origin)
            valid = false
            firstErrorView = firstErrorView ?: findViewById(R.id.tilAdminProductOrigin)
        }
        if (description.length < 12) {
            findViewById<TextInputLayout>(R.id.tilAdminProductDescription)?.error =
                getString(R.string.admin_product_editor_error_description)
            valid = false
            firstErrorView = firstErrorView ?: findViewById(R.id.tilAdminProductDescription)
        }
        if (bullets.isEmpty()) {
            findViewById<TextInputLayout>(R.id.tilAdminProductBullets)?.error =
                getString(R.string.admin_product_editor_error_highlights)
            valid = false
            firstErrorView = firstErrorView ?: findViewById(R.id.tilAdminProductBullets)
        }
        if (editorImages.isEmpty()) {
            valid = false
            firstErrorView = firstErrorView ?: findViewById(R.id.adminProductEditorImage)
            findViewById<TextView>(R.id.adminProductEditorImageEmpty)?.visibility = View.VISIBLE
            showMotionSnackbar(getString(R.string.admin_product_editor_error_image))
        }
        if (!valid) {
            Log.w(logTag, "Save blocked by validation")
            scrollToError(firstErrorView)
            showMotionSnackbar(getString(R.string.admin_product_editor_fix_errors))
            return
        }

        Log.d(logTag, "Validation passed, starting save for $title")
        if (rawDescription.isBlank()) {
            descriptionInput?.setText(description)
        }
        if (rawBullets.isEmpty()) {
            bulletsInput?.setText(bullets.joinToString("\n"))
        }

        val productId = product?.id ?: createUniqueProductId(title)
        val normalizedOrigin = originRaw.lowercase(Locale.getDefault()).replace(" ", "_")

        setSavingState(true, R.string.admin_product_editor_action_preparing)
        lifecycleScope.launch {
            val currentUser = FirebaseAuthManager.currentUser
            val currentUid = currentUser?.uid.orEmpty()
            if (currentUid.isBlank()) {
                showMotionSnackbar(getString(R.string.admin_product_editor_publish_failed_permissions))
                setSavingState(false)
                return@launch
            }
            val role = runCatching { UserService.fetchUserRole(currentUid) }.getOrDefault(activeRole)
            Log.d(logTag, "Publish auth role=$role uid=${currentUid.takeLast(8)} editing=${product != null}")
            activeRole = role
            if (role != UserRoles.ADMIN && role != UserRoles.VENDEUR) {
                showMotionSnackbar(getString(R.string.admin_product_editor_publish_failed_permissions))
                setSavingState(false)
                return@launch
            }
            if (role == UserRoles.VENDEUR && product != null && product?.sellerId != currentUid) {
                showMotionSnackbar(getString(R.string.admin_product_editor_edit_failed_permissions))
                setSavingState(false)
                return@launch
            }

            setSaveProgress(R.string.admin_product_editor_action_uploading_images)
            val resolvedImageUrls = uploadEditorImages(productId) ?: return@launch
            if (resolvedImageUrls.isEmpty()) {
                showMotionSnackbar(getString(R.string.admin_product_editor_error_image))
                setSavingState(false)
                return@launch
            }
            val resolvedImageUrl = resolvedImageUrls.first()

            setSaveProgress(R.string.admin_product_editor_action_publishing)
            runCatching {
                val resolvedSellerId = product?.sellerId?.takeIf { it.isNotBlank() } ?: currentUid
                val resolvedSellerName = product?.sellerName?.takeIf { it.isNotBlank() }
                    ?: currentUser?.displayName?.takeIf { it.isNotBlank() }
                    ?: currentUser?.email?.substringBefore("@")
                    ?: "Fatiweb Seller"
                val resolvedSellerAvatar = product?.sellerAvatarUrl?.takeIf { it.isNotBlank() }
                    ?: currentUser?.photoUrl?.toString().orEmpty()

                val savedProduct = Product(
                    id = productId,
                    title = title,
                    subtitle = subtitle,
                    price = price ?: 0.0,
                    rating = product?.rating ?: 4.5,
                    reviewsCount = product?.reviewsCount ?: 0,
                    tags = tags,
                    description = description,
                    bullets = bullets,
                    imageRes = 0,
                    imageUrl = resolvedImageUrl,
                    imageUrls = resolvedImageUrls,
                    category = category,
                    categoryIds = listOf(category),
                    categoryLeafId = category,
                    origin = normalizedOrigin,
                    stock = stock ?: 0,
                    isBio = isBio,
                    isActive = isActive,
                    status = product?.status ?: "published",
                    discountPercent = discountPercent.coerceIn(0, 90),
                    searchKeywords = generateSearchKeywords(title, subtitle, category),
                    sellerId = resolvedSellerId,
                    sellerName = resolvedSellerName,
                    sellerAvatarUrl = resolvedSellerAvatar,
                    updatedAt = com.google.firebase.Timestamp.now()
                )
                ProductService.saveProduct(savedProduct, knownExistingProduct = product)
            }.onSuccess {
                initialFormSignature = captureFormSignature()
                setResult(RESULT_OK)

                showSuccess(
                    if (product == null) {
                        getString(R.string.admin_product_editor_saved_new)
                    } else {
                        getString(R.string.admin_product_editor_saved_edit)
                    }
                )
                lastAutofillSnapshot = null
                finish()
            }.onFailure { error ->
                Log.e(logTag, "Failed to save product", error)
                val friendlyMessage = friendlySaveError(error)
                showMotionSnackbar(friendlyMessage)
                showDebugSaveError(error, friendlyMessage)
                setSavingState(false)
            }
        }
    }

    private fun setSavingState(isSaving: Boolean, progressTextRes: Int? = null) {
        this.isSaving = isSaving
        findViewById<ProgressBar>(R.id.adminProductEditorProgress)?.visibility =
            if (isSaving || isGeneratingProductInfo) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.adminProductEditorBtnSave)?.apply {
            isEnabled = !isSaving && !isGeneratingProductInfo
            text = if (isSaving) {
                getString(progressTextRes ?: R.string.admin_product_editor_action_saving)
            } else if (product == null) {
                getString(R.string.admin_product_editor_action_add)
            } else {
                getString(R.string.admin_product_editor_action_save)
            }
        }
        listOf(
            R.id.adminProductEditorIvBack,
            R.id.adminProductEditorBtnPickImage,
            R.id.adminProductEditorBtnCameraImage,
            R.id.adminProductEditorBtnRemoveImage,
            R.id.adminProductEditorBtnAutofill
        ).forEach { id ->
            findViewById<View>(id)?.isEnabled = !isSaving && !isGeneratingProductInfo
        }
    }

    private fun setSaveProgress(messageRes: Int) {
        findViewById<MaterialButton>(R.id.adminProductEditorBtnSave)?.text = getString(messageRes)
    }

    private fun setGeneratingProductInfoState(isGenerating: Boolean) {
        isGeneratingProductInfo = isGenerating
        findViewById<ProgressBar>(R.id.adminProductEditorProgress)?.visibility =
            if (isSaving || isGeneratingProductInfo) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.adminProductEditorBtnAutofill)?.apply {
            isEnabled = !isSaving && !isGeneratingProductInfo
            text = if (isGeneratingProductInfo) {
                getString(R.string.admin_product_editor_action_generating)
            } else {
                getString(R.string.admin_product_editor_action_autofill)
            }
        }
        findViewById<MaterialButton>(R.id.adminProductEditorBtnSave)?.isEnabled =
            !isSaving && !isGeneratingProductInfo
        listOf(
            R.id.adminProductEditorIvBack,
            R.id.adminProductEditorBtnPickImage,
            R.id.adminProductEditorBtnCameraImage,
            R.id.adminProductEditorBtnRemoveImage
        ).forEach { id ->
            findViewById<View>(id)?.isEnabled = !isSaving && !isGeneratingProductInfo
        }
    }

    private fun handleCloseRequest() {
        if (isSaving || isGeneratingProductInfo || !hasUnsavedChanges()) {
            finish()
            return
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.admin_product_editor_discard_title))
            .setMessage(getString(R.string.admin_product_editor_discard_message))
            .setNegativeButton(getString(R.string.admin_product_editor_discard_keep), null)
            .setPositiveButton(getString(R.string.admin_product_editor_discard_confirm)) { _: DialogInterface, _: Int ->
                finish()
            }
            .show()
    }

    private fun hasUnsavedChanges(): Boolean = captureFormSignature() != initialFormSignature

    private fun captureFormSignature(): String {
        return listOf(
            findViewById<TextInputEditText>(R.id.etAdminProductTitle)?.text?.toString().orEmpty().trim(),
            findViewById<TextInputEditText>(R.id.etAdminProductSubtitle)?.text?.toString().orEmpty().trim(),
            findViewById<TextInputEditText>(R.id.etAdminProductPrice)?.text?.toString().orEmpty().trim(),
            findViewById<TextInputEditText>(R.id.etAdminProductStock)?.text?.toString().orEmpty().trim(),
            findViewById<TextInputEditText>(R.id.etAdminProductDiscount)?.text?.toString().orEmpty().trim(),
            findViewById<AutoCompleteTextView>(R.id.actvAdminProductCategory)?.text?.toString().orEmpty().trim(),
            findViewById<TextInputEditText>(R.id.etAdminProductOrigin)?.text?.toString().orEmpty().trim(),
            findViewById<TextInputEditText>(R.id.etAdminProductTags)?.text?.toString().orEmpty().trim(),
            findViewById<TextInputEditText>(R.id.etAdminProductDescription)?.text?.toString().orEmpty().trim(),
            findViewById<TextInputEditText>(R.id.etAdminProductBullets)?.text?.toString().orEmpty().trim(),
            (findViewById<SwitchMaterial>(R.id.switchAdminProductBio)?.isChecked == true).toString(),
            (findViewById<SwitchMaterial>(R.id.switchAdminProductActive)?.isChecked == true).toString(),
            editorImages.joinToString(",") { it.stableId }
        ).joinToString("|")
    }

    private fun Product.primaryRemoteImageUrl(): String? {
        return verifiedRemoteImageUrl(imageUrl) ?: imageUrls.firstNotNullOfOrNull(::verifiedRemoteImageUrl)
    }

    private fun Product.orderedRemoteImageUrls(): List<String> {
        return (imageUrls + listOfNotNull(imageUrl))
            .mapNotNull(::verifiedRemoteImageUrl)
            .distinct()
    }

    private fun EditorImage.source(): Any? = localUri ?: verifiedRemoteImageUrl(remoteUrl)

    private suspend fun uploadEditorImages(productId: String): List<String>? {
        val images = editorImages.take(maxProductImages)
        val remoteByIndex = mutableMapOf<Int, String>()
        val localSlots = mutableListOf<Pair<Int, Uri>>()

        images.forEachIndexed { index, editorImage ->
            val remote = verifiedRemoteImageUrl(editorImage.remoteUrl)
            if (remote != null) {
                remoteByIndex[index] = remote
            } else {
                editorImage.localUri?.let { uri -> localSlots += index to uri }
            }
        }

        val uploadedLocalUrls = if (localSlots.isEmpty()) {
            emptyList()
        } else {
            runCatching {
                withTimeout(IMAGE_UPLOAD_TIMEOUT_MS) {
                    ProductImageStorage.uploadProductImages(
                        context = this@AdminProductEditorActivity,
                        productId = productId,
                        uris = localSlots.map { it.second },
                        maxParallelUploads = MAX_PARALLEL_IMAGE_UPLOADS
                    )
                }
            }.getOrElse { imgError ->
                Log.w(logTag, "Image upload failed; product save is blocked", imgError)
                showMotionSnackbar(friendlySaveError(imgError))
                setSavingState(false)
                return null
            }
        }

        val localByIndex = localSlots.mapIndexedNotNull { uploadedIndex, (imageIndex, _) ->
            uploadedLocalUrls.getOrNull(uploadedIndex)?.let { imageIndex to it }
        }.toMap()

        return images
            .mapIndexedNotNull { index, _ -> remoteByIndex[index] ?: localByIndex[index] }
            .distinct()
            .take(maxProductImages)
    }

    private fun verifiedRemoteImageUrl(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        return trimmed.takeIf {
            it.startsWith("https://", ignoreCase = true) ||
                it.startsWith("http://", ignoreCase = true)
        }
    }

    private fun currentDraftTitle(existing: Product?): String {
        return findViewById<TextInputEditText>(R.id.etAdminProductTitle)
            ?.text
            ?.toString()
            .orEmpty()
            .trim()
            .ifBlank { existing?.title ?: getString(R.string.product_default_tag) }
    }

    private fun currentDraftCategory(existing: Product?): String {
        val rawValue = findViewById<AutoCompleteTextView>(R.id.actvAdminProductCategory)
            ?.text
            ?.toString()
            .orEmpty()
            .trim()
            .ifBlank { existing?.category.orEmpty() }
        return resolveCategoryKey(rawValue)
    }

    private fun friendlySaveError(error: Throwable): String {
        if (error is ProductSavePermissionException) {
            return getString(
                if (error.mode == ProductSaveMode.CREATE) {
                    R.string.admin_product_editor_publish_failed_permissions
                } else {
                    R.string.admin_product_editor_edit_failed_permissions
                }
            )
        }
        val message = error.message.orEmpty()
        val baseMessage = when {
            message.contains("app check", ignoreCase = true) || message.contains("app attestation", ignoreCase = true) ->
                getString(R.string.admin_product_editor_save_failed_security)
            message.contains("firebase storage", ignoreCase = true) ||
                message.contains("storage", ignoreCase = true) ||
                message.contains("image upload", ignoreCase = true) ->
                getString(R.string.admin_product_editor_save_failed_image)
            message.contains("publish", ignoreCase = true) && message.contains("permission", ignoreCase = true) ->
                getString(R.string.admin_product_editor_publish_failed_permissions)
            message.contains("permission", ignoreCase = true) ->
                getString(if (product == null) R.string.admin_product_editor_publish_failed_permissions else R.string.admin_product_editor_edit_failed_permissions)
            message.contains("admin", ignoreCase = true) ->
                getString(if (product == null) R.string.admin_product_editor_publish_failed_permissions else R.string.admin_product_editor_edit_failed_permissions)
            message.contains("network", ignoreCase = true) || message.contains("unavailable", ignoreCase = true) ->
                getString(R.string.admin_product_editor_save_failed_network)
            else -> getString(R.string.admin_product_editor_save_failed_generic)
        }
        return if (BuildConfig.DEBUG && message.isNotBlank()) {
            "$baseMessage ($message)"
        } else {
            baseMessage
        }
    }

    private fun friendlyProductGenerationError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("429", ignoreCase = true) ||
                message.contains("quota", ignoreCase = true) ||
                message.contains("api key", ignoreCase = true) ->
                getString(R.string.admin_product_editor_ai_failed_quota)
            message.contains("image", ignoreCase = true) && message.contains("large", ignoreCase = true) ->
                getString(R.string.admin_product_editor_ai_image_too_large)
            message.contains("permission", ignoreCase = true) || message.contains("seller", ignoreCase = true) ->
                getString(R.string.admin_product_editor_save_failed_permissions)
            message.contains("network", ignoreCase = true) ||
                message.contains("unavailable", ignoreCase = true) ||
                message.contains("deadline", ignoreCase = true) ||
                message.contains("timed out", ignoreCase = true) ->
                getString(R.string.admin_product_editor_ai_failed_network)
            else -> getString(R.string.admin_product_editor_ai_failed)
        }
    }

    private fun showDebugSaveError(error: Throwable, friendlyMessage: String) {
        if (!BuildConfig.DEBUG || isFinishing || isDestroyed) return
        val details = buildString {
            append(friendlyMessage)
            append("\n\n")
            append(error.javaClass.simpleName)
            error.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            var cause = error.cause
            var depth = 0
            while (cause != null && depth < 2) {
                append("\nCaused by ")
                append(cause.javaClass.simpleName)
                cause.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                cause = cause.cause
                depth++
            }
            if (toString().contains("PERMISSION", ignoreCase = true)) {
                append("\n\nCheck Firebase: the user document must have role=vendeur and the deployed Firestore rules/functions must allow vendeur product writes.")
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Save failed")
            .setMessage(details.take(1200))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun scrollToError(view: View?) {
        val scrollView = findViewById<androidx.core.widget.NestedScrollView>(R.id.adminProductEditorScroll) ?: return
        view ?: return
        scrollView.post {
            val targetY = (view.top - resources.getDimensionPixelSize(R.dimen.space_24)).coerceAtLeast(0)
            scrollView.smoothScrollTo(0, targetY)
            view.requestFocus()
        }
    }

    private fun buildFallbackDescription(
        title: String,
        subtitle: String,
        category: String,
        origin: String
    ): String {
        val titleValue = title.ifBlank { getString(R.string.product_default_tag) }
        val subtitleValue = subtitle.ifBlank { getString(R.string.admin_product_editor_hint_subtitle) }
        val originValue = origin.ifBlank { "Tunisie" }
        val categoryValue = categoryLabel(category).lowercase(Locale.getDefault())
        return "$titleValue. $subtitleValue. Produit de la categorie $categoryValue, prepare pour une mise en avant claire avec une origine $originValue."
    }

    private fun buildFallbackBullets(
        title: String,
        subtitle: String,
        origin: String,
        tags: List<String>
    ): List<String> {
        val fallbackTag = tags.firstOrNull()?.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        } ?: "Selection locale"
        val titleValue = title.ifBlank { "Produit" }
        val subtitleValue = subtitle.ifBlank { "Presentation claire pour la boutique" }
        val originValue = origin.ifBlank { "Tunisie" }
        return listOf(
            titleValue,
            subtitleValue,
            "Origine $originValue",
            fallbackTag
        )
            .map { it.trim() }
            .filter { it.length >= 3 }
            .distinct()
            .take(4)
    }

    private fun createUniqueProductId(title: String): String {
        val base = ProductCatalog.createIdFromTitle(title)
        val actorPrefix = FirebaseAuthManager.currentUser?.uid
            ?.takeLast(8)
            ?.lowercase(Locale.getDefault())
            ?.replace("[^a-z0-9_]+".toRegex(), "_")
            ?.trim('_')
            ?.takeIf { it.isNotBlank() }
            ?: if (activeRole == UserRoles.VENDEUR || isSellerMode) "seller" else "admin"
        return "${actorPrefix}_${base}_${System.currentTimeMillis()}"
    }

    private fun resolveCategoryKey(rawValue: String): String {
        val normalized = rawValue.trim().lowercase(Locale.getDefault())
        return categories.firstOrNull { option ->
            option.key == normalized || option.label.lowercase(Locale.getDefault()) == normalized
        }?.key ?: MarketplaceCategories.normalizeKey(rawValue).ifBlank { "electronics" }
    }

    private fun categoryLabel(key: String): String {
        return categories.firstOrNull { it.key == key }?.label ?: key.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
    }

    private fun randomTemplateFor(category: String): ProductDraftTemplate {
        val templates = when (category) {
            "home-and-furniture", "real-estate" -> listOf(
                ProductDraftTemplate(
                    title = "Lampe artisanale de Sidi Bou Said",
                    subtitle = "Ceramique peinte a la main et finition chaleureuse",
                    tags = listOf("deco", "maison", "ceramique"),
                    description = "Une lampe decorative imaginee pour apporter une ambiance douce, locale et elegante dans un salon ou une entree.",
                    bullets = listOf(
                        "Finition artisanale peinte a la main",
                        "Style chaleureux pour salon ou chambre",
                        "Piece decorative facile a offrir"
                    ),
                    origin = "Nabeul",
                    bioFriendly = false
                ),
                ProductDraftTemplate(
                    title = "Miroir mural medina",
                    subtitle = "Cadre travaille pour une touche tunisienne chic",
                    tags = listOf("miroir", "deco", "artisanat"),
                    description = "Ce miroir mural donne plus de caractere a la piece grace a un cadre inspire des ateliers traditionnels tunisiens.",
                    bullets = listOf(
                        "Cadre decoratif au style local",
                        "Ideal pour entree ou coin chambre",
                        "Belle idee cadeau maison"
                    ),
                    origin = "Tunis",
                    bioFriendly = false
                )
            )
            "food-and-grocery" -> listOf(
                ProductDraftTemplate(
                    title = "Melange epices du souk",
                    subtitle = "Assemblage parfume pour cuisine tunisienne maison",
                    tags = listOf("epices", "saveurs", "terroir"),
                    description = "Un melange parfume pense pour relever couscous, sauces et marinades avec une vraie signature du terroir tunisien.",
                    bullets = listOf(
                        "Recette inspiree des etals traditionnels",
                        "Parfum intense et equilibre",
                        "Facile a utiliser au quotidien"
                    ),
                    origin = "Kairouan",
                    bioFriendly = true
                ),
                ProductDraftTemplate(
                    title = "Harissa artisanale douce",
                    subtitle = "Recette maison pour relever les plats sans exces",
                    tags = listOf("harissa", "epicerie", "tradition"),
                    description = "Une harissa artisanale douce et parfumee, parfaite pour accompagner vos plats tunisiens ou vos sandwichs du quotidien.",
                    bullets = listOf(
                        "Texture lisse et facile a servir",
                        "Parfum pimente sans etre agressif",
                        "Preparation inspiree des recettes familiales"
                    ),
                    origin = "Cap Bon",
                    bioFriendly = true
                )
            )
            "fashion" -> listOf(
                ProductDraftTemplate(
                    title = "Balgha cuir souple",
                    subtitle = "Confort quotidien avec une finition medina soignee",
                    tags = listOf("mode", "cuir", "balgha"),
                    description = "Une balgha pensee pour allier confort, allure traditionnelle et finition propre pour une utilisation elegante au quotidien.",
                    bullets = listOf(
                        "Cuir souple agreable a porter",
                        "Silhouette inspiree des modeles medina",
                        "Bonne idee cadeau locale"
                    ),
                    origin = "Tunis",
                    bioFriendly = false
                ),
                ProductDraftTemplate(
                    title = "Echarpe tissee artisanale",
                    subtitle = "Texture douce et style traditionnel revisite",
                    tags = listOf("echarpe", "mode", "tissage"),
                    description = "Cette echarpe tissee artisanalement apporte une touche locale, legere et elegante a une tenue de tous les jours.",
                    bullets = listOf(
                        "Texture douce et facile a porter",
                        "Look inspire des savoir-faire tunisiens",
                        "Finition propre pour cadeau ou usage perso"
                    ),
                    origin = "Mahdia",
                    bioFriendly = false
                )
            )
            "beauty-and-health" -> listOf(
                ProductDraftTemplate(
                    title = "Savon naturel a l huile d olive",
                    subtitle = "Soin doux inspire des rituels tunisiens",
                    tags = listOf("beaute", "naturel", "savon"),
                    description = "Un savon doux pour le visage et les mains, formule pour nettoyer en gardant une sensation confortable et naturelle.",
                    bullets = listOf(
                        "Texture douce pour usage quotidien",
                        "Parfum leger et agreable",
                        "Inspire des soins naturels locaux"
                    ),
                    origin = "Sfax",
                    bioFriendly = true
                ),
                ProductDraftTemplate(
                    title = "Huile capillaire au romarin",
                    subtitle = "Routine simple pour nourrir et faire briller",
                    tags = listOf("huile", "capillaire", "naturel"),
                    description = "Une huile capillaire pensee pour nourrir les longueurs, donner de la brillance et accompagner une routine simple.",
                    bullets = listOf(
                        "Application facile avant shampoing",
                        "Fini leger sans effet gras lourd",
                        "Inspire des routines naturelles tunisiennes"
                    ),
                    origin = "Djerba",
                    bioFriendly = true
                )
            )
            else -> listOf(
                ProductDraftTemplate(
                    title = "Boite artisanale gravee",
                    subtitle = "Petite piece deco utile et pleine de caractere",
                    tags = listOf("artisanat", "boite", "cadeau"),
                    description = "Une boite artisanale qui apporte une presence locale sur une console, un bureau ou dans un coin cadeau bien pense.",
                    bullets = listOf(
                        "Piece decorative et pratique",
                        "Finition inspiree des ateliers tunisiens",
                        "Format parfait pour petit cadeau"
                    ),
                    origin = "Sousse",
                    bioFriendly = false
                ),
                ProductDraftTemplate(
                    title = "Plateau cuivre martele",
                    subtitle = "Esprit atelier avec une belle presence visuelle",
                    tags = listOf("cuivre", "artisanat", "table"),
                    description = "Ce plateau en cuivre martele donne tout de suite une sensation plus premium a la table ou a un coin reception.",
                    bullets = listOf(
                        "Belle presence decorative a table",
                        "Travail manuel visible dans les details",
                        "Style local facile a valoriser en vitrine"
                    ),
                    origin = "Kairouan",
                    bioFriendly = false
                )
            )
        }
        return templates.random(random)
    }

    private fun randomPriceFor(category: String): Double = when (category) {
        "food-and-grocery" -> random.nextDouble(12.0, 38.0)
        "beauty-and-health" -> random.nextDouble(18.0, 55.0)
        "fashion" -> random.nextDouble(45.0, 140.0)
        "home-and-furniture", "real-estate" -> random.nextDouble(55.0, 190.0)
        "electronics", "digital-products" -> random.nextDouble(80.0, 650.0)
        "automotive" -> random.nextDouble(120.0, 1200.0)
        else -> random.nextDouble(35.0, 160.0)
    }

    private fun formatGeneratedNumber(value: Double): String {
        return String.format(Locale.US, "%.3f", value)
    }

    private fun formatSuggestedPrice(value: Double): String {
        return String.format(Locale.US, "%.2f", value)
    }

    private fun generateSearchKeywords(title: String, subtitle: String, category: String): List<String> {
        val words = (title + " " + subtitle + " " + category).lowercase(Locale.getDefault())
            .split(" ", ",", "_")
            .filter { it.length >= 3 }
            .distinct()
        return words
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_PRODUCT_ID = "extra_product_id"
        private const val STATE_EDITOR_IMAGES = "state_editor_images"
        private const val STATE_PENDING_CAMERA_URI = "state_pending_camera_uri"
        private const val MAX_PARALLEL_IMAGE_UPLOADS = 2
        private const val IMAGE_UPLOAD_TIMEOUT_MS = 60_000L

        fun createIntent(context: Context, productId: String?, sellerMode: Boolean = false): Intent {
            return Intent(context, AdminProductEditorActivity::class.java).apply {
                putExtra(EXTRA_PRODUCT_ID, productId)
                putExtra(AdminProduitsActivity.EXTRA_SELLER_MODE, sellerMode)
            }
        }
    }
}
