package isim.ia2y.myapplication

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.IdRes
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.card.MaterialCardView
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar

const val NOTIFICATION_PERMISSION_REQUEST_CODE = 951

// Cette classe organise cette partie de l'app.
private object MotionTokens {
    const val QUICK = 140L
    const val STANDARD = 220L
    const val EMPHASIS = 320L
}
private const val TAG_NAV = "UiActionsNav"

private const val PREFS_APP_FLOW = "app_flow"
private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

private fun Context.prefersReducedMotion(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ValueAnimator.areAnimatorsEnabled()) {
        return true
    }
    val durationScale = runCatching {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
    return durationScale == 0f
}

fun AppCompatActivity.isReducedMotionEnabled(): Boolean = prefersReducedMotion()

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


fun Context.isOnboardingCompleted(): Boolean {
    return getSharedPreferences(PREFS_APP_FLOW, Context.MODE_PRIVATE)
        .getBoolean(KEY_ONBOARDING_COMPLETED, false)
}

fun View.performLightHapticFeedback() {
    performHapticFeedback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
            HapticFeedbackConstants.KEYBOARD_TAP 
        else 
            HapticFeedbackConstants.KEYBOARD_TAP,
        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
    )
}

fun View.applyPressFeedback() {
    stateListAnimator = null
    setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                v.performLightHapticFeedback()
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(MotionTokens.QUICK)
                    .setInterpolator(FastOutSlowInInterpolator()).start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f).setDuration(MotionTokens.QUICK)
                    .setInterpolator(FastOutSlowInInterpolator()).start()
            }
        }
        false
    }
}

