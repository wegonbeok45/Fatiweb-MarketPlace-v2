package isim.ia2y.myapplication.voice

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

class FatiVoiceWaveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val path = Path()
    private var phase = 0f
    private var amplitude = 0.16f
    private var speed = 1600L
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = speed
        repeatMode = ValueAnimator.RESTART
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener {
            phase = it.animatedValue as Float * (2f * PI.toFloat())
            invalidate()
        }
    }

    init {
        animator.start()
    }

    fun setWaveSpeed(durationMs: Long) {
        if (durationMs != speed) {
            speed = durationMs
            animator.duration = speed
            if (animator.isStarted) {
                animator.cancel()
                animator.start()
            }
        }
    }

    fun setWaveAmplitude(scale: Float) {
        amplitude = scale.coerceIn(0.05f, 0.28f)
        invalidate()
    }

    fun startWave() {
        if (!animator.isStarted) {
            animator.start()
        }
    }

    fun stopWave() {
        animator.cancel()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val widthF = width.toFloat().coerceAtLeast(1f)
        val heightF = height.toFloat().coerceAtLeast(1f)

        paint.shader = LinearGradient(
            0f,
            0f,
            widthF,
            heightF,
            intArrayOf(0xFFC9A272.toInt(), 0xFF8B6226.toInt()),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        path.reset()
        path.moveTo(0f, heightF)

        val waveHeight = heightF * 0.35f
        val frequency = 2.2f

        var x = 0f
        val step = 12
        while (x <= widthF) {
            val y = heightF - waveHeight - (sin((x / widthF) * frequency * 2f * PI.toFloat() + phase) * waveHeight * amplitude)
            path.lineTo(x, y)
            x += step
        }

        path.lineTo(widthF, heightF)
        path.close()
        canvas.drawPath(path, paint)
    }
}
