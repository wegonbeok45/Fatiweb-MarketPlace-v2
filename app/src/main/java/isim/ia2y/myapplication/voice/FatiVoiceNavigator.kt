package isim.ia2y.myapplication.voice

import android.app.Activity
import android.content.ContentValues.TAG
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.EditText
import isim.ia2y.myapplication.CheckoutDetailsActivity
import isim.ia2y.myapplication.MainActivity
import isim.ia2y.myapplication.Product
import isim.ia2y.myapplication.ProductCatalog
import isim.ia2y.myapplication.ProductDetailsScreen
import isim.ia2y.myapplication.ProductService
import isim.ia2y.myapplication.R
import isim.ia2y.myapplication.SearchActivity
import kotlinx.coroutines.runBlocking
import java.text.Normalizer
import java.util.Locale

class FatiVoiceNavigator(private val activity: Activity) {

    private val frenchStopWords = setOf(
        "je", "veux", "chercher", "rechercher", "cherche", "trouver", "voir", "un", "une",
        "le", "la", "les", "de", "des", "du", "pour", "mon", "ma", "mes", "ce", "cet", "cette",
        "et", "ou", "dans", "sur", "avec", "sans", "est", "pas", "merci", "bonjour", "salut"
    )

    fun goToHome() {
        if (activity is MainActivity) {
            activity.selectTabWithRetries(MainActivity.Tab.HOME)
        }
    }

    fun goToCart() {
        if (activity is MainActivity) {
            activity.selectTabWithRetries(MainActivity.Tab.CART)
        }
    }

