package isim.ia2y.myapplication

import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

class ProductImagePagerAdapter(
    private val fallbackRes: Int
) : RecyclerView.Adapter<ProductImagePagerAdapter.ImageViewHolder>() {
    private var urls: List<String> = emptyList()

    fun submitList(nextUrls: List<String>) {
        urls = nextUrls
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val image = ImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = parent.context.getString(R.string.details_image_cd)
        }
        return ImageViewHolder(image)
    }

    override fun getItemCount(): Int = urls.size

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        holder.image.loadCatalogImage(urls.getOrNull(position), fallbackRes, requestedSizePx = 960)
    }

    class ImageViewHolder(val image: ImageView) : RecyclerView.ViewHolder(image)
}
