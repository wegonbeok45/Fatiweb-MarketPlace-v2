package isim.ia2y.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.Locale

object GeneratedProductArt {
    private data class Palette(
        val backgroundTop: Int,
        val backgroundBottom: Int,
        val glow: Int,
        val surface: Int,
        val surfaceBorder: Int,
        val accent: Int,
        val accentMuted: Int,
        val textPrimary: Int,
        val textSecondary: Int
    )

    fun buildDataUrl(
        context: Context,
        title: String,
        category: String,
        sizePx: Int = 640
    ): String {
        val palette = paletteFor(category)
        val safeTitle = title.trim().ifBlank {
            context.getString(R.string.admin_product_editor_title_add)
        }
        val categoryLabel = categoryLabel(context, category)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val density = context.resources.displayMetrics.density
        val corner = 36f * density
        val outer = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        val size = sizePx.toFloat()

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f,
                0f,
                sizePx.toFloat(),
                sizePx.toFloat(),
                palette.backgroundTop,
                palette.backgroundBottom,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(outer, corner, corner, backgroundPaint)

        val topGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                size * 0.85f,
                size * 0.14f,
                size * 0.34f,
                withAlpha(palette.glow, 88),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(size * 0.85f, size * 0.14f, size * 0.34f, topGlowPaint)

        val bottomGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                size * 0.16f,
                size * 0.84f,
                size * 0.28f,
                withAlpha(palette.accentMuted, 72),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(size * 0.16f, size * 0.84f, size * 0.28f, bottomGlowPaint)

        val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(Color.WHITE, 56)
        }
        val bandHeight = size * 0.17f
        canvas.drawRoundRect(
            RectF(-size * 0.08f, size * 0.14f, size * 1.08f, size * 0.14f + bandHeight),
            bandHeight / 2f,
            bandHeight / 2f,
            bandPaint
        )

