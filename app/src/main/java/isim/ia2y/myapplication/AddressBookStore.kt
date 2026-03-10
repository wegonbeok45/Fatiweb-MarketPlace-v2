package isim.ia2y.myapplication

import android.content.Context

object AddressBookStore {
    private const val PREFS_NAME = "address_book_store"
    private const val KEY_ADDRESSES = "addresses_csv"
    private const val SEP = "|||"

    private fun prefs(context: Context): android.content.SharedPreferences {
        val uid = FirebaseAuthManager.currentUser?.uid ?: "guest"
        return context.getSharedPreferences("${uid}_$PREFS_NAME", Context.MODE_PRIVATE)
    }

    fun getAddresses(context: Context): MutableList<String> {
        val raw = prefs(context).getString(KEY_ADDRESSES, "").orEmpty()
        if (raw.isBlank()) {
            return mutableListOf("Tunis, Tunisie")
        }
        return raw.split(SEP)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toMutableList()
            .ifEmpty { mutableListOf("Tunis, Tunisie") }
    }

    fun saveAddresses(context: Context, addresses: List<String>) {
        val clean = addresses.map { it.trim() }.filter { it.isNotBlank() }
        prefs(context).edit().putString(KEY_ADDRESSES, clean.joinToString(SEP)).apply()
    }

    fun addAddress(context: Context, address: String) {
        val trimmed = address.trim()
        if (trimmed.isBlank()) return
        val list = getAddresses(context)
        list.removeAll { it.equals(trimmed, ignoreCase = true) }
        list.add(0, trimmed)
        saveAddresses(context, list)
    }

    fun setCurrent(context: Context, address: String) {
        val list = getAddresses(context)
        val target = list.firstOrNull { it.equals(address, ignoreCase = true) } ?: return
        list.removeAll { it.equals(target, ignoreCase = true) }
        list.add(0, target)
        saveAddresses(context, list)
    }
}