fun Context.setOnboardingCompleted(completed: Boolean = true) {
    getSharedPreferences(PREFS_APP_FLOW, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ONBOARDING_COMPLETED, completed)
        .apply()
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
        // Cette fonction fait une action de cette partie de l'app.
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

fun AppCompatActivity.navigateToProductDetails(productId: String) {
    startActivity(ProductDetailsScreen.createIntent(this, productId))
    if (isReducedMotionEnabled()) {
        overridePendingTransition(0, 0)
    } else {
        overridePendingTransition(R.anim.motion_activity_enter_forward, R.anim.motion_activity_exit_forward)
    }
}

fun AppCompatActivity.showToast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

fun AppCompatActivity.bindComingSoon(vararg ids: Int) {
    val message = getString(R.string.coming_soon)
    ids.forEach { viewId ->
        findViewById<View?>(viewId)?.setOnClickListener {
            showToast(message)
        }
    }
}

fun AppCompatActivity.bindAlertPopup(vararg ids: Int) {
    ids.forEach { viewId ->
        findViewById<View?>(viewId)?.setOnClickListener {
            showAlertPopupDialog()
        }
    }
}

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

fun AppCompatActivity.showAlertPopupDialog() {
    val dialog = Dialog(this, R.style.ThemeOverlay_MyApp_Dialog)
    dialog.setContentView(R.layout.dialog_alert)
    dialog.setCancelable(true)
    dialog.setCanceledOnTouchOutside(true)
    dialog.window?.setBackgroundDrawable(
        ColorDrawable(ContextCompat.getColor(this, android.R.color.transparent))
    )

    dialog.findViewById<MaterialButton?>(R.id.btnAlertCancel)?.setOnClickListener {
        dialog.dismiss()
    }
    dialog.findViewById<MaterialButton?>(R.id.btnAlertConfirm)?.setOnClickListener {
        dialog.dismiss()
        showMotionSnackbar(getString(R.string.alert_dialog_confirmed))
    }

    dialog.show()
}

fun AppCompatActivity.showAuthChoiceDialog(
    onCreateAccount: () -> Unit,
    onExistingClient: () -> Unit
) {
    val dialog = Dialog(this, R.style.ThemeOverlay_MyApp_Dialog)
    dialog.setContentView(R.layout.dialog_auth_choice)
    dialog.setCancelable(true)
    dialog.setCanceledOnTouchOutside(true)
    dialog.window?.setBackgroundDrawable(
        ColorDrawable(ContextCompat.getColor(this, android.R.color.transparent))
    )

    dialog.findViewById<TextView?>(R.id.tvAuthDialogTitle)?.text =
        getString(R.string.auth_dialog_title)
    dialog.findViewById<TextView?>(R.id.tvAuthDialogMessage)?.text =
        getString(R.string.auth_dialog_message)
    dialog.findViewById<MaterialButton?>(R.id.btnAuthCreateAccount)?.text =
        getString(R.string.auth_dialog_create_account)
    dialog.findViewById<MaterialButton?>(R.id.btnAuthExistingClient)?.text =
        getString(R.string.auth_dialog_existing_client)

    dialog.findViewById<MaterialButton?>(R.id.btnAuthCreateAccount)?.setOnClickListener {
        dialog.dismiss()
        onCreateAccount()
    }
    dialog.findViewById<MaterialButton?>(R.id.btnAuthExistingClient)?.setOnClickListener {
        dialog.dismiss()
        onExistingClient()
    }
    dialog.show()
}

fun AppCompatActivity.bindSearchComingSoon(vararg ids: Int) {
    val message = getString(R.string.search_coming_soon)
    ids.forEach { viewId ->
        findViewById<View?>(viewId)?.setOnClickListener {
            showToast(message)
        }
    }
}

fun AppCompatActivity.startTypingHintAnimation(
    @IdRes hintViewId: Int,
    fullText: String,
    stepDelayMs: Long = 115L,
    vararg interactionViewIds: Int
) {
    val view = findViewById<View?>(hintViewId) ?: return
    val textView = view as? TextView ?: return
    val isEditText = textView is EditText

    if (isReducedMotionEnabled()) {
        if (isEditText) (textView as EditText).hint = fullText else textView.text = fullText
        return
    }
    if (fullText.isEmpty()) {
        if (isEditText) (textView as EditText).hint = "" else textView.text = ""
        return
    }

    val handler = Handler(Looper.getMainLooper())
    var index = 0
    var cancelled = false

    // Cette fonction fait une action de cette partie de l'app.
    fun updateOutput(content: String) {
        if (isEditText) (textView as EditText).hint = content else textView.text = content
    }

    val typer = object : Runnable {
        // Cette fonction fait une action de cette partie de l'app.
        override fun run() {
            if (cancelled || !textView.isAttachedToWindow) return
            index += 1
            updateOutput(fullText.substring(0, index.coerceAtMost(fullText.length)))
            if (index < fullText.length) {
                handler.postDelayed(this, stepDelayMs)
            }
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    fun finishAnimation() {
        if (cancelled) return
        cancelled = true
        handler.removeCallbacks(typer)
        updateOutput(fullText)
    }

    updateOutput("")
    handler.postDelayed(typer, stepDelayMs)

    interactionViewIds.forEach { id ->
        findViewById<View?>(id)?.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                finishAnimation()
            }
            false
        }
    }
}

fun AppCompatActivity.bindBottomNav(
    @IdRes homeId: Int,
    @IdRes exploreId: Int,
    @IdRes favoritesId: Int? = null,
    @IdRes cartId: Int,
    @IdRes profileId: Int
) {
    val navIds = listOfNotNull(homeId, exploreId, favoritesId, cartId, profileId)

    findViewById<View?>(homeId)?.setOnClickListener {
        animateBottomNavAndNavigate(navIds, homeId, MainActivity::class.java) {
            navigateToMainTab(MainActivity.Tab.HOME)
        }
    }
    findViewById<View?>(exploreId)?.setOnClickListener {
        animateBottomNavAndNavigate(navIds, exploreId, MainActivity::class.java) {
            navigateToMainTab(MainActivity.Tab.EXPLORE)
        }
    }
    favoritesId?.let { id ->
        findViewById<View?>(id)?.setOnClickListener {
            animateBottomNavAndNavigate(navIds, id, FavoritesActivity::class.java)
        }
    }
    findViewById<View?>(cartId)?.setOnClickListener {
        animateBottomNavAndNavigate(navIds, cartId, MainActivity::class.java) {
            navigateToMainTab(MainActivity.Tab.CART)
        }
    }
    findViewById<View?>(profileId)?.setOnClickListener {
        animateBottomNavAndNavigate(navIds, profileId, MainActivity::class.java) {
            navigateToMainTab(MainActivity.Tab.PROFILE)
        }
    }
}

fun AppCompatActivity.applyBottomNavSelection(
    @IdRes selectedId: Int,
    @IdRes homeId: Int,
    @IdRes exploreId: Int,
    @IdRes favoritesId: Int? = null,
    @IdRes cartId: Int,
    @IdRes profileId: Int
) {
    val navIds = listOfNotNull(homeId, exploreId, favoritesId, cartId, profileId)
    val activeColor = ContextCompat.getColor(this, R.color.profile_nav_active)
    val inactiveColor = ContextCompat.getColor(this, R.color.profile_nav_inactive)
    navIds.forEach { id ->
        setNavItemColorImmediate(id, if (id == selectedId) activeColor else inactiveColor)
    }
}

private fun AppCompatActivity.animateBottomNavAndNavigate(
    navIds: List<Int>,
    selectedId: Int,
    target: Class<out Activity>,
    navigateOverride: (() -> Unit)? = null
) {
    if (this::class.java == target) return

    val activeColor = ContextCompat.getColor(this, R.color.profile_nav_active)
    val inactiveColor = ContextCompat.getColor(this, R.color.profile_nav_inactive)

    navIds.forEach { id ->
        val item = findViewById<View?>(id) ?: return@forEach
        animateNavItemColor(item, if (id == selectedId) activeColor else inactiveColor)
    }

    findViewById<View?>(selectedId)?.postDelayed({
        runCatching {
            navigateOverride?.invoke() ?: navigateNoShift(target)
        }.onFailure { error ->
            Log.e(TAG_NAV, "Bottom nav navigation failed for target=${target.simpleName}", error)
            showToast(getString(R.string.coming_soon))
        }
    }, 90L)
}

private fun AppCompatActivity.setNavItemColorImmediate(@IdRes itemId: Int, color: Int) {
    val item = findViewById<View?>(itemId) as? LinearLayout ?: return
    val firstChild = item.getChildAt(0)
    val icon = when (firstChild) {
        is ImageView -> firstChild
        is android.view.ViewGroup -> {
            (0 until firstChild.childCount)
                .mapNotNull { idx -> firstChild.getChildAt(idx) as? ImageView }
                .firstOrNull()
        }
        else -> null
    }
    val label = item.getChildAt(1) as? TextView
    label?.setTextColor(color)
    icon?.setColorFilter(color)
}

// Cette fonction fait une action de cette partie de l'app.
private fun animateNavItemColor(item: View, toColor: Int) {
    val navItem = item as? LinearLayout ?: return
    val firstChild = navItem.getChildAt(0)
    val icon = when (firstChild) {
        is ImageView -> firstChild
        is android.view.ViewGroup -> {
            (0 until firstChild.childCount)
                .mapNotNull { idx -> firstChild.getChildAt(idx) as? ImageView }
                .firstOrNull()
        }
        else -> null
    }
    val label = navItem.getChildAt(1) as? TextView
    val fromColor = label?.currentTextColor ?: toColor
    if (fromColor == toColor) return

    ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor).apply {
        duration = 120L
        addUpdateListener { animator ->
            val color = animator.animatedValue as Int
            label?.setTextColor(color)
            icon?.setColorFilter(color)
        }
        start()
    }
}

