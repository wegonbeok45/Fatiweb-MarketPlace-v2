package isim.ia2y.myapplication

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.storage.StorageException
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.random.Random

class AdminProductEditorActivity : AppCompatActivity() {
    private val logTag = "AdminProductEditor"

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
        listOf(
            CategoryOption("craft", getString(R.string.product_category_craft)),
            CategoryOption("decor", getString(R.string.product_category_decor)),
            CategoryOption("food", getString(R.string.product_category_food)),
            CategoryOption("fashion", getString(R.string.product_category_fashion)),
            CategoryOption("beauty", getString(R.string.product_category_beauty))
        )
    }

    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            selectedImageUri = uri
            if (uri != null) {
                currentImageUrl = null
                imageClearedExplicitly = false
            }
            renderImagePreview()
        }

    private var selectedImageUri: Uri? = null
    private var currentImageUrl: String? = null
    private var product: Product? = null
    private var imageClearedExplicitly = false
    private val random = Random(System.currentTimeMillis())
    private var initialFormSignature = ""
    private var isSaving = false
    private var lastAutofillSnapshot: AutofillSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_product_editor)
        setupWindowInsets()
        setupTopBar()
        restoreState(savedInstanceState)

        val productId = intent.getStringExtra(EXTRA_PRODUCT_ID)
        product = productId?.let { ProductCatalog.byId(it) }
        if (savedInstanceState == null) {
            currentImageUrl = product?.imageUrl
        } else if (currentImageUrl == null && !imageClearedExplicitly && selectedImageUri == null) {
            currentImageUrl = product?.imageUrl
        }

        setupCategoryDropdown()
        bindExistingProduct()
        bindActions()
        bindImagePreviewRefresh()
        renderImagePreview()
        initialFormSignature = captureFormSignature()
        onBackPressedDispatcher.addCallback(this) {
            handleCloseRequest()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED_IMAGE_URI, selectedImageUri?.toString())
        outState.putString(STATE_CURRENT_IMAGE_URL, currentImageUrl)
        outState.putBoolean(STATE_IMAGE_CLEARED, imageClearedExplicitly)
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        selectedImageUri = savedInstanceState
            ?.getString(STATE_SELECTED_IMAGE_URI)
            ?.let(Uri::parse)
        currentImageUrl = savedInstanceState?.getString(STATE_CURRENT_IMAGE_URL)
        imageClearedExplicitly = savedInstanceState?.getBoolean(STATE_IMAGE_CLEARED, false) == true
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

    private fun bindActions() {
        findViewById<MaterialButton>(R.id.adminProductEditorBtnPickImage)?.setOnClickListener {
            imagePicker.launch("image/*")
        }
        findViewById<MaterialButton>(R.id.adminProductEditorBtnRemoveImage)?.setOnClickListener {
            selectedImageUri = null
            currentImageUrl = null
            imageClearedExplicitly = true
            renderImagePreview()
        }
        findViewById<MaterialButton>(R.id.adminProductEditorBtnAutofill)?.let { button ->
            if (BuildConfig.DEBUG) {
                button.visibility = View.VISIBLE
                button.setOnClickListener {
                    fillRandomProductDraft()
                }
            } else {
                button.visibility = View.GONE
            }
        }
        findViewById<MaterialButton>(R.id.adminProductEditorBtnSave)?.setOnClickListener {
            saveProduct()
        }
    }

    private fun bindImagePreviewRefresh() {
        findViewById<TextInputEditText>(R.id.etAdminProductTitle)?.doAfterTextChanged {
            if (selectedImageUri == null && currentImageUrl.isNullOrBlank()) {
                renderImagePreview()
            }
        }
        findViewById<AutoCompleteTextView>(R.id.actvAdminProductCategory)?.doAfterTextChanged {
            if (selectedImageUri == null && currentImageUrl.isNullOrBlank()) {
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
        findViewById<TextInputEditText>(R.id.etAdminProductPrice)?.setText(formatGeneratedNumber(generatedPrice))
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

    private fun renderImagePreview() {
        val image = findViewById<ImageView>(R.id.adminProductEditorImage)
        val empty = findViewById<TextView>(R.id.adminProductEditorImageEmpty)
        val remove = findViewById<MaterialButton>(R.id.adminProductEditorBtnRemoveImage)
        val existing = product
        val fallbackImageRes = existing?.imageRes?.takeIf { it != 0 && !imageClearedExplicitly }
        val generatedPreview = GeneratedProductArt.buildDataUrl(
            context = this,
            title = currentDraftTitle(existing),
            category = currentDraftCategory(existing)
        )

        val source: Any? = when {
            selectedImageUri != null -> selectedImageUri
            !currentImageUrl.isNullOrBlank() -> currentImageUrl
            fallbackImageRes != null -> fallbackImageRes
            else -> generatedPreview
        }

        image?.load(source) {
            crossfade(180)
            placeholder(R.drawable.placeholder)
            error(existing?.imageRes ?: R.drawable.placeholder)
        }

        val hasDisplayImage =
            selectedImageUri != null || !currentImageUrl.isNullOrBlank() || fallbackImageRes != null || generatedPreview.isNotBlank()
        empty?.visibility = View.GONE
        remove?.visibility = if (selectedImageUri != null || !currentImageUrl.isNullOrBlank()) View.VISIBLE else View.GONE
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
        val categoryLabel = findViewById<AutoCompleteTextView>(R.id.actvAdminProductCategory)?.text?.toString().orEmpty()
            .ifBlank { autofillSnapshot?.categoryLabel.orEmpty() }
        val category = if (categoryLabel.isNotBlank()) {
            resolveCategoryKey(categoryLabel)
        } else {
            autofillSnapshot?.categoryKey ?: "craft"
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

        setSavingState(true)
        lifecycleScope.launch {
            runCatching {
                val uploadedImageUrl = selectedImageUri?.let { uri ->
                    ProductImageStorage.uploadProductImage(this@AdminProductEditorActivity, productId, uri)
                }
                val generatedFallbackImageUrl = GeneratedProductArt.buildDataUrl(
                    context = this@AdminProductEditorActivity,
                    title = title,
                    category = category
                )
                val resolvedImageUrl = when {
                    uploadedImageUrl != null -> uploadedImageUrl
                    !currentImageUrl.isNullOrBlank() -> currentImageUrl
                    !imageClearedExplicitly && !product?.imageUrl.isNullOrBlank() -> product?.imageUrl
                    else -> generatedFallbackImageUrl
                }
                val resolvedImageUrls = when {
                    uploadedImageUrl != null -> listOf(uploadedImageUrl)
                    !currentImageUrl.isNullOrBlank() -> listOf(currentImageUrl!!)
                    !imageClearedExplicitly && !product?.imageUrls.isNullOrEmpty() -> product?.imageUrls.orEmpty()
                    else -> listOfNotNull(resolvedImageUrl)
                }
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
                    imageRes = product?.imageRes ?: ProductCatalog.imageForCategory(category),
                    imageUrl = resolvedImageUrl,
                    imageUrls = resolvedImageUrls,
                    category = category,
                    categoryIds = listOf(category),
                    origin = normalizedOrigin,
                    stock = stock ?: 0,
                    isBio = isBio,
                    isActive = isActive,
                    status = product?.status ?: "published",
                    searchKeywords = generateSearchKeywords(title, subtitle, category),
                    updatedAt = com.google.firebase.Timestamp.now()
                )
                ProductService.saveProduct(savedProduct)
            }.onSuccess {
                imageClearedExplicitly = false
                initialFormSignature = captureFormSignature()
                setResult(RESULT_OK)
                showToast(
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
                showMotionSnackbar(friendlySaveError(error))
                setSavingState(false)
            }
        }
    }

    private fun setSavingState(isSaving: Boolean) {
        this.isSaving = isSaving
        findViewById<ProgressBar>(R.id.adminProductEditorProgress)?.visibility =
            if (isSaving) View.VISIBLE else View.GONE
        findViewById<MaterialButton>(R.id.adminProductEditorBtnSave)?.apply {
            isEnabled = !isSaving
            text = if (isSaving) {
                getString(R.string.admin_product_editor_action_saving)
            } else if (product == null) {
                getString(R.string.admin_product_editor_action_add)
            } else {
                getString(R.string.admin_product_editor_action_save)
            }
        }
        listOf(
            R.id.adminProductEditorIvBack,
            R.id.adminProductEditorBtnPickImage,
            R.id.adminProductEditorBtnRemoveImage,
            R.id.adminProductEditorBtnAutofill
        ).forEach { id ->
            findViewById<View>(id)?.isEnabled = !isSaving
        }
    }

    private fun handleCloseRequest() {
        if (isSaving || !hasUnsavedChanges()) {
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
            findViewById<AutoCompleteTextView>(R.id.actvAdminProductCategory)?.text?.toString().orEmpty().trim(),
            findViewById<TextInputEditText>(R.id.etAdminProductOrigin)?.text?.toString().orEmpty().trim(),
            findViewById<TextInputEditText>(R.id.etAdminProductTags)?.text?.toString().orEmpty().trim(),
            findViewById<TextInputEditText>(R.id.etAdminProductDescription)?.text?.toString().orEmpty().trim(),
            findViewById<TextInputEditText>(R.id.etAdminProductBullets)?.text?.toString().orEmpty().trim(),
            (findViewById<SwitchMaterial>(R.id.switchAdminProductBio)?.isChecked == true).toString(),
            (findViewById<SwitchMaterial>(R.id.switchAdminProductActive)?.isChecked == true).toString(),
            selectedImageUri?.toString().orEmpty(),
            currentImageUrl.orEmpty(),
            product?.imageUrl.orEmpty(),
            imageClearedExplicitly.toString()
        ).joinToString("|")
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
        val message = error.message.orEmpty()
        return when {
            error is StorageException -> getString(R.string.admin_product_editor_save_failed_image)
            message.contains("app check", ignoreCase = true) || message.contains("app attestation", ignoreCase = true) ->
                getString(R.string.admin_product_editor_save_failed_security)
            message.contains("permission", ignoreCase = true) -> getString(R.string.admin_product_editor_save_failed_permissions)
            message.contains("admin", ignoreCase = true) -> getString(R.string.admin_product_editor_save_failed_permissions)
            message.contains("network", ignoreCase = true) || message.contains("unavailable", ignoreCase = true) ->
                getString(R.string.admin_product_editor_save_failed_network)
            else -> getString(R.string.admin_product_editor_save_failed_generic)
        }
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
        if (ProductCatalog.all(includeInactive = true).none { it.id == base }) {
            return base
        }
        return "${base}_${System.currentTimeMillis()}"
    }

    private fun resolveCategoryKey(rawValue: String): String {
        val normalized = rawValue.trim().lowercase(Locale.getDefault())
        return categories.firstOrNull { option ->
            option.key == normalized || option.label.lowercase(Locale.getDefault()) == normalized
        }?.key ?: "craft"
    }

    private fun categoryLabel(key: String): String {
        return categories.firstOrNull { it.key == key }?.label ?: key.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
    }

    private fun randomTemplateFor(category: String): ProductDraftTemplate {
        val templates = when (category) {
            "decor" -> listOf(
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
            "food" -> listOf(
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
            "beauty" -> listOf(
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
        "food" -> random.nextDouble(12.0, 38.0)
        "beauty" -> random.nextDouble(18.0, 55.0)
        "fashion" -> random.nextDouble(45.0, 140.0)
        "decor" -> random.nextDouble(55.0, 190.0)
        else -> random.nextDouble(35.0, 160.0)
    }

    private fun formatGeneratedNumber(value: Double): String {
        return String.format(Locale.US, "%.3f", value)
    }

    private fun generateSearchKeywords(title: String, subtitle: String, category: String): List<String> {
        val words = (title + " " + subtitle + " " + category).lowercase(Locale.getDefault())
            .split(" ", ",", "_")
            .filter { it.length >= 3 }
            .distinct()
        return words
    }

    companion object {
        private const val EXTRA_PRODUCT_ID = "extra_product_id"
        private const val STATE_SELECTED_IMAGE_URI = "state_selected_image_uri"
        private const val STATE_CURRENT_IMAGE_URL = "state_current_image_url"
        private const val STATE_IMAGE_CLEARED = "state_image_cleared"

        fun createIntent(context: Context, productId: String?): Intent {
            return Intent(context, AdminProductEditorActivity::class.java).apply {
                putExtra(EXTRA_PRODUCT_ID, productId)
            }
        }
    }
}
