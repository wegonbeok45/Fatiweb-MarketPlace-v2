package isim.ia2y.myapplication

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

/**
 * Reads + writes vendor shop profile fields on the user document.
 * Field names live in [VendorFields] (Phase 0).
 */
object VendorShopService {

    data class ShopProfile(
        val shopName: String = "",
        val shopBio: String = "",
        val shopLogoUrl: String = "",
        val shopBannerUrl: String = "",
        val operatingHours: String = "",
        val shippingFeeDt: Double = 0.0,
        val deliveryZones: List<String> = emptyList(),
        val email: String = "",
        val phone: String = "",
    )

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private fun userRef(uid: String) = db.collection(FirestoreCollections.USERS).document(uid)

    suspend fun fetchShop(uid: String): ShopProfile {
        if (uid.isBlank()) return ShopProfile()
        val snap = userRef(uid).get().await() ?: return ShopProfile()
        val zonesAny = snap.get(VendorFields.DELIVERY_ZONES)
        val zones = when (zonesAny) {
            is List<*> -> zonesAny.mapNotNull { it as? String }
            is String -> zonesAny.split(",").map { it.trim() }.filter { it.isNotBlank() }
            else -> emptyList()
        }
        return ShopProfile(
            shopName = snap.getString(VendorFields.SHOP_NAME) ?: "",
            shopBio = snap.getString(VendorFields.SHOP_BIO) ?: "",
            shopLogoUrl = snap.getString(VendorFields.SHOP_LOGO_URL) ?: "",
            shopBannerUrl = snap.getString(VendorFields.SHOP_BANNER_URL) ?: "",
            operatingHours = snap.getString(VendorFields.SHOP_OPERATING_HOURS) ?: "",
            shippingFeeDt = (snap.get(VendorFields.SHIPPING_FEE_DT) as? Number)?.toDouble() ?: 0.0,
            deliveryZones = zones,
            email = snap.getString("email") ?: "",
            phone = snap.getString("phone") ?: "",
        )
    }

    suspend fun saveShop(uid: String, profile: ShopProfile) {
        if (uid.isBlank()) throw IllegalStateException("Authentication is required.")
        userRef(uid).set(
            mapOf(
                VendorFields.SHOP_NAME to profile.shopName,
                VendorFields.SHOP_BIO to profile.shopBio,
                VendorFields.SHOP_LOGO_URL to profile.shopLogoUrl,
                VendorFields.SHOP_BANNER_URL to profile.shopBannerUrl,
                VendorFields.SHOP_OPERATING_HOURS to profile.operatingHours,
                VendorFields.SHIPPING_FEE_DT to profile.shippingFeeDt,
                VendorFields.DELIVERY_ZONES to profile.deliveryZones,
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
    }
}
