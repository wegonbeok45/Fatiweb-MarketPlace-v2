package isim.ia2y.myapplication

import android.widget.ImageView
import androidx.annotation.DrawableRes
import coil.load
import coil.request.CachePolicy
import coil.size.Dimension
import coil.size.Scale

fun ImageView.loadCatalogImage(@DrawableRes imageRes: Int) {
    load(imageRes) {
        placeholder(R.drawable.placeholder)
        error(R.drawable.placeholder)
        memoryCachePolicy(CachePolicy.ENABLED)
        diskCachePolicy(CachePolicy.ENABLED)
        scale(Scale.FILL)
    }
}

fun ImageView.loadCatalogImage(
    imageUrl: String?,
    @DrawableRes fallbackRes: Int,
    requestedSizePx: Int = 300,
    crossfadeMillis: Int = 0
) {
    val safeFallback = fallbackRes.takeIf { it != 0 } ?: R.drawable.placeholder
    val value = imageUrl
        ?.trim()
        ?.takeIf { url ->
            url.startsWith("https://", ignoreCase = true) ||
                url.startsWith("http://", ignoreCase = true) ||
                (BuildConfig.DEBUG && url.startsWith("/"))
        }

    val model = value ?: safeFallback
    load(model) {
        if (crossfadeMillis > 0) {
            crossfade(crossfadeMillis)
        }
        placeholder(R.drawable.placeholder)
        error(safeFallback)
        memoryCachePolicy(CachePolicy.ENABLED)
        diskCachePolicy(CachePolicy.ENABLED)
        scale(Scale.FILL)
        size(Dimension(requestedSizePx), Dimension(requestedSizePx))
    }
}

fun ImageView.loadCatalogImageFit(
    imageUrl: String?,
    @DrawableRes fallbackRes: Int,
    requestedSizePx: Int = 600,
    crossfadeMillis: Int = 0
) {
    val safeFallback = fallbackRes.takeIf { it != 0 } ?: R.drawable.placeholder
    val value = imageUrl
        ?.trim()
        ?.takeIf { url ->
            url.startsWith("https://", ignoreCase = true) ||
                url.startsWith("http://", ignoreCase = true) ||
                (BuildConfig.DEBUG && url.startsWith("/"))
        }

    load(value ?: safeFallback) {
        if (crossfadeMillis > 0) {
            crossfade(crossfadeMillis)
        }
        placeholder(R.drawable.placeholder)
        error(safeFallback)
        memoryCachePolicy(CachePolicy.ENABLED)
        diskCachePolicy(CachePolicy.ENABLED)
        scale(Scale.FIT)
        size(Dimension(requestedSizePx), Dimension(requestedSizePx))
    }
}

fun ImageView.loadAvatarImage(
    imageUrl: String?,
    requestedSizePx: Int = 160
) {
    loadCatalogImage(imageUrl, R.drawable.profile_avatar_art, requestedSizePx)
}

object IdentityResolver {
    fun displayName(name: String?, email: String?, fallback: String = "Client"): String {
        return name
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: email?.substringBefore("@")?.takeIf { it.isNotBlank() }
            ?: fallback
    }

    fun avatarUrl(vararg candidates: String?): String? {
        return candidates.firstOrNull { value ->
            val trimmed = value?.trim().orEmpty()
            trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("http://", ignoreCase = true) ||
                (BuildConfig.DEBUG && trimmed.startsWith("/"))
        }?.trim()
    }
}
