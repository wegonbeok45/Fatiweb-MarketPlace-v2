package isim.ia2y.myapplication

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

object ConfigService {
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val commerceConfigRef = db.collection(FirestoreCollections.APP_CONFIG).document(FirestoreCollections.COMMERCE)

    suspend fun fetchCommerceConfig(): FirestoreService.CommerceConfig {
        val doc = commerceConfigRef.get().await()
        if (!doc.exists()) return FirestoreService.CommerceConfig()
        return FirestoreService.CommerceConfig(
            standardShippingFee = doc.getDouble("standardShippingFee") ?: CartStore.LIVRAISON_FEE,
            expressShippingFee = doc.getDouble("expressShippingFee") ?: 12.5,
            updatedAt = doc.getLong("updatedAt") ?: 0L
        )
    }

    suspend fun saveCommerceConfig(config: FirestoreService.CommerceConfig): FirestoreService.CommerceConfig {
        val normalized = config.copy(updatedAt = System.currentTimeMillis())
        commerceConfigRef.set(
            mapOf(
                "standardShippingFee" to normalized.standardShippingFee,
                "expressShippingFee" to normalized.expressShippingFee,
                "updatedAt" to normalized.updatedAt
            ),
            SetOptions.merge()
        ).await()
        return normalized
    }
}
