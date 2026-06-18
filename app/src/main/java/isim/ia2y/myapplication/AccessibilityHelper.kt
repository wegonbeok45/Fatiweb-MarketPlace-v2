package isim.ia2y.myapplication

import android.app.Activity
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import isim.ia2y.myapplication.voice.FatiVoiceService

object AccessibilityHelper {

    private const val MIN_TEXT_SIZE_SP = 18f

    fun enableAccessibilityMode(activity: Activity) {
        val root = activity.window?.decorView?.rootView ?: return
        applyMinTextSize(root)
    }

    fun disableAccessibilityMode(activity: Activity) {
        // No-op for now; restoring original sizes would require caching.
    }

    private fun applyMinTextSize(view: View?) {
        if (view == null) return
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) applyMinTextSize(view.getChildAt(i))
            return
        }
        if (view is TextView) {
            val metrics = view.resources.displayMetrics
            val currentSp = view.textSize / metrics.scaledDensity
            if (currentSp < MIN_TEXT_SIZE_SP) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, MIN_TEXT_SIZE_SP)
            }
        }
    }

    fun audioDescription(service: FatiVoiceService, text: String, onDone: (() -> Unit)? = null) {
        service.speak(text) { onDone?.invoke() }
    }
}
