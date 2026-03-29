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

fun AppCompatActivity.bindNotificationEntry(vararg ids: Int) {
    ids.forEach { viewId ->
        findViewById<View?>(viewId)?.setOnClickListener {
            handleNotificationEntryClick()
        }
    }
}

private fun AppCompatActivity.handleNotificationEntryClick() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        markNotificationPermissionGranted(true)
        openNotificationsScreen()
        return
    }

    val hasPermission = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
    if (hasPermission) {
        markNotificationPermissionGranted(true)
        openNotificationsScreen()
        return
    }

    if (!wasNotificationPermissionRequested()) {
        markNotificationPermissionRequested()
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST_CODE
        )
        return
    }

    showToast(getString(R.string.notifications_permission_denied_hint))
}

fun AppCompatActivity.handleNotificationPermissionResult(
    requestCode: Int,
    grantResults: IntArray
): Boolean {
    if (requestCode != NOTIFICATION_PERMISSION_REQUEST_CODE) return false

    val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
    markNotificationPermissionGranted(granted)
    if (granted) {
        openNotificationsScreen()
    } else {
        showToast(getString(R.string.notifications_permission_denied_hint))
    }
    return true
}

fun AppCompatActivity.openNotificationsScreen() {
    startActivity(Intent(this, NotificationsActivity::class.java))
    if (isReducedMotionEnabled()) {
        overridePendingTransition(0, 0)
    } else {
        overridePendingTransition(R.anim.motion_activity_enter_forward, R.anim.motion_activity_exit_forward)
    }
}
