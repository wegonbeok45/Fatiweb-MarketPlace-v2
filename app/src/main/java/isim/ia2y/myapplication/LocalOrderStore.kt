package isim.ia2y.myapplication

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

object LocalOrderStore {
    private const val TAG = "LocalOrderStore"
    private const val PREFS_NAME = "local_orders_store"
    private const val KEY_ORDERS = "orders_json"
    private const val GUEST_KEY = "guest"

    @Volatile
    private var cachedAccountKey: String? = null

    @Volatile
    private var cachedRawOrders: String? = null

    @Volatile
    private var cachedOrders: List<AppOrder> = emptyList()

    private fun prefs(context: Context): SharedPreferences {
        return encryptedPrefsForAccount(context.applicationContext, currentAccountKey())
    }

    private fun currentAccountKey(): String = FirebaseAuthManager.currentUser?.uid ?: GUEST_KEY

    private fun encryptedPrefsForAccount(context: Context, accountKey: String): SharedPreferences {
        val encryptedPrefsName = "${accountKey}_${PREFS_NAME}_encrypted"
        val encryptedPrefs = runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                encryptedPrefsName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse { error ->
            Log.e(TAG, "Encrypted order storage unavailable; using legacy preferences.", error)
            return plainPrefsForAccount(context, accountKey)
        }
        migrateLegacyOrders(context, accountKey, encryptedPrefs)
        return encryptedPrefs
    }

    private fun plainPrefsForAccount(context: Context, accountKey: String): SharedPreferences {
        return context.getSharedPreferences("${accountKey}_$PREFS_NAME", Context.MODE_PRIVATE)
    }

    private fun migrateLegacyOrders(
        context: Context,
        accountKey: String,
        encryptedPrefs: SharedPreferences
    ) {
        val legacyPrefs = plainPrefsForAccount(context, accountKey)
        val legacyOrders = legacyPrefs.getString(KEY_ORDERS, null).orEmpty()
        if (legacyOrders.isBlank()) return

        if (!encryptedPrefs.contains(KEY_ORDERS)) {
            encryptedPrefs.edit().putString(KEY_ORDERS, legacyOrders).apply()
        }
        legacyPrefs.edit().remove(KEY_ORDERS).apply()
    }

    fun getAll(context: Context): List<AppOrder> {
        val accountKey = currentAccountKey()
        val raw = prefs(context).getString(KEY_ORDERS, "").orEmpty()
        if (cachedAccountKey == accountKey && cachedRawOrders == raw) {
            return cachedOrders
        }
        if (raw.isBlank()) {
            updateCache(accountKey, raw, emptyList())
            return emptyList()
        }
        val parsed = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(AppOrder.fromMap(item.toMap().filterValues { it != null } as Map<String, Any>))
                }
            }.sortedByDescending { it.createdAtMillis }
        }.getOrDefault(emptyList())
        updateCache(accountKey, raw, parsed)
        return parsed
    }

    fun findById(context: Context, orderId: String): AppOrder? {
        return getAll(context).firstOrNull { it.id == orderId }
    }

    fun upsert(context: Context, order: AppOrder) {
        val next = getAll(context).toMutableList()
        val index = next.indexOfFirst { it.id == order.id }
        if (index >= 0) {
            next[index] = order
        } else {
            next.add(0, order)
        }
        saveAll(context, next.sortedByDescending { it.createdAtMillis })
    }

    fun replaceTemp(context: Context, tempId: String, savedOrder: AppOrder) {
        val next = getAll(context)
            .filterNot { it.id == tempId }
            .toMutableList()
        next.add(0, savedOrder)
        saveAll(context, next.sortedByDescending { it.createdAtMillis })
    }

    fun remove(context: Context, orderId: String) {
        val next = getAll(context).filterNot { it.id == orderId }
        saveAll(context, next)
    }

    fun mergeRemote(context: Context, orders: List<AppOrder>) {
        val merged = linkedMapOf<String, AppOrder>()
        orders.sortedByDescending { it.createdAtMillis }.forEach { merged[it.id] = it }
        getAll(context).forEach { order ->
            if (!merged.containsKey(order.id)) {
                merged[order.id] = order
            }
        }
        saveAll(context, merged.values.sortedByDescending { it.createdAtMillis })
    }

    private fun saveAll(context: Context, orders: List<AppOrder>) {
        val accountKey = currentAccountKey()
        val array = JSONArray()
        orders.forEach { order ->
            array.put(JSONObject(order.toMap()))
        }
        val raw = array.toString()
        prefs(context).edit().putString(KEY_ORDERS, raw).apply()
        updateCache(accountKey, raw, orders)
    }

    private fun updateCache(accountKey: String, raw: String, orders: List<AppOrder>) {
        cachedAccountKey = accountKey
        cachedRawOrders = raw
        cachedOrders = orders
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        keys().forEach { key ->
            val value = opt(key)
            result[key] = when (value) {
                is JSONObject -> value.toMap()
                is JSONArray -> {
                    buildList {
                        for (i in 0 until value.length()) {
                            add(
                                when (val item = value.opt(i)) {
                                    is JSONObject -> item.toMap()
                                    is JSONArray -> item.toList()
                                    JSONObject.NULL -> null
                                    else -> item
                                }
                            )
                        }
                    }
                }
                JSONObject.NULL -> null
                else -> value
            }
        }
        return result
    }

    private fun JSONArray.toList(): List<Any?> {
        return buildList {
            for (i in 0 until length()) {
                add(
                    when (val item = opt(i)) {
                        is JSONObject -> item.toMap()
                        is JSONArray -> item.toList()
                        JSONObject.NULL -> null
                        else -> item
                    }
                )
            }
        }
    }
}
