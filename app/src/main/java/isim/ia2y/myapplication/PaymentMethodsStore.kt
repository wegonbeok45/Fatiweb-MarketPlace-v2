package isim.ia2y.myapplication

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class PaymentMethod(
    val id: String = UUID.randomUUID().toString(),
    val brand: String = "",
    val last4: String = "",
    val expiryMonth: Int = 0,
    val expiryYear: Int = 0,
    val isDefault: Boolean = false
) {
    val maskedLabel: String
        get() = "\u2022\u2022\u2022\u2022 $last4"

    val expiryLabel: String
        get() = if (expiryMonth in 1..12 && expiryYear > 0) {
            "Expires ${expiryMonth.toString().padStart(2, '0')}/${expiryYear.toString().takeLast(2)}"
        } else {
            ""
        }
}

object PaymentMethodsStore {
    private const val PREFS_NAME = "payment_methods_store"
    private const val KEY_METHODS = "methods"
    private const val GUEST_KEY = "guest"

    private fun prefs(context: Context) =
        context.getSharedPreferences("${accountKey()}_$PREFS_NAME", Context.MODE_PRIVATE)

    private fun accountKey(): String = FirebaseAuthManager.currentUser?.uid ?: GUEST_KEY

    fun getAll(context: Context): MutableList<PaymentMethod> {
        val raw = prefs(context).getString(KEY_METHODS, null).orEmpty()
        if (raw.isBlank()) return mutableListOf()
        return runCatching {
            val items = JSONArray(raw)
            buildList {
                for (index in 0 until items.length()) {
                    val json = items.optJSONObject(index) ?: continue
                    add(
                        PaymentMethod(
                            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
                            brand = json.optString("brand"),
                            last4 = json.optString("last4"),
                            expiryMonth = json.optInt("expiryMonth"),
                            expiryYear = json.optInt("expiryYear"),
                            isDefault = json.optBoolean("isDefault")
                        )
                    )
                }
            }.normalize()
        }.getOrDefault(mutableListOf())
    }

    fun saveAll(context: Context, methods: List<PaymentMethod>) {
        val payload = JSONArray()
        methods.normalize().forEach { method ->
            payload.put(
                JSONObject().apply {
                    put("id", method.id)
                    put("brand", method.brand)
                    put("last4", method.last4)
                    put("expiryMonth", method.expiryMonth)
                    put("expiryYear", method.expiryYear)
                    put("isDefault", method.isDefault)
                }
            )
        }
        prefs(context).edit().putString(KEY_METHODS, payload.toString()).apply()
    }

    fun upsert(context: Context, method: PaymentMethod) {
        val current = getAll(context)
        val index = current.indexOfFirst { it.id == method.id }
        if (index >= 0) {
            current[index] = method
        } else {
            current.add(method)
        }
        saveAll(context, current)
    }

    fun remove(context: Context, methodId: String) {
        saveAll(context, getAll(context).filterNot { it.id == methodId })
    }

    fun setDefault(context: Context, methodId: String) {
        saveAll(
            context,
            getAll(context).map { method -> method.copy(isDefault = method.id == methodId) }
        )
    }

    private fun List<PaymentMethod>.normalize(): MutableList<PaymentMethod> {
        val cleaned = this
            .mapNotNull { method ->
                val last4 = method.last4.filter(Char::isDigit).takeLast(4)
                if (method.brand.isBlank() || last4.length != 4) {
                    null
                } else {
                    method.copy(
                        brand = method.brand.trim(),
                        last4 = last4,
                        expiryMonth = method.expiryMonth.coerceIn(1, 12),
                        expiryYear = method.expiryYear.coerceAtLeast(0)
                    )
                }
            }
            .toMutableList()

        if (cleaned.isEmpty()) return cleaned
        val defaultIndex = cleaned.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
        return cleaned.mapIndexed { index, method ->
            method.copy(isDefault = index == defaultIndex)
        }.toMutableList()
    }
}