fun AppCompatActivity.applyPressFeedback(
    vararg ids: Int,
    pressedScale: Float = 0.97f
) {
    val reducedMotion = isReducedMotionEnabled()
    ids.forEach { viewId ->
        val view = findViewById<View?>(viewId) ?: return@forEach
        view.isClickable = true
        view.isFocusable = true
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.performLightHapticFeedback()
                    if (reducedMotion) {
                        view.alpha = 0.92f
                    } else {
                        view.animate()
                            .scaleX(pressedScale)
                            .scaleY(pressedScale)
                            .alpha(0.94f)
                            .setDuration(90L)
                            .setInterpolator(AccelerateDecelerateInterpolator())
                            .start()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (reducedMotion) {
                        view.alpha = 1f
                    } else {
                        view.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(140L)
                            .setInterpolator(FastOutSlowInInterpolator())
                            .start()
                    }
                }
            }
            false
        }
    }
}

fun AppCompatActivity.animateExploreEntrance(
    @IdRes topSectionId: Int,
    @IdRes scrollId: Int,
    @IdRes bottomNavId: Int,
    vararg cardIds: Int
) {
    if (isReducedMotionEnabled()) return

    val topSection = findViewById<View?>(topSectionId)
    val scroll = findViewById<View?>(scrollId)
    val bottomNav = findViewById<View?>(bottomNavId)
    val cards = cardIds.map { findViewById<View?>(it) }.filterNotNull()

    val interpolator = FastOutSlowInInterpolator()

    topSection?.apply {
        alpha = 0f
        translationY = -22f
        animate().alpha(1f).translationY(0f).setDuration(260L).setInterpolator(interpolator).start()
    }

    scroll?.apply {
        alpha = 0f
        translationY = 30f
        animate().alpha(1f).translationY(0f).setStartDelay(70L).setDuration(320L)
            .setInterpolator(interpolator).start()
    }

    bottomNav?.apply {
        alpha = 0f
        translationY = 26f
        animate().alpha(1f).translationY(0f).setStartDelay(110L).setDuration(260L)
            .setInterpolator(interpolator).start()
    }

    cards.forEachIndexed { index, card ->
        card.alpha = 0f
        card.translationY = 20f
        card.animate().alpha(1f).translationY(0f)
            .setStartDelay(90L + (index * 35L))
            .setDuration(250L)
            .setInterpolator(interpolator)
            .start()
    }
}

