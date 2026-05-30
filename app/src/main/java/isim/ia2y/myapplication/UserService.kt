package isim.ia2y.myapplication

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.security.MessageDigest
import java.util.Locale

object UserService {
    private data class RoleCacheEntry(
        val uid: String,
        val role: String,
        val cachedAtMillis: Long
    )

    private const val ROLE_CACHE_TTL_MS = 30 * 60 * 1000L
    private const val CLIENT_ROLE_CACHE_TTL_MS = 30 * 60 * 1000L
    private const val PREFS_NAME = "user_role_cache"
    private const val KEY_ROLE_PREFIX = "role_"
    private const val KEY_CACHED_AT_PREFIX = "role_cached_at_"

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private fun userRef(uid: String) = db.collection(FirestoreCollections.USERS).document(uid)
    private fun addressesRef(uid: String) = userRef(uid).collection(FirestoreCollections.ADDRESSES)
    private fun favoritesRef(uid: String) = userRef(uid).collection(FirestoreCollections.FAVORITES)
    private fun fcmTokensRef(uid: String) = userRef(uid).collection("fcmTokens")

    private var cachedUserRole: RoleCacheEntry? = null
    private var prefs: SharedPreferences? = null

    private val _roleFlow = MutableStateFlow<Pair<String, String>?>(null)
    /** Emits the current (uid, role) the moment a role becomes known or changes. */
    val roleFlow: StateFlow<Pair<String, String>?> = _roleFlow.asStateFlow()

    private val _avatarUrlFlow = MutableStateFlow<Pair<String, String?>?>(null)
    /** Emits (uid, avatarUrl) whenever the user's avatar changes. */
    val avatarUrlFlow: StateFlow<Pair<String, String?>?> = _avatarUrlFlow.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    suspend fun saveUserProfile(
        uid: String,
        name: String,
        email: String,
        avatarUrl: String? = null,
        roleOverride: String? = null
    ) {
        val existing = userRef(uid).get().await()
        FirebaseCostTracker.read("UserService.saveUserProfile", "users/$uid", if (existing.exists()) 1 else 0)
        val existingRole = normalizeRole(existing.getString("role"))
        val requestedRole = normalizeRole(roleOverride)
        val nextRole = when {
            existingRole == UserRoles.ADMIN || existingRole == UserRoles.VENDEUR -> existingRole
            requestedRole == UserRoles.ADMIN || requestedRole == UserRoles.VENDEUR -> requestedRole
            else -> existingRole
        }
        val data = mapOf(
            "uid" to uid,
            "name" to name,
            "displayName" to name,
            "email" to email,
            "role" to nextRole,
            "status" to (existing.getString("status") ?: "active"),
            "avatarUrl" to (avatarUrl ?: existing.getString("avatarUrl")),
            "avatar" to (avatarUrl ?: existing.getString("avatar")),
            "notificationPreferences" to (
                existing.get("notificationPreferences")
                    ?: defaultNotificationPreferences()
                ),
            "createdAt" to (existing.get("createdAt") ?: com.google.firebase.firestore.FieldValue.serverTimestamp()),
            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "schemaVersion" to 2
        )
        userRef(uid).set(data, SetOptions.merge()).await()
        FirebaseCostTracker.write("UserService.saveUserProfile", "users/$uid")
        rememberRole(uid, nextRole)
    }

