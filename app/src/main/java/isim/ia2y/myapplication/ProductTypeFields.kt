package isim.ia2y.myapplication

import androidx.annotation.ArrayRes
import androidx.annotation.StringRes

/** Size vocabulary that applies to a product type. */
enum class SizeSystem { NONE, CLOTHING, SHOES }

/** Which input control renders an attribute field in the editor. */
enum class AttributeInput { TEXT, MULTILINE, NUMBER, DROPDOWN, BOOLEAN, DATE }

/** Dimension along which a product can be split into variants. */
object VariantAxis {
    const val COLOR = "color"
    const val SIZE = "size"
}

/**
 * One category-specific attribute captured by the editor and stored in [Product.attributes].
 * [labelRes] is the field label; [optionsRes] supplies choices for [AttributeInput.DROPDOWN].
 */
data class FieldSpec(
    val key: String,
    @StringRes val labelRes: Int,
    val input: AttributeInput,
    @ArrayRes val optionsRes: Int? = null
)

/** A selectable product type in the editor dropdown. */
data class ProductTypeOption(val key: String, @StringRes val labelRes: Int)

/**
 * Declarative description of which sections, size system, attributes and variant axes
 * apply to a given product type. Drives the dynamic add/edit form and the buyer screen.
 */
data class ProductTypeConfig(
    val key: String,
    @StringRes val labelRes: Int,
    val sizeSystem: SizeSystem,
    val showColors: Boolean,
    val showDimensions: Boolean,
    val attributeFields: List<FieldSpec>,
    val variantBy: Set<String>
) {
    val supportsVariants: Boolean get() = variantBy.isNotEmpty()
    val variantsByColor: Boolean get() = variantBy.contains(VariantAxis.COLOR)
    val variantsBySize: Boolean get() = variantBy.contains(VariantAxis.SIZE)
}

object ProductTypeCatalog {

    const val TYPE_TSHIRT = "tshirt"
    const val TYPE_HOODIE = "hoodie"
    const val TYPE_PANTS = "pants"
    const val TYPE_DRESS = "dress"
    const val TYPE_SHOES = "shoes"
    const val TYPE_BAG = "bag"
    const val TYPE_ACCESSORY = "accessory"
    const val TYPE_FURNITURE = "furniture"
    const val TYPE_DECORATION = "decoration"
    const val TYPE_ELECTRONICS = "electronics"
    const val TYPE_HANDMADE = "handmade"
    const val TYPE_FOOD = "food"
    const val TYPE_OTHER = "other"

    val CLOTHING_SIZES: List<String> = listOf("XS", "S", "M", "L", "XL", "XXL")
    val SHOE_SIZES: List<String> = (35..46).map { "EU $it" }

    /** Predefined visual palette; names are buyer-facing, hex drives the chip swatch. */
    val COLOR_PALETTE: List<ProductColor> = listOf(
        ProductColor("Noir", "#171717"),
        ProductColor("Blanc", "#FFFFFF"),
        ProductColor("Rouge", "#C7374C"),
        ProductColor("Bleu", "#1F4FA8"),
        ProductColor("Vert", "#1F8A5B"),
        ProductColor("Jaune", "#E8C547"),
        ProductColor("Marron", "#74613F"),
        ProductColor("Beige", "#D9C9A8"),
        ProductColor("Gris", "#9A938A"),
        ProductColor("Rose", "#E58BA8"),
        ProductColor("Violet", "#7A5AA8"),
        ProductColor("Orange", "#D38A1D")
    )

    private val clothing = listOf(
        FieldSpec("material", R.string.pa_attr_material, AttributeInput.TEXT),
        FieldSpec("gender", R.string.pa_attr_gender, AttributeInput.DROPDOWN, R.array.pa_options_gender),
        FieldSpec("fit", R.string.pa_attr_fit, AttributeInput.DROPDOWN, R.array.pa_options_fit),
        FieldSpec("brand", R.string.pa_attr_brand, AttributeInput.TEXT),
        FieldSpec("measurements", R.string.pa_attr_measurements, AttributeInput.TEXT),
        FieldSpec("care", R.string.pa_attr_care, AttributeInput.MULTILINE)
    )

    private val shoes = listOf(
        FieldSpec("gender", R.string.pa_attr_gender, AttributeInput.DROPDOWN, R.array.pa_options_gender),
        FieldSpec("brand", R.string.pa_attr_brand, AttributeInput.TEXT),
        FieldSpec("material", R.string.pa_attr_material, AttributeInput.TEXT),
        FieldSpec("condition", R.string.pa_attr_condition, AttributeInput.DROPDOWN, R.array.pa_options_condition)
    )

    private val bag = listOf(
        FieldSpec("material", R.string.pa_attr_material, AttributeInput.TEXT),
        FieldSpec("brand", R.string.pa_attr_brand, AttributeInput.TEXT),
        FieldSpec("style", R.string.pa_attr_style, AttributeInput.TEXT)
    )

    private val furniture = listOf(
        FieldSpec("material", R.string.pa_attr_material, AttributeInput.TEXT),
        FieldSpec("weight", R.string.pa_attr_weight, AttributeInput.TEXT),
        FieldSpec("assembly", R.string.pa_attr_assembly, AttributeInput.BOOLEAN),
        FieldSpec("deliveryAvailable", R.string.pa_attr_delivery_available, AttributeInput.BOOLEAN),
        FieldSpec("condition", R.string.pa_attr_condition, AttributeInput.DROPDOWN, R.array.pa_options_condition)
    )

    private val decoration = listOf(
        FieldSpec("material", R.string.pa_attr_material, AttributeInput.TEXT),
        FieldSpec("handmade", R.string.pa_attr_handmade, AttributeInput.BOOLEAN),
        FieldSpec("fragile", R.string.pa_attr_fragile, AttributeInput.BOOLEAN),
        FieldSpec("roomType", R.string.pa_attr_room_type, AttributeInput.TEXT)
    )

