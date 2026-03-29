package isim.ia2y.myapplication

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
    load(imageUrl?.takeIf { it.isNotBlank() } ?: fallbackRes) {
        crossfade(180)
        placeholder(R.drawable.placeholder)
        error(fallbackRes)
    }
}