    suspend fun updateUserAvatarUrl(uid: String, avatarUrl: String) {
        val current = userRef(uid).get().await()
        FirebaseCostTracker.read("UserService.updateUserAvatarUrl", "users/$uid", if (current.exists()) 1 else 0)
        val serverNow = com.google.firebase.firestore.FieldValue.serverTimestamp()
        if (current.exists()) {
            userRef(uid).set(
                mapOf(
                    "uid" to uid,
                    "avatarUrl" to avatarUrl,
                    "avatar" to avatarUrl,
                    "photoUrl" to avatarUrl,
                    "updatedAt" to serverNow
                ),
                SetOptions.merge()
            ).await()
            FirebaseCostTracker.write("UserService.updateUserAvatarUrl", "users/$uid")
            _avatarUrlFlow.value = uid to avatarUrl
            return
        }

        val authUser = FirebaseAuthManager.currentUser
        userRef(uid).set(
            mapOf(
                "uid" to uid,
                "name" to authUser.profileNameFallback(),
                "displayName" to authUser.profileNameFallback(),
                "email" to authUser?.email.orEmpty(),
                "role" to UserRoles.CLIENT,
                "status" to "active",
                "avatarUrl" to avatarUrl,
                "avatar" to avatarUrl,
                "photoUrl" to avatarUrl,
                "notificationPreferences" to defaultNotificationPreferences(),
                "createdAt" to serverNow,
                "updatedAt" to serverNow,
                "schemaVersion" to 2
            ),
            SetOptions.merge()
        ).await()
        FirebaseCostTracker.write("UserService.updateUserAvatarUrl", "users/$uid")
        _avatarUrlFlow.value = uid to avatarUrl
    }

