package isim.ia2y.myapplication

import android.content.Context
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await

object ConfigService {
    private const val PREFS_NAME = "commerce_config_cache"
    private const val KEY_STANDARD_SHIPPING_FEE = "standardShippingFee"
    private const val KEY_EXPRESS_SHIPPING_FEE = "expressShippingFee"
    private const val KEY_UPDATED_AT = "updatedAt"
    private const val CACHE_TTL_MS = 24L * 60 * 60 * 1000

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()
    private val commerceConfigRef = db.collection(FirestoreCollections.APP_CONFIG).document(FirestoreCollections.COMMERCE)

    suspend fun fetchCommerceConfig(): FirestoreService.CommerceConfig {
        val appContext = MyApplication.instance
        cachedCommerceConfig(appContext)
            ?.takeIf { System.currentTimeMillis() - it.updatedAt < CACHE_TTL_MS }
            ?.let { return it }

        val cachedDoc = runCatching { commerceConfigRef.get(Source.CACHE).await() }.getOrNull()
        if (cachedDoc != null && cachedDoc.exists()) {
            FirebaseCostTracker.read("ConfigService.fetchCommerceConfig", "appConfig/commerce", 1, Source.CACHE.name)
            return cachedDoc.toCommerceConfig().also { cacheCommerceConfig(appContext, it) }
        }

        val doc = commerceConfigRef.get(Source.SERVER).await()
        FirebaseCostTracker.read("ConfigService.fetchCommerceConfig", "appConfig/commerce", if (doc.exists()) 1 else 0, Source.SERVER.name)
        if (!doc.exists()) return FirestoreService.CommerceConfig()
        return doc.toCommerceConfig().also { cacheCommerceConfig(appContext, it) }
    }

    fun cachedCommerceConfig(context: Context): FirestoreService.CommerceConfig? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_STANDARD_SHIPPING_FEE) || !prefs.contains(KEY_EXPRESS_SHIPPING_FEE)) {
            return null
        }
        return FirestoreService.CommerceConfig(
            standardShippingFee = Double.fromBits(prefs.getLong(KEY_STANDARD_SHIPPING_FEE, CartStore.LIVRAISON_FEE.toBits())),
            expressShippingFee = Double.fromBits(prefs.getLong(KEY_EXPRESS_SHIPPING_FEE, 12.5.toBits())),
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        )
    }

    fun cacheCommerceConfig(context: Context, config: FirestoreService.CommerceConfig) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_STANDARD_SHIPPING_FEE, config.standardShippingFee.toBits())
            .putLong(KEY_EXPRESS_SHIPPING_FEE, config.expressShippingFee.toBits())
            .putLong(KEY_UPDATED_AT, config.updatedAt)
            .apply()
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
        FirebaseCostTracker.write("ConfigService.saveCommerceConfig", "appConfig/commerce")
        return normalized
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toCommerceConfig(): FirestoreService.CommerceConfig =
        FirestoreService.CommerceConfig(
            standardShippingFee = getDouble("standardShippingFee") ?: CartStore.LIVRAISON_FEE,
            expressShippingFee = getDouble("expressShippingFee") ?: 12.5,
            updatedAt = getLong("updatedAt") ?: System.currentTimeMillis()
        )
}
