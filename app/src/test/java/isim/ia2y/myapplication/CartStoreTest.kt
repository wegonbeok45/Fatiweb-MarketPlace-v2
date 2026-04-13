package isim.ia2y.myapplication

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CartStoreTest {

    private lateinit var fakeContext: Context
    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        fakeContext = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return fakePrefs
            }
        }
        ProductCatalog.replaceAll(
            listOf(
                Product(
                    id = "chechia",
                    title = "Chechia",
                    subtitle = "Laine rouge",
                    price = 45.0,
                    rating = 4.8,
                    reviewsCount = 18,
                    tags = listOf("artisanat"),
                    description = "Chechia traditionnelle en laine rouge.",
                    bullets = emptyList(),
                    imageRes = 0,
                    stock = 10
                ),
                Product(
                    id = "bijoux",
                    title = "Bijoux",
                    subtitle = "Argent de Djerba",
                    price = 120.0,
                    rating = 4.9,
                    reviewsCount = 12,
                    tags = listOf("bijoux"),
                    description = "Bijoux artisanaux en argent.",
                    bullets = emptyList(),
                    imageRes = 0,
                    stock = 10
                ),
                Product(
                    id = "balgha",
                    title = "Balgha",
                    subtitle = "Cuir medina",
                    price = 65.0,
                    rating = 4.7,
                    reviewsCount = 8,
                    tags = listOf("mode"),
                    description = "Balgha traditionnelle en cuir.",
                    bullets = emptyList(),
                    imageRes = 0,
                    stock = 10
                ),
                Product(
                    id = "harissa_artisanale",
                    title = "Harissa artisanale",
                    subtitle = "Saveur maison",
                    price = 14.0,
                    rating = 4.6,
                    reviewsCount = 5,
                    tags = listOf("food"),
                    description = "Harissa artisanale preparee en petites quantites.",
                    bullets = emptyList(),
                    imageRes = 0,
                    stock = 10
                )
            )
        )
    }

    @Test
    fun testAddOne_initialEmpty() {
        CartStore.clear(fakeContext)
        CartStore.addOne(fakeContext, "chechia")
        val cart = CartStore.getCart(fakeContext)
        assertEquals(1, cart["chechia"])
        assertEquals(1, CartStore.itemCount(fakeContext))
    }

    @Test
    fun testIncrement_existing() {
        CartStore.clear(fakeContext)
        CartStore.addOne(fakeContext, "bijoux")
        CartStore.increment(fakeContext, "bijoux")
        assertEquals(2, CartStore.getCart(fakeContext)["bijoux"])
    }

    @Test
    fun testDecrement_toZeroRemovesItem() {
        CartStore.clear(fakeContext)
        CartStore.addOne(fakeContext, "balgha")
        CartStore.decrement(fakeContext, "balgha")
        assertFalse(CartStore.getCart(fakeContext).containsKey("balgha"))
    }

    @Test
    fun testRemove_clearsItem() {
        CartStore.clear(fakeContext)
        CartStore.addOne(fakeContext, "harissa_artisanale")
        CartStore.addOne(fakeContext, "harissa_artisanale")
        CartStore.remove(fakeContext, "harissa_artisanale")
        assertEquals(0, CartStore.itemCount(fakeContext))
    }

    @Test
    fun testTotal_appliesLivraisonFeeOnlyIfCartNotEmpty() {
        CartStore.clear(fakeContext)
        assertEquals(0.0, CartStore.total(fakeContext), 0.01)

        CartStore.addOne(fakeContext, "chechia")
        val sub = CartStore.subtotal(fakeContext)
        assertTrue(sub > 0.0)
        val expectedTotal = sub + CartStore.LIVRAISON_FEE
        assertEquals(expectedTotal, CartStore.total(fakeContext), 0.01)
    }
}

class FakeSharedPreferences : SharedPreferences {
    val data = mutableMapOf<String, Any>()

    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        return (data[key] as? Set<String>) ?: defValues
    }

    override fun edit(): SharedPreferences.Editor = FakeEditor(this)

    override fun getAll(): Map<String, *> = data
    override fun getString(key: String, defValue: String?): String? = null
    override fun getInt(key: String, defValue: Int): Int = 0
    override fun getLong(key: String, defValue: Long): Long = 0L
    override fun getFloat(key: String, defValue: Float): Float = 0f
    override fun getBoolean(key: String, defValue: Boolean): Boolean = false
    override fun contains(key: String): Boolean = data.containsKey(key)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any>()
        private var clear = false

        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            if (values != null) temp[key] = values else temp.remove(key)
            return this
        }

        override fun remove(key: String): SharedPreferences.Editor {
            temp[key] = "REMOVE_ME"
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clear) prefs.data.clear()
            temp.forEach { (key, value) ->
                if (value == "REMOVE_ME") prefs.data.remove(key) else prefs.data[key] = value
            }
            temp.clear()
            clear = false
        }

        override fun putString(key: String?, value: String?) = this
        override fun putInt(key: String?, value: Int) = this
        override fun putLong(key: String?, value: Long) = this
        override fun putFloat(key: String?, value: Float) = this
        override fun putBoolean(key: String?, value: Boolean) = this
    }
}
