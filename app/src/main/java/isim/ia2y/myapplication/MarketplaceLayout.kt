package isim.ia2y.myapplication

import android.content.Context
import kotlin.math.max
import kotlin.math.min

enum class ScreenWidthClass {
    COMPACT,
    MEDIUM,
    EXPANDED
}

fun Context.screenWidthClass(): ScreenWidthClass {
    val widthDp = resources.configuration.screenWidthDp
    return when {
        widthDp >= 840 -> ScreenWidthClass.EXPANDED
        widthDp >= 600 -> ScreenWidthClass.MEDIUM
        else -> ScreenWidthClass.COMPACT
    }
}

fun Context.marketplaceGridSpanCount(
    minCardWidthDp: Int = 184,
    compactMinCardWidthDp: Int = 220,
    maxSpanCount: Int = 4
): Int {
    val availableWidth = resources.configuration.screenWidthDp - 32
    val targetWidth = if (screenWidthClass() == ScreenWidthClass.COMPACT && availableWidth < 360) {
        compactMinCardWidthDp
    } else {
        minCardWidthDp
    }
    return min(max(1, availableWidth / targetWidth), maxSpanCount)
}
