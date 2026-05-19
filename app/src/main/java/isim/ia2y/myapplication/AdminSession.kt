package isim.ia2y.myapplication

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object AdminSession {
    private const val TAG = "AdminSession"
    private const val PREFS_NAME = "admin_session_encrypted"
    private const val LEGACY_PREFS_NAME = "admin_session"
    private const val KEY_VERIFIED_ADMIN_UID = "verified_admin_uid"

    @Volatile
    private var verifiedAdminUid: String? = null

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        val appContext = context.applicationContext
        prefs = encryptedPrefs(appContext)
        migrateLegacy(appContext)
        verifiedAdminUid = prefs?.getString(KEY_VERIFIED_ADMIN_UID, null)
    }

    fun isVerified(uid: String): Boolean = verifiedAdminUid == uid

    fun markVerified(uid: String) {
        verifiedAdminUid = uid
        prefs?.edit()?.putString(KEY_VERIFIED_ADMIN_UID, uid)?.apply()
    }

    fun clear() {
        verifiedAdminUid = null
        prefs?.edit()?.remove(KEY_VERIFIED_ADMIN_UID)?.apply()
    }

    fun clearIfDifferent(uid: String?) {
        if (uid == null || verifiedAdminUid != uid) {
            clear()
        }
    }

    private fun encryptedPrefs(context: Context): SharedPreferences {
        return runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse { error ->
            Log.e(TAG, "Encrypted admin session unavailable; using private preferences.", error)
            context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun migrateLegacy(context: Context) {
        val sessionPrefs = prefs ?: return
        val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        val legacyUid = legacyPrefs.getString(KEY_VERIFIED_ADMIN_UID, null)
        if (!legacyUid.isNullOrBlank() && !sessionPrefs.contains(KEY_VERIFIED_ADMIN_UID)) {
            sessionPrefs.edit().putString(KEY_VERIFIED_ADMIN_UID, legacyUid).apply()
        }
        legacyPrefs.edit().remove(KEY_VERIFIED_ADMIN_UID).apply()
    }
}