fun AppCompatActivity.revealViewsInOrder(
    vararg ids: Int,
    fromTranslationDp: Float = 18f,
    startDelayMs: Long = 0L,
    staggerMs: Long = 42L,
    durationMs: Long = MotionTokens.STANDARD
) {
    if (isReducedMotionEnabled()) return

    val distance = fromTranslationDp * resources.displayMetrics.density
    val interpolator = FastOutSlowInInterpolator()
    ids.forEachIndexed { index, id ->
        val view = findViewById<View?>(id) ?: return@forEachIndexed
        view.alpha = 0f
        view.translationY = distance
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(startDelayMs + (index * staggerMs))
            .setDuration(durationMs)
            .setInterpolator(interpolator)
            .start()
    }
}

fun AppCompatActivity.revealSingleView(
    @IdRes id: Int,
    fromTranslationDp: Float = 14f,
    durationMs: Long = MotionTokens.STANDARD
) {
    if (isReducedMotionEnabled()) return

    val view = findViewById<View?>(id) ?: return
    val distance = fromTranslationDp * resources.displayMetrics.density
    view.alpha = 0f
    view.translationY = distance
    view.animate()
        .alpha(1f)
        .translationY(0f)
        .setDuration(durationMs)
        .setInterpolator(FastOutSlowInInterpolator())
        .start()
}

fun AppCompatActivity.animateListItemEntry(
    item: View,
    index: Int,
    startDelayMs: Long = 70L
) {
    if (isReducedMotionEnabled()) return

    item.alpha = 0f
    item.translationY = 16f * resources.displayMetrics.density
    item.animate()
        .alpha(1f)
        .translationY(0f)
        .setStartDelay(startDelayMs + (index * 26L))
        .setDuration(MotionTokens.STANDARD)
        .setInterpolator(FastOutSlowInInterpolator())
        .start()
}

// Cette classe organise cette partie de l'app.
enum class InputFieldState {
    NEUTRAL,
    FOCUSED,
    SUCCESS,
    ERROR
}

