package isim.ia2y.myapplication

import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.recyclerview.widget.RecyclerView

data class HomeHeroSlide(
    val imageUrl: String,
    @DrawableRes val fallbackRes: Int,
    val categoryKey: String,
    val contentDescription: String
)

class HomeHeroCarouselAdapter(
    private val items: List<HomeHeroSlide>,
    private val onOpenSlide: (HomeHeroSlide) -> Unit
) : RecyclerView.Adapter<HomeHeroCarouselAdapter.HeroViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeroViewHolder {
        val image = ImageView(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            adjustViewBounds = false
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val frame = FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            clipToPadding = false
            addView(image)
        }
        return HeroViewHolder(frame, image, onOpenSlide)
    }

    override fun onBindViewHolder(holder: HeroViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class HeroViewHolder(
        private val frame: FrameLayout,
        private val image: ImageView,
        private val onOpenSlide: (HomeHeroSlide) -> Unit
    ) : RecyclerView.ViewHolder(frame) {
        fun bind(item: HomeHeroSlide) {
            image.contentDescription = item.contentDescription
            image.loadCatalogImageFit(item.imageUrl, item.fallbackRes, requestedSizePx = 1200, crossfadeMillis = 180)
            frame.setOnClickListener { onOpenSlide(item) }
        }
    }
}
