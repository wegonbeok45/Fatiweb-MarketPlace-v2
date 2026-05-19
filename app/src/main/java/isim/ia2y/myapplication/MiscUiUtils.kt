package isim.ia2y.myapplication

import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import java.util.Locale

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

    fun updateOutput(content: String) {
        if (isEditText) (textView as EditText).hint = content else textView.text = content
    }

    val typer = object : Runnable {
        override fun run() {
            if (cancelled || !textView.isAttachedToWindow) return
            index += 1
            updateOutput(fullText.substring(0, index.coerceAtMost(fullText.length)))
            if (index < fullText.length) {
                handler.postDelayed(this, stepDelayMs)
            }
        }
    }

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

fun View.applyStatusBarInset() {
    val initialTopPadding = paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
        view.updatePadding(top = initialTopPadding + statusBars.top)
        insets
    }
    post { ViewCompat.requestApplyInsets(this) }
}

fun formatDt(price: Double): String {
    return formatMinorDt(toMinorUnits(price))
}

fun toMinorUnits(price: Double): Long {
    return kotlin.math.round(price.coerceAtLeast(0.0) * 1000.0).toLong()
}

fun fromMinorUnits(priceMinor: Long): Double {
    return priceMinor.coerceAtLeast(0L) / 1000.0
}

fun formatMinorDt(priceMinor: Long): String {
    val locale = java.util.Locale.forLanguageTag("fr-TN")
    val symbols = java.text.DecimalFormatSymbols.getInstance(locale)
    val formatter = java.text.DecimalFormat("#,##0.###", symbols)
    return "${formatter.format(fromMinorUnits(priceMinor))} DT"
}

fun formatOrigin(origin: String): String {
    return origin.replace('_', ' ')
        .replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
}

fun productCardRatingText(product: Product): String {
    return if (product.rating > 0.0 && product.reviewsCount > 0) {
        String.format(Locale.US, "%.1f (%d)", product.rating, product.reviewsCount)
    } else {
        product.reviewsCount.takeIf { it > 0 }?.let { "0.0 ($it)" } ?: "Aucun avis"
    }
}

fun productCardCategoryLabel(product: Product): String {
    val key = MarketplaceCategories.categoryFor(product.category)?.topLevelId ?: product.category
    return MarketplaceCategories.displayNameFor(key)
}

fun TextView.bindProductCardStock(product: Product) {
    val (labelRes, bgRes, textRes) = when {
        !product.isActive -> Triple(
            R.string.home_ref_stock_hidden,
            R.color.home_ref_surface_alt,
            R.color.home_ref_text_secondary
        )
        product.stock <= 0 -> Triple(
            R.string.home_ref_stock_out,
            R.color.home_ref_surface_alt,
            R.color.home_ref_text_secondary
        )
        product.stock in 1..5 -> Triple(
            R.string.home_ref_stock_low,
            R.color.home_ref_warning_bg,
            R.color.home_ref_warning_text
        )
        else -> Triple(
            R.string.home_ref_stock_in,
            R.color.home_ref_success_bg,
            R.color.home_ref_success_text
        )
    }
    visibility = View.VISIBLE
    text = context.getString(labelRes)
    backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, bgRes))
    setTextColor(ContextCompat.getColor(context, textRes))
}