    fun goToOrders() {
        // Try to open OrdersHistoryActivity if available
        try {
            val intent = Intent(activity, Class.forName("isim.ia2y.myapplication.OrdersHistoryActivity")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            activity.startActivity(intent)
        } catch (e: ClassNotFoundException) {
            // Fallback if OrdersHistoryActivity doesn't exist
            activity.startActivity(Intent(activity, MainActivity::class.java).apply {
                putExtra("open_main_tab", "PROFILE")
            })
        }
    }

    fun openProductDetails(productId: String) {
        activity.startActivity(ProductDetailsScreen.createIntent(activity, productId))
    }

    fun searchProduct(query: String) {
        val products = loadCatalogSnapshot()
        val normalizedQuery = normalizeText(query)
        val product = products.asSequence()
            .filter { it.isActive }
            .map { product -> product to matchScore(product, normalizedQuery) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

        if (product != null) {
            activity.startActivity(ProductDetailsScreen.createIntent(activity, product.id))
            return
        }

        val intent = SearchActivity.createIntent(
            context = activity,
            initialQuery = query,
            initialCategory = "all"
        )
        activity.startActivity(intent)
    }

    private fun loadCatalogSnapshot(): List<Product> {
        val cachedProducts = ProductCatalog.all(includeInactive = false)
        if (cachedProducts.isNotEmpty()) return cachedProducts

        val refreshedProducts = runCatching {
            runBlocking { ProductService.fetchProducts(limit = 60) }
        }.getOrNull().orEmpty()

        if (refreshedProducts.isNotEmpty()) {
            ProductCatalog.replaceAll(refreshedProducts)
        }

        return ProductCatalog.all(includeInactive = false)
    }

    private fun matchScore(product: Product, normalizedQuery: String): Int {
        val queryTokens = meaningfulTokens(normalizedQuery)
        if (queryTokens.isEmpty()) return 0

        val candidates = buildList {
            add(product.id)
            add(product.title)
            add(product.subtitle)
            add(product.category)
            add(product.origin)
            add(product.sellerName)
            addAll(product.searchKeywords)
            addAll(product.tags)
            add(product.description)
        }

        var bestScore = 0
        for (candidate in candidates) {
            val normalizedCandidate = normalizeText(candidate)
            if (normalizedCandidate.isBlank()) continue

            val candidateTokens = meaningfulTokens(normalizedCandidate)
            val tokenMatches = queryTokens.count { token -> candidateTokens.contains(token) }
            val containsAllTokens = queryTokens.all { normalizedCandidate.contains(it) }

            when {
                normalizedCandidate == normalizedQuery -> return 100
                containsAllTokens -> {
                    bestScore = maxOf(bestScore, 70 + (queryTokens.size * 5))
                }
                tokenMatches > 0 -> {
                    val score = (tokenMatches * 40) + if (candidateTokens.contains(queryTokens.first())) 30 else 0
                    bestScore = maxOf(bestScore, score.coerceAtMost(100))
                }
            }
        }

        return bestScore
    }

    private fun meaningfulTokens(value: String): List<String> {
        return normalizeText(value)
            .split("\\s+".toRegex())
            .filter { token -> token.length >= 2 && token !in frenchStopWords }
            .distinct()
    }

    private fun normalizeText(value: String): String {
        val normalized = Normalizer.normalize(value.lowercase(Locale.getDefault()), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .replace("[^a-z0-9]+".toRegex(), " ")
            .trim()
        return normalized.replace("\\s+".toRegex(), " ")
    }

    private fun MainActivity.selectTabWithRetries(tab: MainActivity.Tab) {
        runOnUiThread {
            selectTab(tab)
            window.decorView.postDelayed({ selectTab(tab) }, 800L)
            window.decorView.postDelayed({ selectTab(tab) }, 2_000L)
        }
    }

    fun openCheckout() {
        val intent = Intent(activity, CheckoutDetailsActivity::class.java)
        activity.startActivity(intent)
    }

    fun clickButton(viewId: Int) {
        activity.runOnUiThread {
            activity.findViewById<View>(viewId)?.performClick()
        }
    }

    fun fillTextField(viewId: Int, value: String) {
        activity.runOnUiThread {
            val edit = activity.findViewById<EditText>(viewId)
            if (edit != null) {
                edit.setText(value)
                return@runOnUiThread
            }
            val tv = activity.findViewById<View>(viewId)
            try {
                if (tv is android.widget.TextView) {
                    tv.text = value
                }
            } catch (_: Exception) { }
        }
    }

    fun selectCOD() {
        // Common ID used in checkout
        clickButton(R.id.cardPayCash)
    }

    fun selectHomeDelivery() {
        // Common ID used in checkout for standard delivery
        clickButton(R.id.cardDeliveryStandard)
    }

    // the 3 new methodes
    // Add these new methods to FatiVoiceNavigator class

    fun navigateToAddresses() {
        // AFTER (CORRECT):
    // Option 1: If you have an AddressesActivity, import it properly
    // Option 2: Use CheckoutDetailsActivity instead
        val intent = Intent(activity, CheckoutDetailsActivity::class.java)
        activity.startActivity(intent)
    }

    fun fillAddressField(viewId: Int, value: String) {
        activity.runOnUiThread {
            val editText = activity.findViewById<EditText>(viewId)
            if (editText != null) {
                editText.setText(value)
                editText.clearFocus()
                return@runOnUiThread
            }
        }
    }

    fun fillAddressFormFields(
        label: String,
        recipient: String,
        phone: String,
        governorate: String,
        city: String,
        line1: String,
        line2: String,
        postalCode: String,
        notes: String
    ) {
        activity.runOnUiThread {
            // Try to fill each field - find by view IDs from dialog_edit_address.xml
            fillAddressField(R.id.etAddressLabel, label)
            fillAddressField(R.id.etAddressRecipient, recipient)
            fillAddressField(R.id.etAddressPhone, phone)
            fillAddressField(R.id.etAddressGovernorate, governorate)
            fillAddressField(R.id.etAddressCity, city)
            fillAddressField(R.id.etAddressLine1, line1)
            if (line2.isNotBlank()) {
                fillAddressField(R.id.etAddressLine2, line2)
            }
            if (postalCode.isNotBlank()) {
                fillAddressField(R.id.etAddressPostalCode, postalCode)
            }
            if (notes.isNotBlank()) {
                fillAddressField(R.id.etAddressNotes, notes)
            }
        }
    }

    fun clickAddressSaveButton() {
        activity.runOnUiThread {
            val saveButton = activity.findViewById<View>(R.id.btnAddressSheetSave)
            saveButton?.performClick()
        }
    }


    fun returnToCheckout() {
        activity.runOnUiThread {
            // ✅ Après
            try {
                val intent = Intent(activity, Class.forName("isim.ia2y.myapplication.AddressesActivity")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                activity.startActivity(intent)
            } catch (e: ClassNotFoundException) {
                Log.w(TAG, "AddressesActivity not found", e)
            }
        }
    }
}
