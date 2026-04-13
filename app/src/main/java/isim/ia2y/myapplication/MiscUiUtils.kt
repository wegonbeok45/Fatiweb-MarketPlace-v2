package isim.ia2y.myapplication

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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
        val breathingRoom = (4 * resources.displayMetrics.density).toInt()
        view.setPadding(
            view.paddingLeft,
            initialTopPadding + breathingRoom,
            view.paddingRight,
            view.paddingBottom
        )
        insets
    }
    post { ViewCompat.requestApplyInsets(this) }
}

fun formatDt(price: Double): String {
    return String.format(java.util.Locale("fr", "TN"), "%.3f DT", price)
}

fun formatOrigin(origin: String): String {
    return origin.replace('_', ' ')
        .replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
        }
}