    suspend fun updateUserProfileName(uid: String, name: String) {
        userRef(uid).set(
            mapOf(
                "name" to name,
                "displayName" to name,
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
        FirebaseCostTracker.write("UserService.updateUserProfileName", "users/$uid")
    }

    suspend fun submitVendorApplication(uid: String, shopName: String, phone: String, note: String) {
        if (uid.isBlank()) throw IllegalStateException("Authentication is required.")
        val cleanShopName = shopName.trim()
        val cleanPhone = DeliveryAddressValidator.normalizedPhone(phone)
        val cleanNote = note.trim()
        if (cleanShopName.length < 2) throw IllegalArgumentException("Shop name is required.")
        if (cleanPhone.length < 8) throw IllegalArgumentException("Phone number is required.")

        val existing = userRef(uid).get().await()
        FirebaseCostTracker.read("UserService.submitVendorApplication", "users/$uid", if (existing.exists()) 1 else 0)
        val existingRole = normalizeRole(existing.getString("role"))
        if (existingRole == UserRoles.ADMIN || existingRole == UserRoles.VENDEUR) {
            throw IllegalStateException("This account already has elevated access.")
        }

        val authUser = FirebaseAuthManager.currentUser
        val serverNow = FieldValue.serverTimestamp()
        val data = mutableMapOf<String, Any?>(
            "uid" to uid,
            VendorFields.STATUS to VendorStatus.PENDING.wireValue,
            VendorFields.APPLICATION_SUBMITTED_AT to serverNow,
            VendorFields.SHOP_NAME to cleanShopName,
            VendorFields.SHOP_BIO to cleanNote,
            "phone" to cleanPhone,
            "updatedAt" to serverNow,
            "schemaVersion" to 2
        )

        val email = existing.getString("email") ?: authUser?.email.orEmpty()
        if (email.isNotBlank()) data["email"] = email

        if (!existing.exists()) {
            val fallbackName = authUser.profileNameFallback()
            data += mapOf(
                "name" to fallbackName,
                "displayName" to fallbackName,
                "role" to UserRoles.CLIENT,
                "status" to "active",
                "notificationPreferences" to defaultNotificationPreferences(),
                "createdAt" to serverNow
            )
        }

        userRef(uid).set(data, SetOptions.merge()).await()
        FirebaseCostTracker.write("UserService.submitVendorApplication", "users/$uid")
    }

    suspend fun updateUserLocationIfChanged(uid: String, location: UserLocation): Boolean {
        if (uid.isBlank() || location.displayText.isBlank()) return false
        val cachedDoc = runCatching { userRef(uid).get(Source.CACHE).await() }.getOrNull()
        val doc = cachedDoc ?: userRef(uid).get().await().also {
            FirebaseCostTracker.read("UserService.updateUserLocationIfChanged", "users/$uid", if (it.exists()) 1 else 0)
        }
        val existing = UserLocation.fromAny(doc.get("location"))
        if (location.isMeaningfullySame(existing)) {
            Log.d("LocationFlow", "Location saved/skipped because unchanged")
            return false
        }

        val serverNow = FieldValue.serverTimestamp()
        val data = if (doc.exists()) {
            mapOf(
                "location" to location.toMap(),
                "updatedAt" to serverNow
            )
        } else {
            val authUser = FirebaseAuthManager.currentUser
            mapOf(
                "uid" to uid,
                "name" to authUser.profileNameFallback(),
                "displayName" to authUser.profileNameFallback(),
                "email" to authUser?.email.orEmpty(),
                "role" to UserRoles.CLIENT,
                "status" to "active",
                "notificationPreferences" to defaultNotificationPreferences(),
                "createdAt" to serverNow,
                "updatedAt" to serverNow,
                "schemaVersion" to 2,
                "location" to location.toMap()
            )
        }
        userRef(uid).set(data, SetOptions.merge()).await()
        FirebaseCostTracker.write("UserService.updateUserLocationIfChanged", "users/$uid")
        Log.d("LocationFlow", "Location saved")
        return true
    }

    suspend fun markPhoneAccount(uid: String, phone: String) {
        userRef(uid).set(
            mapOf(
                "phone" to DeliveryAddressValidator.normalizedPhone(phone),
                "isPhoneAccount" to true,
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
        FirebaseCostTracker.write("UserService.markPhoneAccount", "users/$uid")
    }

    suspend fun fetchUserProfile(uid: String): FirestoreService.UserProfile? {
        val cachedDoc = runCatching { userRef(uid).get(Source.CACHE).await() }.getOrNull()
        val doc = cachedDoc ?: userRef(uid).get().await().also {
            FirebaseCostTracker.read("UserService.fetchUserProfile", "users/$uid", if (it.exists()) 1 else 0)
        }
        val data = doc.data ?: return null
        return FirestoreService.UserProfile(
            uid = uid,
            name = data["name"] as? String ?: "",
            email = data["email"] as? String ?: "",
            role = normalizeRole(data["role"] as? String),
            avatarUrl = data["avatarUrl"] as? String ?: data["avatar"] as? String ?: data["photoUrl"] as? String,
            location = UserLocation.fromAny(data["location"]),
            createdAt = data["createdAt"],
            updatedAt = data["updatedAt"],
            phone = data["phone"] as? String ?: "",
            vendorStatus = data[VendorFields.STATUS] as? String ?: "",
            shopName = data[VendorFields.SHOP_NAME] as? String ?: "",
            shopBio = data[VendorFields.SHOP_BIO] as? String ?: ""
        )
    }

    suspend fun fetchUserRole(uid: String, forceRefresh: Boolean = false): String {
        if (!forceRefresh) {
            cachedRole(uid)?.let { return it }
        }

        currentUserRoleFromClaims(uid, forceRefresh)?.takeIf { it != UserRoles.CLIENT }?.let { claimedRole ->
            rememberRole(uid, claimedRole)
            return claimedRole
        }

        val roleDoc = userRef(uid).get().await()
        FirebaseCostTracker.read("UserService.fetchUserRole", "users/$uid", if (roleDoc.exists()) 1 else 0)
        val role = normalizeRole(roleDoc.getString("role"))
        if (role == UserRoles.ADMIN || role == UserRoles.VENDEUR) {
            ensureCurrentUserRoleClaim(uid, role)
            rememberRole(uid, role)
            return role
        }
        rememberRole(uid, role)
        return role
    }

    fun cachedRole(uid: String): String? {
        cachedUserRole?.let { cached ->
            if (cached.uid == uid && !cached.isExpired()) return cached.role
        }

        val prefs = prefs ?: return null
        val storedRole = prefs.getString("$KEY_ROLE_PREFIX$uid", null) ?: return null
        val role = normalizeRole(storedRole)
        val cachedAt = prefs.getLong("$KEY_CACHED_AT_PREFIX$uid", 0L)
        val cached = RoleCacheEntry(uid, role, cachedAt)
        if (cached.isExpired()) return null
        cachedUserRole = cached
        if (_roleFlow.value?.first != uid || _roleFlow.value?.second != role) {
            _roleFlow.value = uid to role
        }
        return role
    }

    suspend fun fetchUserStatus(uid: String): String {
        return userRef(uid).get().await().getString("status") ?: "active"
    }

    suspend fun saveFcmToken(uid: String, token: String) {
        if (uid.isBlank() || token.isBlank()) return
        fcmTokensRef(uid).document(token.stableTokenId()).set(
            mapOf(
                "token" to token,
                "platform" to "android",
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                "schemaVersion" to 2
            ),
            SetOptions.merge()
        ).await()
        FirebaseCostTracker.write("FcmTokenService", "users/$uid/fcmTokens")
    }

    suspend fun deleteFcmToken(uid: String, token: String) {
        if (uid.isBlank() || token.isBlank()) return
        fcmTokensRef(uid).document(token.stableTokenId()).delete().await()
    }

    suspend fun isCurrentUserAdmin(): Boolean {
        val uid = FirebaseAuthManager.currentUser?.uid ?: return false
        
        cachedUserRole?.let { cached ->
            if (cached.uid == uid && !cached.isExpired()) return cached.role == "admin"
        }

        return fetchUserRole(uid) == "admin"
    }

    private suspend fun currentUserRoleFromClaims(uid: String, forceRefresh: Boolean = false): String? {
        val currentUser = FirebaseAuthManager.currentUser ?: return null
        if (currentUser.uid != uid) return null

        val tokenResult = currentUser.getIdToken(forceRefresh).await()
        val claims = tokenResult.claims
        return when {
            claims["admin"] == true -> UserRoles.ADMIN
            claims["role"] is String -> normalizeRole(claims["role"] as? String)
            else -> null
        }
    }

    suspend fun hasCurrentUserRoleClaim(
        uid: String,
        role: String,
        forceRefresh: Boolean = true
    ): Boolean {
        val claimedRole = currentUserRoleFromClaims(uid, forceRefresh)
        return claimedRole == normalizeRole(role)
    }

    private suspend fun ensureCurrentUserRoleClaim(uid: String, role: String): Boolean {
        val normalizedRole = normalizeRole(role)
        if (normalizedRole != UserRoles.ADMIN && normalizedRole != UserRoles.VENDEUR) return false
        if (hasCurrentUserRoleClaim(uid, normalizedRole, forceRefresh = true)) return true

        runCatching {
            userRef(uid).set(
                mapOf("updatedAt" to FieldValue.serverTimestamp()),
                SetOptions.merge()
            ).await()
        }

        repeat(5) { attempt ->
            delay(700L * (attempt + 1))
            if (hasCurrentUserRoleClaim(uid, normalizedRole, forceRefresh = true)) return true
        }
        return false
    }

    fun clearCache() {
        cachedUserRole = null
        prefs?.edit()?.clear()?.apply()
        _roleFlow.value = null
        _avatarUrlFlow.value = null
    }

    fun invalidateRole(uid: String) {
        if (cachedUserRole?.uid == uid) cachedUserRole = null
        prefs?.edit()
            ?.remove("$KEY_ROLE_PREFIX$uid")
            ?.remove("$KEY_CACHED_AT_PREFIX$uid")
            ?.apply()
        if (_roleFlow.value?.first == uid) _roleFlow.value = null
    }

    fun cacheRole(uid: String, role: String) {
        rememberRole(uid, normalizeRole(role))
    }

    private fun rememberRole(uid: String, role: String) {
        val now = System.currentTimeMillis()
        val normalizedRole = normalizeRole(role)
        cachedUserRole = RoleCacheEntry(uid, normalizedRole, now)
        prefs?.edit()
            ?.putString("$KEY_ROLE_PREFIX$uid", normalizedRole)
            ?.putLong("$KEY_CACHED_AT_PREFIX$uid", now)
            ?.apply()
        _roleFlow.value = uid to normalizedRole
    }

    private fun defaultNotificationPreferences(): Map<String, Boolean> = mapOf(
        "orderUpdates" to true,
        "promotions" to true,
        "announcements" to true,
        "pushEnabled" to true
    )

    private fun normalizeRole(role: String?): String {
        return when (role?.trim()?.lowercase(Locale.getDefault())) {
            UserRoles.ADMIN -> UserRoles.ADMIN
            UserRoles.VENDEUR, "vendor", "seller" -> UserRoles.VENDEUR
            else -> UserRoles.CLIENT
        }
    }

    private fun FirebaseUser?.profileNameFallback(): String {
        return this?.displayName?.takeIf { it.isNotBlank() }
            ?: this?.email?.substringBefore("@")?.takeIf { it.isNotBlank() }
            ?: "Client"
    }

    internal fun stableFcmTokenDocumentId(token: String): String = token.stableTokenId()

    private fun String.stableTokenId(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            "%02x".format(Locale.US, byte.toInt() and 0xff)
        }
    }

    private fun RoleCacheEntry.isExpired(): Boolean {
        val ttl = if (role == UserRoles.CLIENT) CLIENT_ROLE_CACHE_TTL_MS else ROLE_CACHE_TTL_MS
        return System.currentTimeMillis() - cachedAtMillis >= ttl
    }

    suspend fun fetchAddresses(uid: String): List<DeliveryAddress> {
        val snapshot = addressesRef(uid).get().await()
        FirebaseCostTracker.read("UserService.fetchAddresses", "users/$uid/addresses", snapshot.size())
        return snapshot.documents.mapNotNull { doc ->
            DeliveryAddress.fromAny(doc.data)?.copy(id = doc.id)
        }
    }

    suspend fun replaceAddresses(uid: String, addresses: List<DeliveryAddress>) {
        val existing = addressesRef(uid).get().await()
        FirebaseCostTracker.read("UserService.replaceAddresses", "users/$uid/addresses", existing.size())
        val batch = db.batch()
        val nextById = addresses.associateBy { it.id }
        existing.documents
            .filter { it.id !in nextById }
            .forEach { batch.delete(it.reference) }
        addresses.forEach { address ->
            batch.set(addressesRef(uid).document(address.id), address.toMap())
        }
        batch.commit().await()
        FirebaseCostTracker.write("UserService.replaceAddresses", "users/$uid/addresses", existing.count { it.id !in nextById } + addresses.size)
    }

    suspend fun fetchFavorites(uid: String): Set<String> {
        val snapshot = favoritesRef(uid).get().await()
        FirebaseCostTracker.read("UserService.fetchFavorites", "users/$uid/favorites", snapshot.size())
        return snapshot.documents.map { it.id }.toSet()
    }

    suspend fun replaceFavorites(uid: String, favorites: Set<String>) {
        val existing = favoritesRef(uid).get().await()
        FirebaseCostTracker.read("UserService.replaceFavorites", "users/$uid/favorites", existing.size())
        val existingIds = existing.documents.map { it.id }.toSet()
        val nextIds = favorites.filter { it.isNotBlank() }.toSet()
        val idsToDelete = existingIds - nextIds
        val idsToSet = nextIds - existingIds
        if (idsToDelete.isEmpty() && idsToSet.isEmpty()) return
        val batch = db.batch()
        existing.documents
            .filter { it.id in idsToDelete }
            .forEach { batch.delete(it.reference) }
        idsToSet.forEach { productId ->
            batch.set(
                favoritesRef(uid).document(productId),
                mapOf("updatedAt" to System.currentTimeMillis())
            )
        }
        batch.commit().await()
        FirebaseCostTracker.write("UserService.replaceFavorites", "users/$uid/favorites", idsToDelete.size + idsToSet.size)
    }
}
