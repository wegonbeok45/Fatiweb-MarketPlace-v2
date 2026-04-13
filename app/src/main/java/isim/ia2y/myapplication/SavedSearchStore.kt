package isim.ia2y.myapplication

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedSearchPreset(
    val id: String,
    val title: String,
    val query: String,
    val category: String,
    val location: String,
    val minPrice: Float,
    val maxPrice: Float,
    val bioNaturel: Boolean,
    val sortOrdinal: Int,
    val createdAt: Long
)

object SavedSearchStore {
    private const val PREFS = "saved_searches"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 8

    fun load(context: Context): List<SavedSearchPreset> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, "[]")
            .orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        SavedSearchPreset(
                            id = item.optString("id"),
                            title = item.optString("title"),
                            query = item.optString("query"),
                            category = item.optString("category", "all"),
                            location = item.optString("location", "all"),
                            minPrice = item.optDouble("minPrice", 0.0).toFloat(),
                            maxPrice = item.optDouble("maxPrice", 400.0).toFloat(),
                            bioNaturel = item.optBoolean("bioNaturel", false),
                            sortOrdinal = item.optInt("sortOrdinal", 2),
                            createdAt = item.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, preset: SavedSearchPreset) {
        val current = load(context).toMutableList()
        current.removeAll { it.title.equals(preset.title, ignoreCase = true) || it.id == preset.id }
        current.add(0, preset)
        persist(context, current.take(MAX_ITEMS))
    }

    fun remove(context: Context, presetId: String) {
        persist(context, load(context).filterNot { it.id == presetId })
    }

    fun exists(context: Context, title: String): Boolean {
        return load(context).any { it.title.equals(title, ignoreCase = true) }
    }

    private fun persist(context: Context, items: List<SavedSearchPreset>) {
        val array = JSONArray()
        items.forEach { preset ->
            array.put(
                JSONObject().apply {
                    put("id", preset.id)
                    put("title", preset.title)
                    put("query", preset.query)
                    put("category", preset.category)
                    put("location", preset.location)
                    put("minPrice", preset.minPrice.toDouble())
                    put("maxPrice", preset.maxPrice.toDouble())
                    put("bioNaturel", preset.bioNaturel)
                    put("sortOrdinal", preset.sortOrdinal)
                    put("createdAt", preset.createdAt)
                }
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }
}
