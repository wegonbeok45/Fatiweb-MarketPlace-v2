package isim.ia2y.myapplication

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Drives the dynamic, category-aware attribute / color / size / variant sections of the
 * product editor. Owns all view wiring so [AdminProductEditorActivity] stays focused on the
 * core product fields. Sections are hidden until a product type is chosen.
 */
class ProductAttributesFormController(
    private val activity: AppCompatActivity,
    private val root: View
) {
    private val sectionAttributes: LinearLayout = root.findViewById(R.id.sectionAdminProductAttributes)
    private val attributesContainer: LinearLayout = root.findViewById(R.id.llAdminProductAttributes)
    private val sectionColors: LinearLayout = root.findViewById(R.id.sectionAdminProductColors)
    private val colorGroup: ChipGroup = root.findViewById(R.id.cgAdminProductColors)
    private val addColorButton: MaterialButton = root.findViewById(R.id.btnAdminProductAddColor)
    private val sectionSizes: LinearLayout = root.findViewById(R.id.sectionAdminProductSizes)
    private val sizeGroup: ChipGroup = root.findViewById(R.id.cgAdminProductSizes)
    private val customSizeInput: TextInputEditText = root.findViewById(R.id.etAdminProductCustomSize)
    private val addSizeButton: MaterialButton = root.findViewById(R.id.btnAdminProductAddSize)
    private val sectionDimensions: LinearLayout = root.findViewById(R.id.sectionAdminProductDimensions)
    private val dimWidth: TextInputEditText = root.findViewById(R.id.etAdminProductDimWidth)
    private val dimHeight: TextInputEditText = root.findViewById(R.id.etAdminProductDimHeight)
    private val dimDepth: TextInputEditText = root.findViewById(R.id.etAdminProductDimDepth)
    private val sectionVariants: LinearLayout = root.findViewById(R.id.sectionAdminProductVariants)
    private val generateVariantsButton: MaterialButton = root.findViewById(R.id.btnAdminProductGenerateVariants)
    private val variantsContainer: LinearLayout = root.findViewById(R.id.llAdminProductVariants)

    private var config: ProductTypeConfig? = null

    /** Field key -> a reader returning the captured value (String, Boolean...) or null. */
    private val attributeReaders = linkedMapOf<String, () -> Any?>()

    private val variantRows = mutableListOf<VariantRow>()

    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    init {
        addColorButton.setOnClickListener { promptCustomColor() }
        addSizeButton.setOnClickListener { addCustomSizeFromInput() }
        generateVariantsButton.setOnClickListener { regenerateVariants() }
    }

    val activeConfig: ProductTypeConfig? get() = config

    /** Applies a product type config: shows the relevant sections and rebuilds attribute fields. */
    fun applyConfig(newConfig: ProductTypeConfig?) {
        config = newConfig
        if (newConfig == null) {
            sectionAttributes.visibility = View.GONE
            sectionColors.visibility = View.GONE
            sectionSizes.visibility = View.GONE
            sectionDimensions.visibility = View.GONE
            sectionVariants.visibility = View.GONE
            return
        }

        buildAttributeFields(newConfig)

        sectionColors.visibility = if (newConfig.showColors) View.VISIBLE else View.GONE
        colorGroup.removeAllViews()
        if (newConfig.showColors) {
            ProductTypeCatalog.COLOR_PALETTE.forEach { addColorChip(it, checked = false, removable = false) }
        }

        val predefinedSizes = ProductTypeCatalog.predefinedSizesFor(newConfig.sizeSystem)
        val showSizes = newConfig.sizeSystem != SizeSystem.NONE || newConfig.variantsBySize
        sectionSizes.visibility = if (showSizes) View.VISIBLE else View.GONE
        sizeGroup.removeAllViews()
        if (showSizes) {
            ensurePredefinedSizeChips(predefinedSizes)
        }

        sectionDimensions.visibility = if (newConfig.showDimensions) View.VISIBLE else View.GONE

        sectionVariants.visibility = if (newConfig.supportsVariants) View.VISIBLE else View.GONE
        if (!newConfig.supportsVariants) {
            variantRows.clear()
            variantsContainer.removeAllViews()
        }
    }

    /** Restores all dynamic values from an existing product after [applyConfig] has run. */
    fun bind(product: Product) {
        // Colors — palette already built by applyConfig; check matches and add custom ones.
        product.colorOptions.forEach { color ->
            val existing = findColorChip(color.name)
            if (existing != null) {
                existing.isChecked = true
            } else {
                addColorChip(color, checked = true, removable = true)
            }
        }

        // Sizes — predefined already present; check matches and add custom ones.
        product.sizeOptions.forEach { size ->
            val chip = findSizeChip(size)
            if (chip != null) {
                chip.isChecked = true
            } else {
                addSizeChip(size, checked = true, removable = true)
            }
        }

        // Attributes
        product.attributes.forEach { (key, value) ->
            if (key == "dimensions") {
                (value as? Map<*, *>)?.let { dims ->
                    dimWidth.setText(dimToText(dims["width"]))
                    dimHeight.setText(dimToText(dims["height"]))
                    dimDepth.setText(dimToText(dims["depth"]))
                }
            }
        }
        rebindAttributeValues(product.attributes)

        // Variants
        variantRows.clear()
        variantsContainer.removeAllViews()
        product.variants.forEach { addVariantRow(it) }
    }

    fun collectColors(): List<ProductColor> {
        if (config?.showColors != true) return emptyList()
        val result = mutableListOf<ProductColor>()
        for (i in 0 until colorGroup.childCount) {
            val chip = colorGroup.getChildAt(i) as? Chip ?: continue
            if (chip.isChecked) {
                val color = chip.getTag(R.id.cgAdminProductColors) as? ProductColor
                    ?: ProductColor(chip.text.toString())
                result += color
            }
        }
        return result.distinctBy { it.name.lowercase(Locale.getDefault()) }
    }

    fun collectSizes(): List<String> {
        val showSizes = config?.let { it.sizeSystem != SizeSystem.NONE || it.variantsBySize } ?: false
        if (!showSizes) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until sizeGroup.childCount) {
            val chip = sizeGroup.getChildAt(i) as? Chip ?: continue
            if (chip.isChecked) result += chip.text.toString()
        }
        return result.distinct()
    }

    fun collectAttributes(): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        attributeReaders.forEach { (key, reader) ->
            val value = reader()
            if (value != null && value != "" && value != false) {
                result[key] = value
            } else if (value == true) {
                result[key] = true
            }
        }
        if (config?.showDimensions == true) {
            val dims = linkedMapOf<String, Any?>()
            dimWidth.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()?.let { dims["width"] = it }
            dimHeight.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()?.let { dims["height"] = it }
            dimDepth.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()?.let { dims["depth"] = it }
            if (dims.isNotEmpty()) result["dimensions"] = dims
        }
        return result
    }

    fun collectVariants(): List<ProductVariant> = variantRows.map { it.toVariant() }

    /** Returns an error message resource string, or null when valid. */
    fun validate(): String? {
        val cfg = config ?: return null
        if (cfg.showDimensions) {
            val anyInvalid = listOf(dimWidth, dimHeight, dimDepth).any { input ->
                val raw = input.text?.toString()?.trim().orEmpty()
                if (raw.isEmpty()) return@any false
                val parsed = raw.replace(',', '.').toDoubleOrNull()
                parsed == null || parsed < 0
            }
            if (anyInvalid) return activity.getString(R.string.pa_error_dimensions)
        }
        if (variantRows.isNotEmpty()) {
            for (row in variantRows) {
                val stock = row.stockValue()
                if (stock == null || stock < 0) return activity.getString(R.string.pa_error_variant_stock)
                val price = row.priceValue()
                if (price != null && price < 0) return activity.getString(R.string.pa_error_variant_price)
            }
            val hasActiveStocked = variantRows.any { it.isActive() && (it.stockValue() ?: 0) > 0 }
            if (!hasActiveStocked) return activity.getString(R.string.pa_error_variant_required)
        }
        return null
    }

    /** Signature contribution so the editor can detect unsaved changes. */
    fun signature(): String = buildString {
        append(config?.key.orEmpty()).append('|')
        append(collectColors().joinToString(",") { it.name }).append('|')
        append(collectSizes().joinToString(",")).append('|')
        append(collectAttributes().entries.joinToString(",") { "${it.key}=${it.value}" }).append('|')
        append(variantRows.joinToString(",") { "${it.variantId}:${it.stockValue()}:${it.priceValue()}:${it.isActive()}" })
    }

    // ----- Attribute fields -----

    private fun buildAttributeFields(cfg: ProductTypeConfig) {
        attributesContainer.removeAllViews()
        attributeReaders.clear()
        val inflater = LayoutInflater.from(activity)
        cfg.attributeFields.forEach { spec ->
            when (spec.input) {
                AttributeInput.BOOLEAN -> {
                    val switch = inflater.inflate(R.layout.item_admin_attribute_switch, attributesContainer, false) as SwitchMaterial
                    switch.text = activity.getString(spec.labelRes)
                    attributesContainer.addView(switch)
                    attributeReaders[spec.key] = { switch.isChecked }
                }
                AttributeInput.DROPDOWN -> {
                    val til = inflater.inflate(R.layout.item_admin_attribute_dropdown, attributesContainer, false) as TextInputLayout
                    til.hint = activity.getString(spec.labelRes)
                    val dropdown = til.findViewById<AutoCompleteTextView>(R.id.attributeFieldDropdown)
                    val options = spec.optionsRes?.let { activity.resources.getStringArray(it).toList() } ?: emptyList()
                    dropdown.setAdapter(ArrayAdapter(activity, android.R.layout.simple_list_item_1, options))
                    dropdown.setOnClickListener { dropdown.showDropDown() }
                    attributesContainer.addView(til)
                    attributeReaders[spec.key] = { dropdown.text?.toString()?.trim().orEmpty() }
                }
                AttributeInput.DATE -> {
                    val til = inflater.inflate(R.layout.item_admin_attribute_text, attributesContainer, false) as TextInputLayout
                    til.hint = activity.getString(spec.labelRes)
                    val input = til.findViewById<TextInputEditText>(R.id.attributeFieldInput)
                    input.isFocusable = false
                    input.isClickable = true
                    input.setOnClickListener { pickDate(input) }
                    attributesContainer.addView(til)
                    attributeReaders[spec.key] = { input.text?.toString()?.trim().orEmpty() }
                }
                else -> {
                    val til = inflater.inflate(R.layout.item_admin_attribute_text, attributesContainer, false) as TextInputLayout
                    til.hint = activity.getString(spec.labelRes)
                    val input = til.findViewById<TextInputEditText>(R.id.attributeFieldInput)
                    if (spec.input == AttributeInput.NUMBER) {
                        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                    } else if (spec.input == AttributeInput.MULTILINE) {
                        input.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        input.minLines = 2
                    }
                    attributesContainer.addView(til)
                    attributeReaders[spec.key] = { input.text?.toString()?.trim().orEmpty() }
                }
            }
        }
        sectionAttributes.visibility = if (cfg.attributeFields.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun rebindAttributeValues(attributes: Map<String, Any?>) {
        val cfg = config ?: return
        // Rebuild fields so views are fresh, then set their values via tagged keys.
        cfg.attributeFields.forEach { spec ->
            val value = attributes[spec.key] ?: return@forEach
            val view = attributesContainer.findViewWithKey(spec.key, cfg) ?: return@forEach
            when (spec.input) {
                AttributeInput.BOOLEAN -> (view as? SwitchMaterial)?.isChecked = value as? Boolean ?: false
                AttributeInput.DROPDOWN -> (view as? TextInputLayout)
                    ?.findViewById<AutoCompleteTextView>(R.id.attributeFieldDropdown)
                    ?.setText(value.toString(), false)
                else -> (view as? TextInputLayout)
                    ?.findViewById<TextInputEditText>(R.id.attributeFieldInput)
                    ?.setText(value.toString())
            }
        }
    }

    private fun ViewGroup.findViewWithKey(key: String, cfg: ProductTypeConfig): View? {
        val index = cfg.attributeFields.indexOfFirst { it.key == key }
        if (index < 0 || index >= childCount) return null
        return getChildAt(index)
    }

    private fun pickDate(target: TextInputEditText) {
        val picker = MaterialDatePicker.Builder.datePicker().build()
        picker.addOnPositiveButtonClickListener { selection ->
            target.setText(isoDateFormat.format(java.util.Date(selection)))
        }
        picker.show(activity.supportFragmentManager, "pa_date_picker")
    }

    // ----- Colors -----

    private fun findColorChip(name: String): Chip? {
        for (i in 0 until colorGroup.childCount) {
            val chip = colorGroup.getChildAt(i) as? Chip ?: continue
            if (chip.text.toString().equals(name, ignoreCase = true)) return chip
        }
        return null
    }

    private fun addColorChip(color: ProductColor, checked: Boolean, removable: Boolean) {
        val chip = Chip(activity).apply {
            text = color.name
            isCheckable = true
            isChecked = checked
            isCheckedIconVisible = true
            setTag(R.id.cgAdminProductColors, color)
            chipIcon = colorSwatchDrawable(color.hex)
            isChipIconVisible = color.hex.isNotBlank()
            if (removable) {
                isCloseIconVisible = true
                setOnCloseIconClickListener { colorGroup.removeView(this) }
            }
        }
        colorGroup.addView(chip)
    }

    private fun colorSwatchDrawable(hex: String): GradientDrawable? {
        val parsed = runCatching { Color.parseColor(hex) }.getOrNull() ?: return null
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(parsed)
            setStroke(activity.resources.getDimensionPixelSize(R.dimen.ms_stroke_hairline), Color.parseColor("#33000000"))
        }
    }

    private fun promptCustomColor() {
        val container = LayoutInflater.from(activity)
            .inflate(R.layout.item_admin_attribute_text, colorGroup, false) as TextInputLayout
        container.hint = activity.getString(R.string.pa_color_custom_name)
        val nameInput = container.findViewById<TextInputEditText>(R.id.attributeFieldInput)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.pa_add_custom_color)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty() && findColorChip(name) == null) {
                    addColorChip(ProductColor(name), checked = true, removable = true)
                }
            }
            .show()
    }

    // ----- Sizes -----

    private fun ensurePredefinedSizeChips(sizes: List<String>) {
        if (sizes.isEmpty()) return
        sizes.forEach { size ->
            if (findSizeChip(size) == null) addSizeChip(size, checked = false, removable = false)
        }
    }

    private fun findSizeChip(size: String): Chip? {
        for (i in 0 until sizeGroup.childCount) {
            val chip = sizeGroup.getChildAt(i) as? Chip ?: continue
            if (chip.text.toString().equals(size, ignoreCase = true)) return chip
        }
        return null
    }

    private fun addSizeChip(size: String, checked: Boolean, removable: Boolean) {
        val chip = Chip(activity).apply {
            text = size
            isCheckable = true
            isChecked = checked
            isCheckedIconVisible = true
            if (removable) {
                isCloseIconVisible = true
                setOnCloseIconClickListener { sizeGroup.removeView(this) }
            }
        }
        sizeGroup.addView(chip)
    }

    private fun addCustomSizeFromInput() {
        val size = customSizeInput.text?.toString()?.trim().orEmpty()
        if (size.isEmpty()) return
        val existing = findSizeChip(size)
        if (existing != null) {
            existing.isChecked = true
        } else {
            addSizeChip(size, checked = true, removable = true)
        }
        customSizeInput.setText("")
    }

    // ----- Variants -----

    private fun regenerateVariants() {
        val cfg = config ?: return
        val colors = if (cfg.variantsByColor) collectColors() else emptyList()
        val sizes = if (cfg.variantsBySize) collectSizes() else emptyList()

        val combos: List<Pair<ProductColor?, String>> = when {
            colors.isNotEmpty() && sizes.isNotEmpty() -> colors.flatMap { c -> sizes.map { s -> c to s } }
            colors.isNotEmpty() -> colors.map { it to "" }
            sizes.isNotEmpty() -> sizes.map { null to it }
            else -> emptyList()
        }

        // Preserve any stock/price already entered for matching variant ids.
        val previous = variantRows.associateBy { it.variantId }
        variantRows.clear()
        variantsContainer.removeAllViews()
        combos.forEach { (color, size) ->
            val variantId = ProductVariant.buildVariantId(color?.name.orEmpty(), size)
            if (variantId.isEmpty()) return@forEach
            val prior = previous[variantId]
            val seed = ProductVariant(
                variantId = variantId,
                colorName = color?.name.orEmpty(),
                colorHex = color?.hex.orEmpty(),
                size = size,
                stock = prior?.stockValue() ?: 0,
                priceOverrideMinor = prior?.priceValue()?.let { toMinorUnits(it) },
                active = prior?.isActive() ?: true
            )
            addVariantRow(seed)
        }
    }

    private fun addVariantRow(seed: ProductVariant) {
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.item_admin_variant_row, variantsContainer, false)
        val row = VariantRow(seed.variantId, seed.colorName, seed.colorHex, seed.size, view)
        view.findViewById<android.widget.TextView>(R.id.variantLabel).text =
            seed.label.ifBlank { seed.variantId }
        view.findViewById<View>(R.id.variantColorSwatch).background =
            colorSwatchDrawable(seed.colorHex) ?: view.context.getDrawable(R.drawable.bg_color_swatch)
        view.findViewById<TextInputEditText>(R.id.etVariantStock).setText(seed.stock.toString())
        seed.priceOverrideMinor?.let {
            view.findViewById<TextInputEditText>(R.id.etVariantPrice).setText(fromMinorUnits(it).toString())
        }
        view.findViewById<MaterialSwitch>(R.id.variantActive).isChecked = seed.active
        view.findViewById<View>(R.id.variantRemove).setOnClickListener {
            variantsContainer.removeView(view)
            variantRows.remove(row)
        }
        variantRows.add(row)
        variantsContainer.addView(view)
    }

    private fun dimToText(value: Any?): String = when (value) {
        is Number -> {
            val d = value.toDouble()
            if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
        }
        else -> ""
    }

    private inner class VariantRow(
        val variantId: String,
        val colorName: String,
        val colorHex: String,
        val size: String,
        val view: View
    ) {
        private val stockInput: TextInputEditText = view.findViewById(R.id.etVariantStock)
        private val priceInput: TextInputEditText = view.findViewById(R.id.etVariantPrice)
        private val activeSwitch: MaterialSwitch = view.findViewById(R.id.variantActive)

        fun stockValue(): Int? = stockInput.text?.toString()?.trim()?.toIntOrNull()
        fun priceValue(): Double? = priceInput.text?.toString()?.trim()?.replace(',', '.')?.toDoubleOrNull()
        fun isActive(): Boolean = activeSwitch.isChecked

        fun toVariant(): ProductVariant = ProductVariant(
            variantId = variantId,
            colorName = colorName,
            colorHex = colorHex,
            size = size,
            stock = (stockValue() ?: 0).coerceAtLeast(0),
            priceOverrideMinor = priceValue()?.takeIf { it >= 0 }?.let { toMinorUnits(it) },
            active = isActive()
        )
    }
}
