package isim.ia2y.myapplication

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

const val NOTIFICATION_PERMISSION_REQUEST_CODE = 951

private const val PREFS_PERMISSIONS = "app_runtime_permissions"
private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
private const val KEY_NOTIFICATION_PERMISSION_GRANTED = "notification_permission_granted"

private fun Context.permissionsPrefs() = getSharedPreferences(PREFS_PERMISSIONS, Context.MODE_PRIVATE)

private fun Context.wasNotificationPermissionRequested(): Boolean =
    permissionsPrefs().getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)

private fun Context.markNotificationPermissionRequested() {
    permissionsPrefs().edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
}

private fun Context.markNotificationPermissionGranted(granted: Boolean) {
    permissionsPrefs().edit().putBoolean(KEY_NOTIFICATION_PERMISSION_GRANTED, granted).apply()
}

fun Context.hasNotificationPostPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    return ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

fun AppCompatActivity.maybeRequestNotificationPermissionForPush(force: Boolean = false): Boolean {
    if (hasNotificationPostPermission()) {
        markNotificationPermissionGranted(true)
        syncFcmTokenIfAllowed()
        return true
    }

    if (force || !wasNotificationPermissionRequested()) {
        markNotificationPermissionRequested()
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST_CODE
        )
    }
    return false
}

fun AppCompatActivity.bindNotificationEntry(vararg ids: Int) {
    ids.forEach { viewId ->
        findViewById<View?>(viewId)?.setOnClickListener {
            handleNotificationEntryClick()
        }
    }
}

fun AppCompatActivity.openNotificationsScreenWithPermissionCheck() {
    handleNotificationEntryClick()
}

private fun AppCompatActivity.handleNotificationEntryClick() {
    if (hasNotificationPostPermission()) {
        markNotificationPermissionGranted(true)
        openNotificationsScreen()
        return
    }

    maybeRequestNotificationPermissionForPush(force = true)
    openNotificationsScreen()
}

fun AppCompatActivity.handleNotificationPermissionResult(
    requestCode: Int,
    grantResults: IntArray
): Boolean {
    if (requestCode != NOTIFICATION_PERMISSION_REQUEST_CODE) return false

    val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
    markNotificationPermissionGranted(granted)
    if (granted) {
        syncFcmTokenIfAllowed()
    }
    if (!granted) {
        showToast(getString(R.string.notifications_permission_denied_hint))
    }
    return true
}

private fun AppCompatActivity.syncFcmTokenIfAllowed() {
    if (!FirebaseAuthManager.isLoggedIn) return
    if (!NotificationPreferencesStore.load(this).pushEnabled) return
    val appContext = applicationContext
    lifecycleScope.launch {
        runCatching { FcmTokenService.syncCurrentUserToken(appContext) }
    }
}

fun AppCompatActivity.openNotificationsScreen() {
    startActivity(Intent(this, NotificationsActivity::class.java))
    if (isReducedMotionEnabled()) {
        overridePendingTransition(0, 0)
    } else {
        overridePendingTransition(R.anim.motion_activity_enter_forward, R.anim.motion_activity_exit_forward)
    }
}
