package isim.ia2y.myapplication

import android.content.Context

// Cette classe organise cette partie de l'app.
object AddressBookStore {
    private const val PREFS_NAME = "address_book_store"
    private const val KEY_ADDRESSES = "addresses_csv"
    private const val SEP = "|||"

    // Cette fonction fait une action de cette partie de l'app.
    private fun prefs(context: Context): android.content.SharedPreferences {
        val uid = FirebaseAuthManager.currentUser?.uid ?: "guest"
        return context.getSharedPreferences("${uid}_$PREFS_NAME", Context.MODE_PRIVATE)
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun getAddresses(context: Context): MutableList<String> {
        val raw = prefs(context).getString(KEY_ADDRESSES, "").orEmpty()
        val defaultList = mutableListOf("Tunis, Tunisie")
        if (raw.isBlank()) return defaultList
        
        return try {
            val array = org.json.JSONArray(raw)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                val item = array.getString(i).trim()
                if (item.isNotBlank()) list.add(item)
            }
            if (list.isEmpty()) defaultList else list
        } catch (e: Exception) {
            raw.split(SEP)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toMutableList()
                .ifEmpty { defaultList }
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun saveAddresses(context: Context, addresses: List<String>) {
        val clean = addresses.map { it.trim() }.filter { it.isNotBlank() }
        val array = org.json.JSONArray()
        clean.forEach { array.put(it) }
        prefs(context).edit().putString(KEY_ADDRESSES, array.toString()).apply()
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun addAddress(context: Context, address: String) {
        val trimmed = address.trim()
        if (trimmed.isBlank()) return
        val list = getAddresses(context)
        list.removeAll { it.equals(trimmed, ignoreCase = true) }
        list.add(0, trimmed)
        saveAddresses(context, list)
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun setCurrent(context: Context, address: String) {
        val list = getAddresses(context)
        val target = list.firstOrNull { it.equals(address, ignoreCase = true) } ?: return
        list.removeAll { it.equals(target, ignoreCase = true) }
        list.add(0, target)
        saveAddresses(context, list)
    }
}
