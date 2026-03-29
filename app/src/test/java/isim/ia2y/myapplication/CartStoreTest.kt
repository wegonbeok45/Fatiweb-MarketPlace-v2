package isim.ia2y.myapplication

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

// Cette classe organise cette partie de l'app.
class CartStoreTest {

    private lateinit var fakeContext: Context
    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    // Cette fonction fait une action de cette partie de l'app.
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        fakeContext = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
                return fakePrefs
            }
        }
        ProductCatalog.replaceAll(ProductCatalog.seededAll())
    }

    @Test
    // Cette fonction fait une action de cette partie de l'app.
    fun testAddOne_initialEmpty() {
        CartStore.clear(fakeContext)
        CartStore.addOne(fakeContext, "chechia")
        val cart = CartStore.getCart(fakeContext)
        assertEquals(1, cart["chechia"])
        assertEquals(1, CartStore.itemCount(fakeContext))
    }

    @Test
    // Cette fonction fait une action de cette partie de l'app.
    fun testIncrement_existing() {
        CartStore.clear(fakeContext)
        CartStore.addOne(fakeContext, "bijoux")
        val newQty = CartStore.increment(fakeContext, "bijoux")
        assertEquals(2, newQty)
        assertEquals(2, CartStore.getCart(fakeContext)["bijoux"])
    }

    @Test
    // Cette fonction fait une action de cette partie de l'app.
    fun testDecrement_toZeroRemovesItem() {
        CartStore.clear(fakeContext)
        CartStore.addOne(fakeContext, "balgha")
        val newQty = CartStore.decrement(fakeContext, "balgha")
        assertEquals(0, newQty)
        assertFalse(CartStore.getCart(fakeContext).containsKey("balgha"))
    }
    
    @Test
    // Cette fonction fait une action de cette partie de l'app.
    fun testRemove_clearsItem() {
        CartStore.clear(fakeContext)
        CartStore.addOne(fakeContext, "harissa_artisanale")
        CartStore.addOne(fakeContext, "harissa_artisanale")
        CartStore.remove(fakeContext, "harissa_artisanale")
        assertEquals(0, CartStore.itemCount(fakeContext))
    }
    
    @Test
    // Cette fonction fait une action de cette partie de l'app.
    fun testTotal_appliesLivraisonFeeOnlyIfCartNotEmpty() {
        CartStore.clear(fakeContext)
        // subtotal 0 -> total 0
        assertEquals(0.0, CartStore.total(fakeContext), 0.01)
        
        CartStore.addOne(fakeContext, "chechia")
        val sub = CartStore.subtotal(fakeContext)
        assertTrue(sub > 0.0)
        val expectedTotal = sub + CartStore.LIVRAISON_FEE
        assertEquals(expectedTotal, CartStore.total(fakeContext), 0.01)
    }
}

// Cette classe organise cette partie de l'app.
class FakeSharedPreferences : SharedPreferences {
    val data = mutableMapOf<String, Any>()

    // Cette fonction fait une action de cette partie de l'app.
    override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? {
        return (data[key] as? Set<String>) ?: defValues
    }

    // Cette fonction fait une action de cette partie de l'app.
    override fun edit(): SharedPreferences.Editor = FakeEditor(this)
    
    // Cette fonction fait une action de cette partie de l'app.
    override fun getAll(): Map<String, *> = data
    // Cette fonction fait une action de cette partie de l'app.
    override fun getString(key: String, defValue: String?): String? = null
    // Cette fonction fait une action de cette partie de l'app.
    override fun getInt(key: String, defValue: Int): Int = 0
    // Cette fonction fait une action de cette partie de l'app.
    override fun getLong(key: String, defValue: Long): Long = 0L
    // Cette fonction fait une action de cette partie de l'app.
    override fun getFloat(key: String, defValue: Float): Float = 0f
    // Cette fonction fait une action de cette partie de l'app.
    override fun getBoolean(key: String, defValue: Boolean): Boolean = false
    // Cette fonction fait une action de cette partie de l'app.
    override fun contains(key: String): Boolean = data.containsKey(key)
    // Cette fonction fait une action de cette partie de l'app.
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    // Cette fonction fait une action de cette partie de l'app.
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    // Cette classe organise cette partie de l'app.
    class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any>()
        private var clear = false

        @Suppress("UNCHECKED_CAST")
        // Cette fonction fait une action de cette partie de l'app.
        override fun putStringSet(key: String, values: Set<String>?): SharedPreferences.Editor {
            if (values != null) temp[key] = values else temp.remove(key)
            return this
        }
        
        // Cette fonction fait une action de cette partie de l'app.
        override fun remove(key: String): SharedPreferences.Editor {
            temp[key] = "REMOVE_ME"
            return this
        }

        // Cette fonction fait une action de cette partie de l'app.
        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        // Cette fonction fait une action de cette partie de l'app.
        override fun commit(): Boolean {
            apply()
            return true
        }

        // Cette fonction fait une action de cette partie de l'app.
        override fun apply() {
            if (clear) prefs.data.clear()
            temp.forEach { (k, v) ->
                if (v == "REMOVE_ME") prefs.data.remove(k)
                else prefs.data[k] = v
            }
            temp.clear()
            clear = false
        }
        
        // Cette fonction fait une action de cette partie de l'app.
        override fun putString(key: String?, value: String?) = this
        // Cette fonction fait une action de cette partie de l'app.
        override fun putInt(key: String?, value: Int) = this
        // Cette fonction fait une action de cette partie de l'app.
        override fun putLong(key: String?, value: Long) = this
        // Cette fonction fait une action de cette partie de l'app.
        override fun putFloat(key: String?, value: Float) = this
        // Cette fonction fait une action de cette partie de l'app.
        override fun putBoolean(key: String?, value: Boolean) = this
    }
}
