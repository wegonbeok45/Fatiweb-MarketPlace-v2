package isim.ia2y.myapplication

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@ColorInt
fun Context.messagingColor(@ColorRes colorRes: Int): Int = ContextCompat.getColor(this, colorRes)

fun roundedRect(
    @ColorInt fillColor: Int,
    radiusDp: Int,
    @ColorInt strokeColor: Int? = null,
    strokeWidthDp: Int = 1
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    cornerRadius = radiusDp.dp.toFloat()
    setColor(fillColor)
    if (strokeColor != null) {
        setStroke(strokeWidthDp.dp, strokeColor)
    }
}

fun ovalShape(
    @ColorInt fillColor: Int,
    @ColorInt strokeColor: Int? = null,
    strokeWidthDp: Int = 1
): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(fillColor)
    if (strokeColor != null) {
        setStroke(strokeWidthDp.dp, strokeColor)
    }
}

fun View.applyRoundedBackground(
    @ColorInt fillColor: Int,
    radiusDp: Int,
    @ColorInt strokeColor: Int? = null,
    strokeWidthDp: Int = 1
) {
    background = roundedRect(fillColor, radiusDp, strokeColor, strokeWidthDp)
}

fun TextView.setDtHighlightedText(
    rawText: String,
    @ColorInt textColor: Int,
    @ColorInt amountColor: Int
) {
    setTextColor(textColor)
    val span = SpannableString(rawText)
    dtAmountRegex.findAll(rawText).forEach { match ->
        span.setSpan(
            ForegroundColorSpan(amountColor),
            match.range.first,
            match.range.last + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        span.setSpan(
            StyleSpan(Typeface.BOLD),
            match.range.first,
            match.range.last + 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }
    text = span
}

fun formatConversationClock(millis: Long): String =
    if (millis <= 0L) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

fun formatConversationDay(millis: Long): String {
    if (millis <= 0L) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    return when {
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> "Today"
        isYesterday(now, then) -> "Yesterday"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(millis))
    }
}

private fun isYesterday(now: Calendar, then: Calendar): Boolean {
    val yesterday = now.clone() as Calendar
    yesterday.add(Calendar.DAY_OF_YEAR, -1)
    return yesterday.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
}

private val dtAmountRegex = Regex("(?i)(?<![A-Z0-9])\\d[\\d\\s.,]*\\s*DT(?![A-Z0-9])")
