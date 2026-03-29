package isim.ia2y.myapplication

import android.content.Context

private const val PREFS_APP_FLOW = "app_flow"
private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

fun Context.isOnboardingCompleted(): Boolean {
    return getSharedPreferences(PREFS_APP_FLOW, Context.MODE_PRIVATE)
        .getBoolean(KEY_ONBOARDING_COMPLETED, false)
}

fun Context.setOnboardingCompleted(completed: Boolean = true) {
    getSharedPreferences(PREFS_APP_FLOW, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ONBOARDING_COMPLETED, completed)
        .apply()
}
