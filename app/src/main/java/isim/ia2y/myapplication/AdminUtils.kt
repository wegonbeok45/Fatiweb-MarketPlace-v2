package isim.ia2y.myapplication

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Verifies the current user has admin role. Returns true if verified.
 * Finishes the activity and shows a snackbar if verification fails.
 */
suspend fun AppCompatActivity.requireAdminRole(): Boolean {
    val uid = FirebaseAuthManager.currentUser?.uid
    val role = uid?.let { runCatching { FirestoreService.fetchUserRole(it) }.getOrNull() }
    if (uid == null || role != "admin") {
        if (uid != null && role == null) {
            showMotionSnackbar(getString(R.string.admin_verify_failed))
        }
        finish()
        return false
    }
    return true
}

fun AppCompatActivity.setupAdminWindowInsets(appBarId: Int, bottomNavId: Int = R.id.adminBottomNav) {
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(appBarId)) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updatePadding(top = bars.top)
        insets
    }
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(bottomNavId)) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.updatePadding(bottom = bars.bottom)
        insets
    }
}