fun AppCompatActivity.bindInputFieldMotion(
    @IdRes cardId: Int,
    @IdRes inputId: Int,
    validator: (String) -> Boolean = { it.isNotBlank() }
) {
    val card = findViewById<MaterialCardView?>(cardId) ?: return
    val input = findViewById<EditText?>(inputId) ?: return

    val updateByFocus: () -> Unit = {
        if (input.hasFocus()) {
            animateInputState(card, InputFieldState.FOCUSED)
        } else if (validator(input.text?.toString().orEmpty())) {
            animateInputState(card, InputFieldState.SUCCESS)
        } else {
            animateInputState(card, InputFieldState.NEUTRAL)
        }
    }

    input.setOnFocusChangeListener { _, _ -> updateByFocus() }
    input.addTextChangedListener(object : TextWatcher {
        // Cette fonction fait une action de cette partie de l'app.
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        // Cette fonction fait une action de cette partie de l'app.
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        // Cette fonction fait une action de cette partie de l'app.
        override fun afterTextChanged(s: Editable?) {
            if (!input.hasFocus()) updateByFocus()
        }
    })
}

fun AppCompatActivity.markInputState(@IdRes cardId: Int, state: InputFieldState) {
    val card = findViewById<MaterialCardView?>(cardId) ?: return
    animateInputState(card, state)
}

private fun AppCompatActivity.animateInputState(card: MaterialCardView, state: InputFieldState) {
    val targetStroke = when (state) {
        InputFieldState.NEUTRAL -> ContextCompat.getColor(this, R.color.colorOutline)
        InputFieldState.FOCUSED -> ContextCompat.getColor(this, R.color.colorPrimary)
        InputFieldState.SUCCESS -> ContextCompat.getColor(this, R.color.colorPrimary)
        InputFieldState.ERROR -> ContextCompat.getColor(this, R.color.colorError)
    }

    if (isReducedMotionEnabled()) {
        card.strokeColor = targetStroke
        card.cardElevation = if (state == InputFieldState.FOCUSED) 6f else 0f
        return
    }

    ValueAnimator.ofObject(ArgbEvaluator(), card.strokeColor, targetStroke).apply {
        duration = MotionTokens.QUICK
        addUpdateListener { animator ->
            card.strokeColor = animator.animatedValue as Int
        }
        start()
    }

    val lift = if (state == InputFieldState.FOCUSED) -2f * resources.displayMetrics.density else 0f
    card.animate()
        .translationY(lift)
        .setDuration(MotionTokens.QUICK)
        .setInterpolator(FastOutSlowInInterpolator())
        .start()
}

fun AppCompatActivity.emphasizeCta(@IdRes id: Int, delayMs: Long = 260L) {
    if (isReducedMotionEnabled()) return
    val view = findViewById<View?>(id) ?: return
    view.postDelayed({
        view.animate()
            .scaleX(1.02f)
            .scaleY(1.02f)
            .setDuration(160L)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(190L)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .start()
            }
            .start()
    }, delayMs)
}

fun AppCompatActivity.showMotionSnackbar(message: String, @IdRes anchorId: Int? = null) {
    val root = findViewById<View>(android.R.id.content)
    val snackbar = Snackbar.make(root, message, Snackbar.LENGTH_SHORT)
    anchorId?.let { snackbar.anchorView = findViewById(it) }
    snackbar.show()

    if (!isReducedMotionEnabled()) {
        snackbar.view.alpha = 0f
        snackbar.view.translationY = 20f * resources.displayMetrics.density
        snackbar.view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(MotionTokens.EMPHASIS)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }
}

fun AppCompatActivity.updateBottomCartBadge(
    @IdRes badgeContainerId: Int = R.id.cardBottomCartBadge,
    @IdRes badgeTextId: Int = R.id.tvBottomCartBadge
) {
    val badgeContainer = findViewById<View?>(badgeContainerId) ?: return
    val badgeText = findViewById<TextView?>(badgeTextId) ?: return
    val count = CartStore.itemCount(this)
    if (count <= 0) {
        badgeContainer.visibility = View.GONE
        return
    }

    badgeContainer.visibility = View.VISIBLE
    badgeText.text = count.toString()
}
