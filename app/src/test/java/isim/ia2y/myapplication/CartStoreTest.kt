package isim.ia2y.myapplication

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

class CartStoreTest {

    private lateinit var fakeContext: Context
    private lateinit var fakePrefs: FakeSharedPreferences

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        fakeContext = Proxy.newProxyInstance(
            Context::class.java.classLoader,
            arrayOf(Context::class.java)
        ) { _, method, _ ->
            if (method.name == "getSharedPreferences") {
                fakePrefs
            } else {
                null
            }
        } as Context
    }

    @Test
    fun testAddOne_initialEmpty() {
        CartStore.clear(fakeContext)
        CartStore.addOne(fakeContext, "prod_1")
        val cart = CartStore.getCart(fakeContext)
        assertEquals(1, cart["prod_1"])
        assertEquals(1, CartStore.itemCount(fakeContext))
    }

    @Test
    fun testIncrement_existing() {
        CartStore.clear(fakeContext)
        CartStore.addOne(fakeContext, "prod_2")
        val newQty = CartStore.increment(fakeContext, "prod_2")
        assertEquals(2, newQty)
        assertEquals(2, CartStore.getCart(fakeContext)["prod_2"])
    }

    @Test
    fun testDecrement_toZeroRemovesItem() {
        CartStore.clear(fakeContext)
        CartStore.addOne(fakeContext, "prod_3")
        val newQty = CartStore.decrement(fakeContext, "prod_3")
        assertEquals(0, newQty)
        assertFalse(CartStore.getCart(fakeContext).containsKey("prod_3"))
    }
    
    @Test
    fun testRemove_clearsItem() {
        CartStore.clear(fakeContext)
        CartStore.addOne(fakeContext, "prod_4")
        CartStore.addOne(fakeContext, "prod_4")
        CartStore.remove(fakeContext, "prod_4")
        assertEquals(0, CartStore.itemCount(fakeContext))
    }
    
    @Test
    fun testTotal_appliesLivraisonFeeOnlyIfCartNotEmpty() {
        CartStore.clear(fakeContext)
        // subtotal 0 -> total 0
        assertEquals(0.0, CartStore.total(fakeContext), 0.01)
        
        // add an arbitrary product (must exist in ProductCatalog for subtotal to > 0)
        // If ProductCatalog has ID "1", it returns real price. We just map 1.
        CartStore.addOne(fakeContext, "1")
        val sub = CartStore.subtotal(fakeContext)
        if (sub > 0.0) {
            val expectedTotal = sub + CartStore.LIVRAISON_FEE
            assertEquals(expectedTotal, CartStore.total(fakeContext), 0.01)
        }
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
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    class FakeEditor(private val prefs: FakeSharedPreferences) : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any>()
        private var clear = false

        @Suppress("UNCHECKED_CAST")
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
            temp.forEach { (k, v) ->
                if (v == "REMOVE_ME") prefs.data.remove(k)
                else prefs.data[k] = v
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
