package isim.ia2y.myapplication

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

data class UserLocation(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val address: String = "",
    val city: String = "",
    val source: String = SOURCE_MANUAL,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val displayText: String
        get() = address.trim().ifBlank { city.trim() }

    fun toMap(): Map<String, Any> = buildMap {
        latitude?.let { put("latitude", it) }
        longitude?.let { put("longitude", it) }
        put("address", address.trim())
        put("city", city.trim())
        put("source", if (source == SOURCE_GPS) SOURCE_GPS else SOURCE_MANUAL)
        put("updatedAt", updatedAt)
    }

    fun isMeaningfullySame(other: UserLocation?): Boolean {
        other ?: return false
        val sameText = address.normalizedLocationText() == other.address.normalizedLocationText() &&
            city.normalizedLocationText() == other.city.normalizedLocationText() &&
            source == other.source
        val sameLatitude = latitude.closeTo(other.latitude)
        val sameLongitude = longitude.closeTo(other.longitude)
        return sameText && sameLatitude && sameLongitude
    }

    fun toDeliveryAddress(name: String = "", phone: String = ""): DeliveryAddress {
        val line = displayText
        val cityValue = city.trim().ifBlank { line }
        return DeliveryAddress(
            label = if (source == SOURCE_GPS) "Localisation" else "Adresse",
            recipientName = name.trim(),
            phone = DeliveryAddressValidator.normalizedPhone(phone),
            governorate = cityValue,
            city = cityValue,
            addressLine1 = line,
            isDefault = true
        )
    }

    companion object {
        const val SOURCE_GPS = "gps"
        const val SOURCE_MANUAL = "manual"

        @Suppress("UNCHECKED_CAST")
        fun fromAny(value: Any?): UserLocation? {
            val map = value as? Map<String, Any?> ?: return null
            val location = UserLocation(
                latitude = (map["latitude"] as? Number)?.toDouble(),
                longitude = (map["longitude"] as? Number)?.toDouble(),
                address = map["address"] as? String ?: "",
                city = map["city"] as? String ?: "",
                source = (map["source"] as? String)?.takeIf { it == SOURCE_GPS || it == SOURCE_MANUAL } ?: SOURCE_MANUAL,
                updatedAt = (map["updatedAt"] as? Number)?.toLong() ?: 0L
            )
            return location.takeIf { it.displayText.isNotBlank() }
        }

        fun fromDeliveryAddress(address: DeliveryAddress, source: String = SOURCE_MANUAL): UserLocation? {
            val display = address.summaryLine.ifBlank { address.addressLine1 }
            if (display.isBlank()) return null
            return UserLocation(
                address = display,
                city = address.city.ifBlank { address.governorate },
                source = if (source == SOURCE_GPS) SOURCE_GPS else SOURCE_MANUAL,
                updatedAt = System.currentTimeMillis()
            )
        }
    }
}

object UserLocationStore {
    private const val PREFS_NAME = "user_location_store"
    private const val KEY_LOCATION = "location_json"
    private const val GUEST_KEY = "guest"

    private fun accountKey(): String = runCatching { FirebaseAuthManager.currentRealUid }.getOrNull() ?: GUEST_KEY

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("${accountKey()}_$PREFS_NAME", Context.MODE_PRIVATE)

    fun save(context: Context, location: UserLocation) {
        if (location.displayText.isBlank()) return
        val json = JSONObject().apply {
            location.latitude?.let { put("latitude", it) }
            location.longitude?.let { put("longitude", it) }
            put("address", location.address)
            put("city", location.city)
            put("source", location.source)
            put("updatedAt", location.updatedAt)
        }
        prefs(context).edit().putString(KEY_LOCATION, json.toString()).apply()
    }

    fun load(context: Context): UserLocation? {
        val raw = prefs(context).getString(KEY_LOCATION, "").orEmpty()
        if (raw.isBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            UserLocation(
                latitude = json.optDoubleOrNull("latitude"),
                longitude = json.optDoubleOrNull("longitude"),
                address = json.optString("address", ""),
                city = json.optString("city", ""),
                source = json.optString("source", UserLocation.SOURCE_MANUAL),
                updatedAt = json.optLong("updatedAt", 0L)
            )
        }.getOrNull()?.takeIf { it.displayText.isNotBlank() }
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_LOCATION).apply()
    }
}

object LocationProfileSync {
    private const val TAG = "LocationFlow"

    suspend fun saveLocation(context: Context, location: UserLocation): Boolean {
        if (location.displayText.isBlank()) return false
        UserLocationStore.save(context, location)
        val uid = FirebaseAuthManager.currentUser?.uid ?: return false
        return runCatching { FirestoreService.updateUserLocationIfChanged(uid, location) }
            .onFailure { Log.w(TAG, "Location save failed", it) }
            .getOrDefault(false)
    }

    suspend fun saveManualAddress(context: Context, address: DeliveryAddress): Boolean {
        val location = UserLocation.fromDeliveryAddress(address, UserLocation.SOURCE_MANUAL) ?: return false
        Log.d(TAG, "Checkout used manual location")
        return saveLocation(context, location)
    }
}

object LocationPermissionStore {
    private const val PREFS_NAME = "location_permission_state"
    private const val KEY_STARTUP_REQUEST_SHOWN = "startup_request_shown"
    private const val KEY_PERMISSION_EVER_REQUESTED = "permission_ever_requested"
    private const val KEY_PERMISSION_PERMANENTLY_DENIED = "permission_permanently_denied"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun shouldAskOnStartup(context: Context): Boolean =
        !prefs(context).getBoolean(KEY_STARTUP_REQUEST_SHOWN, false) && !LocationHelper.hasPermission(context)

    fun markStartupRequestShown(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_STARTUP_REQUEST_SHOWN, true)
            .putBoolean(KEY_PERMISSION_EVER_REQUESTED, true)
            .apply()
    }

    fun markPermissionResult(context: Context, granted: Boolean, permanentlyDenied: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_PERMISSION_EVER_REQUESTED, true)
            .putBoolean(KEY_PERMISSION_PERMANENTLY_DENIED, !granted && permanentlyDenied)
            .apply()
    }

    fun wasPermissionEverRequested(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PERMISSION_EVER_REQUESTED, false)

    fun isPermanentlyDenied(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PERMISSION_PERMANENTLY_DENIED, false) && !LocationHelper.hasPermission(context)
}

private fun String.normalizedLocationText(): String =
    trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

private fun Double?.closeTo(other: Double?): Boolean {
    if (this == null && other == null) return true
    if (this == null || other == null) return false
    return abs(this - other) < 0.0001
}

private fun JSONObject.optDoubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null