        val cardInset = 48f * density
        val cardRect = RectF(
            cardInset,
            size * 0.16f,
            size - cardInset,
            size * 0.87f
        )
        val shadowRect = RectF(cardRect).apply { offset(0f, 14f * density) }
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(Color.BLACK, 22)
        }
        canvas.drawRoundRect(shadowRect, 30f * density, 30f * density, shadowPaint)

        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.surface
        }
        canvas.drawRoundRect(cardRect, 28f * density, 28f * density, cardPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.surfaceBorder
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        canvas.drawRoundRect(cardRect, 28f * density, 28f * density, borderPaint)

        val chipHeight = 42f * density
        val chipWidth = kotlin.math.min(cardRect.width() * 0.58f, 216f * density)
        val chipRect = RectF(
            cardRect.left + 26f * density,
            cardRect.top + 24f * density,
            cardRect.left + 26f * density + chipWidth,
            cardRect.top + 24f * density + chipHeight
        )
        val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.accent, 232)
        }
        canvas.drawRoundRect(chipRect, chipHeight / 2f, chipHeight / 2f, chipPaint)

        val chipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16f * density
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            letterSpacing = 0f
        }
        drawCenteredText(
            canvas = canvas,
            text = categoryLabel.uppercase(Locale.getDefault()),
            centerX = chipRect.centerX(),
            baselineY = chipRect.centerY() - centeredTextOffset(chipTextPaint),
            paint = chipTextPaint
        )

        val mediaRect = RectF(
            cardRect.left + 34f * density,
            chipRect.bottom + 28f * density,
            cardRect.right - 34f * density,
            cardRect.top + cardRect.height() * 0.66f
        )
        val mediaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                mediaRect.left,
                mediaRect.top,
                mediaRect.right,
                mediaRect.bottom,
                withAlpha(Color.WHITE, 220),
                withAlpha(palette.accentMuted, 48),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRoundRect(mediaRect, 26f * density, 26f * density, mediaPaint)

        val mediaBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.surfaceBorder, 220)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        canvas.drawRoundRect(mediaRect, 26f * density, 26f * density, mediaBorderPaint)

        drawPlaceholderGlyph(
            canvas = canvas,
            rect = mediaRect,
            palette = palette,
            density = density
        )

        val helperRect = RectF(
            mediaRect.left,
            mediaRect.bottom + 20f * density,
            mediaRect.left + 146f * density,
            mediaRect.bottom + 56f * density
        )
        val helperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.accentMuted, 92)
        }
        canvas.drawRoundRect(helperRect, 18f * density, 18f * density, helperPaint)

        val helperTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textPrimary
            textSize = 13f * density
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        drawCenteredText(
            canvas = canvas,
            text = "DEFAULT IMAGE",
            centerX = helperRect.centerX(),
            baselineY = helperRect.centerY() - centeredTextOffset(helperTextPaint),
            paint = helperTextPaint
        )

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textPrimary
            textSize = 26f * density
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            truncateMiddle(safeTitle, 24),
            cardRect.centerX(),
            cardRect.bottom - 58f * density,
            titlePaint
        )

        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.textSecondary
            textSize = 15f * density
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            context.getString(R.string.generated_product_art_caption),
            cardRect.centerX(),
            cardRect.bottom - 26f * density,
            subtitlePaint
        )

        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val encoded = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        return "data:image/png;base64,$encoded"
    }

    private fun paletteFor(category: String): Palette {
        val accent = when (category.trim().lowercase(Locale.getDefault())) {
            "food-and-grocery" -> Color.parseColor("#5F5A56")
            "beauty-and-health" -> Color.parseColor("#5A636B")
            "fashion" -> Color.parseColor("#525A66")
            "home-and-furniture", "real-estate" -> Color.parseColor("#626A72")
            "electronics", "digital-products" -> Color.parseColor("#4D5D67")
            else -> Color.parseColor("#67615D")
        }
        val accentMuted = when (category.trim().lowercase(Locale.getDefault())) {
            "food-and-grocery" -> Color.parseColor("#C8C3BE")
            "beauty-and-health" -> Color.parseColor("#C5CCD2")
            "fashion" -> Color.parseColor("#C3C8D0")
            "home-and-furniture", "real-estate" -> Color.parseColor("#CBD1D5")
            "electronics", "digital-products" -> Color.parseColor("#C1CBD1")
            else -> Color.parseColor("#CEC8C3")
        }
        return Palette(
            backgroundTop = Color.parseColor("#F4F4F5"),
            backgroundBottom = Color.parseColor("#D6D7DA"),
            glow = Color.parseColor("#FFFFFF"),
            surface = Color.parseColor("#FDFDFD"),
            surfaceBorder = Color.parseColor("#D7D9DD"),
            accent = accent,
            accentMuted = accentMuted,
            textPrimary = Color.parseColor("#2F3237"),
            textSecondary = Color.parseColor("#7A8088")
        )
    }

    private fun categoryLabel(context: Context, category: String): String =
        MarketplaceCategories.displayNameFor(category)

    private fun truncateMiddle(value: String, maxLength: Int): String {
        if (value.length <= maxLength) return value
        val left = (maxLength - 1) / 2
        val right = maxLength - left - 1
        return value.take(left) + "..." + value.takeLast(right)
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baselineY: Float,
        paint: Paint
    ) {
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(text, centerX, baselineY, paint)
    }

    private fun centeredTextOffset(paint: Paint): Float {
        val metrics = paint.fontMetrics
        return (metrics.ascent + metrics.descent) / 2f
    }

    private fun drawPlaceholderGlyph(
        canvas: Canvas,
        rect: RectF,
        palette: Palette,
        density: Float
    ) {
        val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.accentMuted, 80)
        }
        canvas.drawCircle(rect.centerX(), rect.centerY(), rect.width() * 0.24f, haloPaint)

        val frameRect = RectF(
            rect.left + rect.width() * 0.16f,
            rect.top + rect.height() * 0.18f,
            rect.right - rect.width() * 0.16f,
            rect.bottom - rect.height() * 0.2f
        )
        val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(Color.WHITE, 128)
        }
        canvas.drawRoundRect(frameRect, 22f * density, 22f * density, framePaint)

        val frameBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.accent, 168)
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
        }
        canvas.drawRoundRect(frameRect, 22f * density, 22f * density, frameBorderPaint)

        val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.accent, 184)
        }
        canvas.drawCircle(
            frameRect.right - frameRect.width() * 0.18f,
            frameRect.top + frameRect.height() * 0.2f,
            13f * density,
            sunPaint
        )

        val mountainBack = Path().apply {
            moveTo(frameRect.left + frameRect.width() * 0.08f, frameRect.bottom - frameRect.height() * 0.18f)
            lineTo(frameRect.left + frameRect.width() * 0.34f, frameRect.top + frameRect.height() * 0.46f)
            lineTo(frameRect.left + frameRect.width() * 0.54f, frameRect.bottom - frameRect.height() * 0.18f)
            close()
        }
        val mountainBackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.accentMuted, 180)
        }
        canvas.drawPath(mountainBack, mountainBackPaint)

        val mountainFront = Path().apply {
            moveTo(frameRect.left + frameRect.width() * 0.22f, frameRect.bottom - frameRect.height() * 0.12f)
            lineTo(frameRect.left + frameRect.width() * 0.48f, frameRect.top + frameRect.height() * 0.34f)
            lineTo(frameRect.right - frameRect.width() * 0.08f, frameRect.bottom - frameRect.height() * 0.12f)
            close()
        }
        val mountainFrontPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.accent, 208)
        }
        canvas.drawPath(mountainFront, mountainFrontPaint)

        val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = withAlpha(palette.accent, 128)
            strokeWidth = 3f * density
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawLine(
            frameRect.left + frameRect.width() * 0.12f,
            frameRect.bottom - frameRect.height() * 0.08f,
            frameRect.right - frameRect.width() * 0.12f,
            frameRect.bottom - frameRect.height() * 0.08f,
            basePaint
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
    }
}
