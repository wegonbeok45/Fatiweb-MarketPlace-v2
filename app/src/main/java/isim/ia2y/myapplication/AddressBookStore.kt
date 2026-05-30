package isim.ia2y.myapplication

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object AddressBookStore {
    private const val PREFS_NAME = "address_book_store"
    private const val KEY_ADDRESSES = "addresses_json"
    private const val GUEST_KEY = "guest"
    private const val LEGACY_SEP = "|||"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(context: Context) =
        prefsForAccount(context, currentUidOrNull() ?: GUEST_KEY)

    private fun prefsForAccount(context: Context, accountKey: String) =
        context.getSharedPreferences("${accountKey}_$PREFS_NAME", Context.MODE_PRIVATE)

    private fun currentUidOrNull(): String? = runCatching { FirebaseAuthManager.currentRealUid }.getOrNull()

    fun getAll(context: Context): MutableList<DeliveryAddress> {
        val raw = prefs(context).getString(KEY_ADDRESSES, "").orEmpty()
        val list = when {
            raw.isBlank() -> mutableListOf()
            raw.trimStart().startsWith("[") -> parseJsonArray(raw)
            else -> parseLegacy(raw)
        }
        return normalize(list)
    }

    fun getCurrent(context: Context): DeliveryAddress? = getAll(context).firstOrNull { it.isDefault }
        ?: getAll(context).firstOrNull()

    fun saveAll(context: Context, addresses: List<DeliveryAddress>) {
        val normalized = normalize(addresses)
        saveAllLocal(context, currentUidOrNull() ?: GUEST_KEY, normalized)
        syncCurrentAddressesToCloud(context, normalized)
    }

    suspend fun refreshFromCloud(context: Context): List<DeliveryAddress> {
        if (FirebaseCostSafeMode.enabled) return getAll(context)
        val uid = currentUidOrNull() ?: return getAll(context)
        val remoteAddresses = runCatching { FirestoreService.fetchAddresses(uid) }.getOrDefault(getAll(context))
        saveAllLocal(context, uid, remoteAddresses)
        return remoteAddresses
    }

    private fun saveAllLocal(context: Context, accountKey: String, addresses: List<DeliveryAddress>) {
        val array = JSONArray()
        normalize(addresses).forEach { address ->
            array.put(JSONObject(address.toMap()))
        }
        prefsForAccount(context, accountKey)
            .edit()
            .putString(KEY_ADDRESSES, array.toString())
            .apply()
    }

    private fun syncCurrentAddressesToCloud(context: Context, addresses: List<DeliveryAddress>) {
        if (FirebaseCostSafeMode.enabled) return
        val uid = currentUidOrNull() ?: return
        scope.launch {
            runCatching { FirestoreService.replaceAddresses(uid, addresses) }
        }
    }

    fun upsert(context: Context, address: DeliveryAddress) {
        val current = getAll(context).toMutableList()
        val index = current.indexOfFirst { it.id == address.id }
        if (index >= 0) {
            current[index] = address
        } else {
            current.add(0, address)
        }
        saveAll(context, current)
    }

    fun delete(context: Context, addressId: String) {
        val next = getAll(context).filterNot { it.id == addressId }
        saveAll(context, next)
    }

    fun setCurrent(context: Context, addressId: String) {
        val current = getAll(context)
        saveAll(
            context,
            current.map { address -> address.copy(isDefault = address.id == addressId) }
        )
    }

    fun clear(context: Context) {
        saveAllLocal(context, currentUidOrNull() ?: GUEST_KEY, emptyList())
        syncCurrentAddressesToCloud(context, emptyList())
    }

    suspend fun mergeGuestAddressesIntoCurrent(context: Context) {
        if (FirebaseCostSafeMode.enabled) return
        val uid = currentUidOrNull() ?: return
        val guestPrefs = prefsForAccount(context, GUEST_KEY)
        val guestRaw = guestPrefs.getString(KEY_ADDRESSES, "").orEmpty()
        val mergedBase = runCatching { FirestoreService.fetchAddresses(uid) }
            .getOrDefault(parseFlexible(prefsForAccount(context, uid).getString(KEY_ADDRESSES, "").orEmpty()))
        val merged = normalize(parseFlexible(guestRaw) + mergedBase)
            .distinctBy { listOf(it.summaryLine.lowercase(), it.phone.trim()).joinToString("#") }
        saveAllLocal(context, uid, merged)
        guestPrefs.edit().remove(KEY_ADDRESSES).apply()
        runCatching { FirestoreService.replaceAddresses(uid, merged) }
    }

    fun legacyFormattedLines(context: Context): MutableList<String> {
        return getAll(context).map { it.summaryLine }.filter { it.isNotBlank() }.toMutableList()
    }

    private fun parseFlexible(raw: String): MutableList<DeliveryAddress> = when {
        raw.isBlank() -> mutableListOf()
        raw.trimStart().startsWith("[") -> parseJsonArray(raw)
        else -> parseLegacy(raw)
    }

    private fun parseJsonArray(raw: String): MutableList<DeliveryAddress> {
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.opt(i)
                    when (item) {
                        is JSONObject -> {
                            DeliveryAddress.fromAny(item.toMap())?.let(::add)
                        }
                        is String -> migrateLegacyString(item)?.let(::add)
                    }
                }
            }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    private fun parseLegacy(raw: String): MutableList<DeliveryAddress> {
        return raw.split(LEGACY_SEP)
            .mapNotNull { migrateLegacyString(it) }
            .toMutableList()
    }

    private fun migrateLegacyString(value: String): DeliveryAddress? {
        val cleaned = value.trim()
        if (cleaned.isBlank()) return null
        if (cleaned.equals("Tunis, Tunisie", ignoreCase = true)) return null
        return DeliveryAddress(
            label = "Adresse",
            recipientName = FirebaseAuthManager.currentUser?.displayName.orEmpty(),
            phone = "",
            governorate = "",
            city = "",
            addressLine1 = cleaned,
            isDefault = true
        )
    }

    private fun normalize(addresses: List<DeliveryAddress>): MutableList<DeliveryAddress> {
        val cleaned = addresses.mapNotNull { address ->
            val normalized = address.copy(
                label = address.label.trim(),
                recipientName = address.recipientName.trim(),
                phone = DeliveryAddressValidator.normalizedPhone(address.phone),
                governorate = address.governorate.trim(),
                city = address.city.trim(),
                addressLine1 = address.addressLine1.trim(),
                addressLine2 = address.addressLine2?.trim()?.takeIf { it.isNotBlank() },
                postalCode = address.postalCode?.trim()?.takeIf { it.isNotBlank() },
                deliveryNotes = address.deliveryNotes?.trim()?.takeIf { it.isNotBlank() }
            )
            if (normalized.summaryLine.isBlank()) null else normalized
        }.toMutableList()

        if (cleaned.isEmpty()) return cleaned
        val defaultIndex = cleaned.indexOfFirst { it.isDefault }.takeIf { it >= 0 } ?: 0
        return cleaned.mapIndexed { index, address ->
            address.copy(isDefault = index == defaultIndex)
        }.toMutableList()
    }

    private fun JSONObject.toMap(): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        keys().forEach { key ->
            result[key] = opt(key)
        }
        return result
    }
}