    private val electronics = listOf(
        FieldSpec("brand", R.string.pa_attr_brand, AttributeInput.TEXT),
        FieldSpec("model", R.string.pa_attr_model, AttributeInput.TEXT),
        FieldSpec("storage", R.string.pa_attr_storage, AttributeInput.TEXT),
        FieldSpec("ram", R.string.pa_attr_ram, AttributeInput.TEXT),
        FieldSpec("warranty", R.string.pa_attr_warranty, AttributeInput.TEXT),
        FieldSpec("accessories", R.string.pa_attr_accessories, AttributeInput.TEXT),
        FieldSpec("battery", R.string.pa_attr_battery, AttributeInput.TEXT),
        FieldSpec("specs", R.string.pa_attr_specs, AttributeInput.MULTILINE)
    )

    private val handmade = listOf(
        FieldSpec("material", R.string.pa_attr_material, AttributeInput.TEXT),
        FieldSpec("customizable", R.string.pa_attr_customizable, AttributeInput.BOOLEAN),
        FieldSpec("productionTime", R.string.pa_attr_production_time, AttributeInput.TEXT),
        FieldSpec("originRegion", R.string.pa_attr_origin_region, AttributeInput.TEXT),
        FieldSpec("craftTechnique", R.string.pa_attr_craft_technique, AttributeInput.TEXT),
        FieldSpec("unique", R.string.pa_attr_unique, AttributeInput.BOOLEAN)
    )

    private val food = listOf(
        FieldSpec("weight", R.string.pa_attr_weight, AttributeInput.TEXT),
        FieldSpec("expiry", R.string.pa_attr_expiry, AttributeInput.DATE),
        FieldSpec("ingredients", R.string.pa_attr_ingredients, AttributeInput.MULTILINE),
        FieldSpec("allergens", R.string.pa_attr_allergens, AttributeInput.TEXT),
        FieldSpec("storageInstructions", R.string.pa_attr_storage_instructions, AttributeInput.MULTILINE),
        FieldSpec("homemade", R.string.pa_attr_homemade, AttributeInput.BOOLEAN)
    )

    private val other = listOf(
        FieldSpec("material", R.string.pa_attr_material, AttributeInput.TEXT),
        FieldSpec("brand", R.string.pa_attr_brand, AttributeInput.TEXT),
        FieldSpec("condition", R.string.pa_attr_condition, AttributeInput.DROPDOWN, R.array.pa_options_condition)
    )

    val types: List<ProductTypeConfig> = listOf(
        ProductTypeConfig(TYPE_TSHIRT, R.string.pa_type_tshirt, SizeSystem.CLOTHING, true, false, clothing, setOf(VariantAxis.COLOR, VariantAxis.SIZE)),
        ProductTypeConfig(TYPE_HOODIE, R.string.pa_type_hoodie, SizeSystem.CLOTHING, true, false, clothing, setOf(VariantAxis.COLOR, VariantAxis.SIZE)),
        ProductTypeConfig(TYPE_PANTS, R.string.pa_type_pants, SizeSystem.CLOTHING, true, false, clothing, setOf(VariantAxis.COLOR, VariantAxis.SIZE)),
        ProductTypeConfig(TYPE_DRESS, R.string.pa_type_dress, SizeSystem.CLOTHING, true, false, clothing, setOf(VariantAxis.COLOR, VariantAxis.SIZE)),
        ProductTypeConfig(TYPE_SHOES, R.string.pa_type_shoes, SizeSystem.SHOES, true, false, shoes, setOf(VariantAxis.COLOR, VariantAxis.SIZE)),
        ProductTypeConfig(TYPE_BAG, R.string.pa_type_bag, SizeSystem.NONE, true, true, bag, setOf(VariantAxis.COLOR)),
        ProductTypeConfig(TYPE_ACCESSORY, R.string.pa_type_accessory, SizeSystem.NONE, true, false, bag, setOf(VariantAxis.COLOR)),
        ProductTypeConfig(TYPE_FURNITURE, R.string.pa_type_furniture, SizeSystem.NONE, true, true, furniture, setOf(VariantAxis.COLOR)),
        ProductTypeConfig(TYPE_DECORATION, R.string.pa_type_decoration, SizeSystem.NONE, true, true, decoration, setOf(VariantAxis.COLOR)),
        ProductTypeConfig(TYPE_ELECTRONICS, R.string.pa_type_electronics, SizeSystem.NONE, true, false, electronics, setOf(VariantAxis.COLOR)),
        ProductTypeConfig(TYPE_HANDMADE, R.string.pa_type_handmade, SizeSystem.NONE, true, true, handmade, setOf(VariantAxis.COLOR)),
        ProductTypeConfig(TYPE_FOOD, R.string.pa_type_food, SizeSystem.NONE, false, false, food, emptySet()),
        ProductTypeConfig(TYPE_OTHER, R.string.pa_type_other, SizeSystem.NONE, true, false, other, setOf(VariantAxis.COLOR, VariantAxis.SIZE))
    )

    val options: List<ProductTypeOption> = types.map { ProductTypeOption(it.key, it.labelRes) }

    fun byKey(key: String?): ProductTypeConfig? {
        if (key.isNullOrBlank()) return null
        return types.firstOrNull { it.key.equals(key.trim(), ignoreCase = true) }
    }

    fun predefinedSizesFor(sizeSystem: SizeSystem): List<String> = when (sizeSystem) {
        SizeSystem.CLOTHING -> CLOTHING_SIZES
        SizeSystem.SHOES -> SHOE_SIZES
        SizeSystem.NONE -> emptyList()
    }
}
