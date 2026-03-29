package isim.ia2y.myapplication

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.storage.StorageException
import kotlinx.coroutines.launch
import java.util.Locale

class AdminProductEditorActivity : AppCompatActivity() {

    private data class CategoryOption(val key: String, val label: String)

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
            }
            renderImagePreview()
        }

    private var selectedImageUri: Uri? = null
    private var currentImageUrl: String? = null
    private var product: Product? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_product_editor)
        setupWindowInsets()
        setupTopBar()
        restoreState(savedInstanceState)

        val productId = intent.getStringExtra(EXTRA_PRODUCT_ID)
        product = productId?.let { ProductCatalog.byId(it) }
        currentImageUrl = product?.imageUrl

        setupCategoryDropdown()
        bindExistingProduct()
        bindActions()
        renderImagePreview()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_SELECTED_IMAGE_URI, selectedImageUri?.toString())
        outState.putString(STATE_CURRENT_IMAGE_URL, currentImageUrl)
    }

    private fun restoreState(savedInstanceState: Bundle?) {
        selectedImageUri = savedInstanceState
            ?.getString(STATE_SELECTED_IMAGE_URI)
            ?.let(Uri::parse)
        currentImageUrl = savedInstanceState?.getString(STATE_CURRENT_IMAGE_URL)
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
        findViewById<View>(R.id.adminProductEditorIvBack)?.setOnClickListener { finish() }
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
            getString(R.string.admin_product_editor_title_edit)
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
            renderImagePreview()
        }
        findViewById<MaterialButton>(R.id.adminProductEditorBtnSave)?.setOnClickListener {
            saveProduct()
        }
    }

    private fun renderImagePreview() {
        val image = findViewById<ImageView>(R.id.adminProductEditorImage)
        val empty = findViewById<TextView>(R.id.adminProductEditorImageEmpty)
        val remove = findViewById<MaterialButton>(R.id.adminProductEditorBtnRemoveImage)
        val existing = product

        val source: Any? = selectedImageUri
            ?: currentImageUrl?.takeIf { it.isNotBlank() }
            ?: existing?.imageRes?.takeIf { it != 0 }
            ?: R.drawable.placeholder

        image?.load(source) {
            crossfade(180)
            placeholder(R.drawable.placeholder)
            error(existing?.imageRes ?: R.drawable.placeholder)
        }

        val hasRealImage = selectedImageUri != null || !currentImageUrl.isNullOrBlank()
        empty?.visibility = if (hasRealImage || existing != null) View.GONE else View.VISIBLE
        remove?.visibility = if (hasRealImage) View.VISIBLE else View.GONE
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

        val title = findViewById<TextInputEditText>(R.id.etAdminProductTitle)?.text?.toString().orEmpty().trim()
        val subtitle = findViewById<TextInputEditText>(R.id.etAdminProductSubtitle)?.text?.toString().orEmpty().trim()
        val price = findViewById<TextInputEditText>(R.id.etAdminProductPrice)?.text?.toString().orEmpty().trim().toDoubleOrNull()
        val stock = findViewById<TextInputEditText>(R.id.etAdminProductStock)?.text?.toString().orEmpty().trim().toIntOrNull()
        val category = resolveCategoryKey(
            findViewById<AutoCompleteTextView>(R.id.actvAdminProductCategory)?.text?.toString().orEmpty()
        )
        val originRaw = findViewById<TextInputEditText>(R.id.etAdminProductOrigin)?.text?.toString().orEmpty().trim()
        val tags = findViewById<TextInputEditText>(R.id.etAdminProductTags)?.text?.toString().orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(getString(R.string.product_default_tag)) }
        val description = findViewById<TextInputEditText>(R.id.etAdminProductDescription)?.text?.toString().orEmpty().trim()
        val bullets = findViewById<TextInputEditText>(R.id.etAdminProductBullets)?.text?.toString().orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val isBio = findViewById<SwitchMaterial>(R.id.switchAdminProductBio)?.isChecked ?: false
        val isActive = findViewById<SwitchMaterial>(R.id.switchAdminProductActive)?.isChecked ?: true

        var valid = true
        if (title.length < 3) {
            findViewById<TextInputLayout>(R.id.tilAdminProductTitle)?.error =
                getString(R.string.admin_product_editor_error_title)
            valid = false
        }
        if (subtitle.length < 3) {
            findViewById<TextInputLayout>(R.id.tilAdminProductSubtitle)?.error =
                getString(R.string.admin_product_editor_error_subtitle)
            valid = false
        }
        if (price == null || price <= 0.0) {
            findViewById<TextInputLayout>(R.id.tilAdminProductPrice)?.error =
                getString(R.string.admin_product_editor_error_price)
            valid = false
        }
        if (stock == null || stock < 0) {
            findViewById<TextInputLayout>(R.id.tilAdminProductStock)?.error =
                getString(R.string.admin_product_editor_error_stock)
            valid = false
        }
        if (originRaw.length < 2) {
            findViewById<TextInputLayout>(R.id.tilAdminProductOrigin)?.error =
                getString(R.string.admin_product_editor_error_origin)
            valid = false
        }
        if (description.length < 12) {
            findViewById<TextInputLayout>(R.id.tilAdminProductDescription)?.error =
                getString(R.string.admin_product_editor_error_description)
            valid = false
        }
        if (bullets.isEmpty()) {
            findViewById<TextInputLayout>(R.id.tilAdminProductBullets)?.error =
                getString(R.string.admin_product_editor_error_highlights)
            valid = false
        }
        if (product == null && selectedImageUri == null) {
            showMotionSnackbar(getString(R.string.admin_product_editor_error_image))
            valid = false
        }
        if (!valid) return

        val productId = product?.id ?: createUniqueProductId(title)
        val normalizedOrigin = originRaw.lowercase(Locale.getDefault()).replace(" ", "_")

        setSavingState(true)
        lifecycleScope.launch {
            runCatching {
                val uploadedImageUrl = selectedImageUri?.let { uri ->
                    runCatching {
                        ProductImageStorage.uploadProductImage(this@AdminProductEditorActivity, productId, uri)
                    }.getOrElse { uploadError ->
                        if (uploadError is StorageException) {
                            showMotionSnackbar(
                                getString(R.string.admin_product_editor_image_upload_failed_fallback)
                            )
                        } else {
                            throw uploadError
                        }
                        null
                    }
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
                    imageUrl = uploadedImageUrl ?: currentImageUrl ?: product?.imageUrl,
                    category = category,
                    origin = normalizedOrigin,
                    stock = stock ?: 0,
                    isBio = isBio,
                    isActive = isActive,
                    updatedAt = System.currentTimeMillis()
                )
                FirestoreService.saveProduct(savedProduct)
            }.onSuccess { saved ->
                ProductCatalog.upsert(saved)
                setResult(RESULT_OK)
                showToast(
                    if (product == null) {
                        getString(R.string.admin_product_editor_saved_new)
                    } else {
                        getString(R.string.admin_product_editor_saved_edit)
                    }
                )
                finish()
            }.onFailure { error ->
                showMotionSnackbar(
                    getString(R.string.admin_product_editor_save_failed, error.localizedMessage ?: "")
                        .trimEnd('.', ' ')
                )
                setSavingState(false)
            }
        }
    }

    private fun setSavingState(isSaving: Boolean) {
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
            R.id.adminProductEditorBtnRemoveImage
        ).forEach { id ->
            findViewById<View>(id)?.isEnabled = !isSaving
        }
    }

    private fun createUniqueProductId(title: String): String {
        val base = ProductCatalog.createIdFromTitle(title)
        if (ProductCatalog.all(includeInactive = true).none { it.id == base }) {
            return base
        }
        return "${base}_${System.currentTimeMillis().toString().takeLast(4)}"
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



    companion object {
        private const val EXTRA_PRODUCT_ID = "extra_product_id"
        private const val STATE_SELECTED_IMAGE_URI = "state_selected_image_uri"
        private const val STATE_CURRENT_IMAGE_URL = "state_current_image_url"

        fun createIntent(context: Context, productId: String?): Intent {
            return Intent(context, AdminProductEditorActivity::class.java).apply {
                putExtra(EXTRA_PRODUCT_ID, productId)
            }
        }
    }
}
