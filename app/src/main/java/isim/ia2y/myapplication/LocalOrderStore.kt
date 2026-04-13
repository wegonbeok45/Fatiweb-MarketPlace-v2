package isim.ia2y.myapplication

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object LocalOrderStore {
    private const val PREFS_NAME = "local_orders_store"
    private const val KEY_ORDERS = "orders_json"
    private const val GUEST_KEY = "guest"

    private fun prefs(context: Context) =
        context.getSharedPreferences("${currentAccountKey()}_$PREFS_NAME", Context.MODE_PRIVATE)

    private fun currentAccountKey(): String = FirebaseAuthManager.currentUser?.uid ?: GUEST_KEY

    fun getAll(context: Context): List<AppOrder> {
        val raw = prefs(context).getString(KEY_ORDERS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(AppOrder.fromMap(item.toMap().filterValues { it != null } as Map<String, Any>))
                }
            }.sortedByDescending { it.createdAtMillis }
        }.getOrDefault(emptyList())
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
        val array = JSONArray()
        orders.forEach { order ->
            array.put(JSONObject(order.toMap()))
        }
        prefs(context).edit().putString(KEY_ORDERS, array.toString()).apply()
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
