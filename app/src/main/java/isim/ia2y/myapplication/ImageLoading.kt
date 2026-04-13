package isim.ia2y.myapplication

import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.ImageView
import androidx.annotation.DrawableRes
import coil.load

fun ImageView.loadCatalogImage(@DrawableRes imageRes: Int) {
    load(imageRes) {
        crossfade(180)
        placeholder(R.drawable.placeholder)
        error(R.drawable.placeholder)
    }
}

fun ImageView.loadCatalogImage(imageUrl: String?, @DrawableRes fallbackRes: Int) {
    val safeFallback = fallbackRes.takeIf { it != 0 } ?: R.drawable.placeholder
    val value = imageUrl?.takeIf { it.isNotBlank() }

    decodeInlineImage(value)?.let { bitmap ->
        setImageBitmap(bitmap)
        return
    }

    val model = value ?: safeFallback

    load(model) {
        crossfade(180)
        placeholder(R.drawable.placeholder)
        error(safeFallback)
    }
}

private fun decodeInlineImage(imageUrl: String?): android.graphics.Bitmap? {
    if (imageUrl.isNullOrBlank() || !imageUrl.startsWith("data:image/", ignoreCase = true)) {
        return null
    }

    val encodedPart = imageUrl.substringAfter("base64,", "")
    if (encodedPart.isBlank()) return null

    return runCatching {
        val bytes = Base64.decode(encodedPart, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }.getOrNull()
}
