package isim.ia2y.myapplication

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.annotation.IdRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.card.MaterialCardView

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
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
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
        InputFieldState.NEUTRAL -> ContextCompat.getColor(this, R.color.ms_border_default)
        InputFieldState.FOCUSED -> ContextCompat.getColor(this, R.color.ms_surface_inverse)
        InputFieldState.SUCCESS -> ContextCompat.getColor(this, R.color.ms_surface_inverse)
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
