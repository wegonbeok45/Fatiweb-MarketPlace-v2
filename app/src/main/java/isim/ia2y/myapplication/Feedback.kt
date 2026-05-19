package isim.ia2y.myapplication

import android.content.Context
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.snackbar.Snackbar

enum class FeedbackType {
    SUCCESS, ERROR, WARNING, INFO
}

private data class FeedbackStyle(
    val bgColorRes: Int,
    val textColorRes: Int,
    @DrawableRes val iconRes: Int
)

private fun FeedbackType.style(): FeedbackStyle = when (this) {
    FeedbackType.SUCCESS -> FeedbackStyle(R.color.feedback_success_bg, R.color.feedback_success_text, R.drawable.ic_feedback_check)
    FeedbackType.ERROR -> FeedbackStyle(R.color.feedback_error_bg, R.color.feedback_error_text, R.drawable.ic_feedback_error)
    FeedbackType.WARNING -> FeedbackStyle(R.color.feedback_warning_bg, R.color.feedback_warning_text, R.drawable.ic_feedback_warning)
    FeedbackType.INFO -> FeedbackStyle(R.color.feedback_info_bg, R.color.feedback_info_text, R.drawable.ic_feedback_info)
}

private fun Snackbar.applyFeedbackStyle(context: Context, type: FeedbackType) {
    val style = type.style()
    val bg = ContextCompat.getColor(context, style.bgColorRes)
    val textColor = ContextCompat.getColor(context, style.textColorRes)
    val density = context.resources.displayMetrics.density

    view.background = GradientDrawable().apply {
        cornerRadius = 14f * density
        setColor(bg)
    }
    val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
    if (lp != null) {
        val horiz = (12 * density).toInt()
        val vert = (12 * density).toInt()
        lp.leftMargin = horiz
        lp.rightMargin = horiz
        lp.bottomMargin = vert
        view.layoutParams = lp
    }
    view.elevation = 6f * density

    val tv = view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text) ?: return
    tv.setTextColor(textColor)
    tv.textSize = 13f
    tv.maxLines = 4
    tv.gravity = Gravity.CENTER_VERTICAL
    val icon = ContextCompat.getDrawable(context, style.iconRes)?.mutate()
    icon?.colorFilter = PorterDuffColorFilter(textColor, PorterDuff.Mode.SRC_IN)
    tv.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
    tv.compoundDrawablePadding = (10 * density).toInt()
}

fun AppCompatActivity.showFeedback(
    message: String,
    type: FeedbackType = FeedbackType.INFO,
    @IdRes anchorId: Int? = null
) {
    if (message.isBlank()) return
    val root = findViewById<View>(android.R.id.content) ?: return
    val duration = if (type == FeedbackType.ERROR) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT
    val snackbar = Snackbar.make(root, message, duration)
    anchorId?.let { snackbar.anchorView = findViewById(it) }
    snackbar.applyFeedbackStyle(this, type)
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

fun AppCompatActivity.showSuccess(message: String, @IdRes anchorId: Int? = null) =
    showFeedback(message, FeedbackType.SUCCESS, anchorId)

fun AppCompatActivity.showError(message: String, @IdRes anchorId: Int? = null) =
    showFeedback(message, FeedbackType.ERROR, anchorId)

fun AppCompatActivity.showWarning(message: String, @IdRes anchorId: Int? = null) =
    showFeedback(message, FeedbackType.WARNING, anchorId)

fun AppCompatActivity.showInfo(message: String, @IdRes anchorId: Int? = null) =
    showFeedback(message, FeedbackType.INFO, anchorId)

fun Fragment.showSuccess(message: String) {
    (activity as? AppCompatActivity)?.showSuccess(message)
}

fun Fragment.showError(message: String) {
    (activity as? AppCompatActivity)?.showError(message)
}

fun Fragment.showWarning(message: String) {
    (activity as? AppCompatActivity)?.showWarning(message)
}

fun Fragment.showInfo(message: String) {
    (activity as? AppCompatActivity)?.showInfo(message)
}
