package isim.ia2y.myapplication

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

object UserService {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private fun userRef(uid: String) = db.collection(FirestoreCollections.USERS).document(uid)
    private fun addressesRef(uid: String) = userRef(uid).collection(FirestoreCollections.ADDRESSES)
    private fun favoritesRef(uid: String) = userRef(uid).collection(FirestoreCollections.FAVORITES)

    private var cachedUserRole: Pair<String, String>? = null

    suspend fun saveUserProfile(
        uid: String,
        name: String,
        email: String,
        avatarUrl: String? = null,
        roleOverride: String? = null
    ) {
        val existing = userRef(uid).get().await()
        val data = mapOf(
            "uid" to uid,
            "name" to name,
            "displayName" to name,
            "email" to email,
            "role" to (roleOverride ?: existing.getString("role") ?: "client"),
            "status" to (existing.getString("status") ?: "active"),
            "avatarUrl" to (avatarUrl ?: existing.getString("avatarUrl")),
            "avatar" to (avatarUrl ?: existing.getString("avatar")),
            "createdAt" to (existing.get("createdAt") ?: com.google.firebase.firestore.FieldValue.serverTimestamp()),
            "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "schemaVersion" to 2
        )
        userRef(uid).set(data, SetOptions.merge()).await()
    }

    suspend fun updateUserAvatarUrl(uid: String, avatarUrl: String) {
        userRef(uid).set(
            mapOf(
                "avatarUrl" to avatarUrl,
                "avatar" to avatarUrl,
                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
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
    }

    suspend fun fetchUserProfile(uid: String): FirestoreService.UserProfile? {
        val doc = userRef(uid).get().await()
        val data = doc.data ?: return null
        return FirestoreService.UserProfile(
            uid = uid,
            name = data["name"] as? String ?: "",
            email = data["email"] as? String ?: "",
            role = data["role"] as? String ?: "client",
            avatarUrl = data["avatarUrl"] as? String,
            createdAt = data["createdAt"],
            updatedAt = data["updatedAt"]
        )
    }

    suspend fun fetchUserRole(uid: String): String {
        cachedUserRole?.let { (cachedUid, role) ->
            if (cachedUid == uid) return role
        }

        currentUserRoleFromClaims(uid)?.let { claimedRole ->
            cachedUserRole = uid to claimedRole
            return claimedRole
        }

        val role = userRef(uid).get().await().getString("role") ?: "client"
        if (role != "admin") {
            currentUserRoleFromClaims(uid, forceRefresh = true)?.let { claimedRole ->
                cachedUserRole = uid to claimedRole
                return claimedRole
            }
        }
        cachedUserRole = uid to role
        return role
    }

    suspend fun isCurrentUserAdmin(): Boolean {
        val uid = FirebaseAuthManager.currentUser?.uid ?: return false
        
        cachedUserRole?.let { (cid, role) ->
            if (cid == uid) return role == "admin"
        }

        return fetchUserRole(uid) == "admin"
    }

    private suspend fun currentUserRoleFromClaims(uid: String, forceRefresh: Boolean = false): String? {
        val currentUser = FirebaseAuthManager.currentUser ?: return null
        if (currentUser.uid != uid) return null

        val tokenResult = currentUser.getIdToken(forceRefresh).await()
        val claims = tokenResult.claims
        return when {
            claims["admin"] == true -> "admin"
            claims["role"] is String -> claims["role"] as String
            else -> null
        }
    }

    fun clearCache() {
        cachedUserRole = null
    }

    suspend fun fetchAddresses(uid: String): List<DeliveryAddress> {
        val snapshot = addressesRef(uid).get().await()
        return snapshot.documents.mapNotNull { doc ->
            doc.toObject(DeliveryAddress::class.java)?.copy(id = doc.id)
        }
    }

    suspend fun replaceAddresses(uid: String, addresses: List<DeliveryAddress>) {
        val existing = addressesRef(uid).get().await()
        val batch = db.batch()
        existing.documents.forEach { batch.delete(it.reference) }
        addresses.forEach { address ->
            batch.set(addressesRef(uid).document(address.id), address.toMap())
        }
        batch.commit().await()
    }

    suspend fun fetchFavorites(uid: String): Set<String> {
        val snapshot = favoritesRef(uid).get().await()
        return snapshot.documents.map { it.id }.toSet()
    }

    suspend fun replaceFavorites(uid: String, favorites: Set<String>) {
        val existing = favoritesRef(uid).get().await()
        val batch = db.batch()
        existing.documents.forEach { batch.delete(it.reference) }
        favorites.filter { it.isNotBlank() }.forEach { productId ->
            batch.set(
                favoritesRef(uid).document(productId),
                mapOf("updatedAt" to System.currentTimeMillis())
            )
        }
        batch.commit().await()
    }
}
