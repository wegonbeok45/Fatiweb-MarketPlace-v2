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
    if (uid != null &&
        AdminSession.isVerified(uid) &&
        runCatching { UserService.hasCurrentUserRoleClaim(uid, UserRoles.ADMIN, forceRefresh = false) }.getOrDefault(false)
    ) return true

    val role = uid?.let { runCatching { FirestoreService.fetchUserRole(it, forceRefresh = true) }.getOrNull() }
    val hasAdminClaim = uid != null &&
        role == UserRoles.ADMIN &&
        runCatching { UserService.hasCurrentUserRoleClaim(uid, UserRoles.ADMIN, forceRefresh = true) }.getOrDefault(false)
    if (uid == null || role != UserRoles.ADMIN || !hasAdminClaim) {
        showMotionSnackbar(
            if (uid != null && role == null) getString(R.string.admin_verify_failed)
            else getString(R.string.admin_access_denied)
        )
        finish()
        return false
    }
    AdminSession.markVerified(uid)
    return true
}

suspend fun AppCompatActivity.requireAdminOrVendeurRole(): String? {
    val uid = FirebaseAuthManager.currentUser?.uid
    if (uid != null &&
        AdminSession.isVerified(uid) &&
        runCatching { UserService.hasCurrentUserRoleClaim(uid, UserRoles.ADMIN, forceRefresh = false) }.getOrDefault(false)
    ) return UserRoles.ADMIN

    val role = uid?.let { runCatching { FirestoreService.fetchUserRole(it, forceRefresh = true) }.getOrNull() }
    val hasRoleClaim = uid != null &&
        (role == UserRoles.ADMIN || role == UserRoles.VENDEUR) &&
        runCatching { UserService.hasCurrentUserRoleClaim(uid, role, forceRefresh = true) }.getOrDefault(false)
    if (uid == null || (role != UserRoles.ADMIN && role != UserRoles.VENDEUR) || !hasRoleClaim) {
        showMotionSnackbar(
            if (uid != null && role == null) getString(R.string.admin_verify_failed)
            else getString(R.string.admin_access_denied)
        )
        finish()
        return null
    }
    if (role == UserRoles.ADMIN) {
        AdminSession.markVerified(uid)
    }
    return role
}

fun AppCompatActivity.setupAdminWindowInsets(appBarId: Int, bottomNavId: Int = R.id.adminBottomNav) {
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(appBarId)) { view, insets ->
        val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
        view.updatePadding(top = statusBars.top)
        insets
    }
    ViewCompat.setOnApplyWindowInsetsListener(findViewById(bottomNavId)) { view, insets ->
        val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
        val baseBottomPadding = (view.getTag(R.id.adminBottomNav) as? Int)
            ?: view.paddingBottom.also { view.setTag(R.id.adminBottomNav, it) }
        view.updatePadding(
            bottom = baseBottomPadding +
                navigationBars.bottom +
                view.resources.getDimensionPixelSize(R.dimen.admin_bottom_nav_inset_extra)
        )
        insets
    }
}
