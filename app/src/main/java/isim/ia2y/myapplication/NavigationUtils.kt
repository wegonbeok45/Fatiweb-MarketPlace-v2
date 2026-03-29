package isim.ia2y.myapplication

import android.app.Activity
import android.content.Intent
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity

private const val TAG_NAV = "UiActionsNav"

fun AppCompatActivity.navigateNoShift(target: Class<out Activity>) {
    navigateWithMotion(target)
}

fun AppCompatActivity.navigateWithMotion(
    target: Class<out Activity>,
    isForward: Boolean = true
) {
    if (this::class.java == target) return
    val intent = Intent(this, target).apply {
        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }
    startActivity(intent)
    if (isReducedMotionEnabled()) {
        overridePendingTransition(0, 0)
    } else if (isForward) {
        overridePendingTransition(R.anim.motion_activity_enter_forward, R.anim.motion_activity_exit_forward)
    } else {
        overridePendingTransition(R.anim.motion_activity_enter_backward, R.anim.motion_activity_exit_backward)
    }
}

fun AppCompatActivity.navigateFromTop(target: Class<out Activity>) {
    if (this::class.java == target) return
    val intent = Intent(this, target).apply {
        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    }
    startActivity(intent)
    if (isReducedMotionEnabled()) {
        overridePendingTransition(0, 0)
    } else {
        overridePendingTransition(R.anim.motion_activity_enter_from_top, R.anim.motion_activity_exit_stay)
    }
}

fun AppCompatActivity.finishToTop() {
    finish()
    if (isReducedMotionEnabled()) {
        overridePendingTransition(0, 0)
    } else {
        overridePendingTransition(R.anim.motion_activity_enter_stay, R.anim.motion_activity_exit_to_top)
    }
}

fun AppCompatActivity.navigateToMainTab(tab: MainActivity.Tab) {
    val intent = Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        putExtra(MainActivity.EXTRA_OPEN_TAB, tab.name)
    }
    startActivity(intent)
    if (isReducedMotionEnabled()) {
        overridePendingTransition(0, 0)
    } else {
        overridePendingTransition(R.anim.motion_activity_enter_forward, R.anim.motion_activity_exit_forward)
    }
}

/**
 * Navigate from the LoadingScreen to MainActivity with NO animation.
 * The loading screen already fades out visually before calling this, so no
 * slide/fade is needed — doing one would cause a flicker mid-layout-inflation.
 * FLAG_ACTIVITY_CLEAR_TASK removes the loading screen from the back stack entirely.
 */
fun AppCompatActivity.launchMainFromLoader(tab: MainActivity.Tab) {
    val intent = Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        putExtra(MainActivity.EXTRA_OPEN_TAB, tab.name)
    }
    startActivity(intent)
    overridePendingTransition(0, 0)
}

fun AppCompatActivity.navigateBackToMain() {
    if (this::class.java == MainActivity::class.java) {
        finishWithMotion(isForward = false)
        return
    }

    val intent = Intent(this, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    startActivity(intent)
    if (isReducedMotionEnabled()) {
        overridePendingTransition(0, 0)
    } else {
        overridePendingTransition(R.anim.motion_activity_enter_backward, R.anim.motion_activity_exit_backward)
    }
    finish()
}

fun AppCompatActivity.bindBackToMainForBottomNavScreens() {
    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            navigateBackToMain()
        }
    })
}

fun AppCompatActivity.finishWithMotion(isForward: Boolean = false) {
    finish()
    if (isReducedMotionEnabled()) {
        overridePendingTransition(0, 0)
    } else if (isForward) {
        overridePendingTransition(R.anim.motion_activity_enter_forward, R.anim.motion_activity_exit_forward)
    } else {
        overridePendingTransition(R.anim.motion_activity_enter_backward, R.anim.motion_activity_exit_backward)
    }
}

fun Activity.navigateToProductDetails(productId: String) {
    startActivity(ProductDetailsScreen.createIntent(this, productId))
    if (this is AppCompatActivity && isReducedMotionEnabled()) {
        overridePendingTransition(0, 0)
    } else if (this is AppCompatActivity) {
        overridePendingTransition(R.anim.motion_activity_enter_forward, R.anim.motion_activity_exit_forward)
    }
}

fun Activity.openWhatsApp(number: String, message: String = "") {
    try {
        val cleanNumber = number.replace("+", "").replace(" ", "")
        val uri = android.net.Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=${android.net.Uri.encode(message)}")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        startActivity(intent)
    } catch (e: Exception) {
        Log.e("Nav", "Failed to open WhatsApp", e)
    }
}

fun Activity.openEmail(email: String, subject: String = "", body: String = "") {
    try {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(intent)
    } catch (e: Exception) {
        Log.e("Nav", "Failed to open Email", e)
    }
}
