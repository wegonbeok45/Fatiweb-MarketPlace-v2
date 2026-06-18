package isim.ia2y.myapplication.voice

import android.content.Context
import android.content.SharedPreferences

object FatiVoicePreferences {
    private const val PREFS_FATIVOICE = "fativoice_prefs"
    const val KEY_MIC_GRANTED = "fativoice_mic_granted"
    const val KEY_GPS_GRANTED = "fativoice_gps_granted"
    const val KEY_PERMISSIONS_REQUESTED = "fativoice_permissions_requested"
    const val KEY_FATIVOICE_ENABLED = "fativoice_enabled"

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_FATIVOICE, Context.MODE_PRIVATE)
    }

    fun isVoiceEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_FATIVOICE_ENABLED, false)
    }

    fun setVoiceEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FATIVOICE_ENABLED, enabled).apply()
    }
}
