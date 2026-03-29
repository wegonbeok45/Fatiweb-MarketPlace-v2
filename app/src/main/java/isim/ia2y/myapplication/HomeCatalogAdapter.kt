package isim.ia2y.myapplication

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import java.util.Locale

class HomeCatalogAdapter(
    private val onToggleFavorite: (Product) -> Unit,
    private val onAddToCart: (Product) -> Unit,
    private val onOpenProduct: (Product) -> Unit
) : ListAdapter<Product, HomeCatalogAdapter.ViewHolder>(ProductDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_catalog_product, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image = itemView.findViewById<ImageView>(R.id.homeDynamicProductImage)
        private val title = itemView.findViewById<TextView>(R.id.homeDynamicProductTitle)
        private val subtitle = itemView.findViewById<TextView>(R.id.homeDynamicProductSubtitle)
        private val price = itemView.findViewById<TextView>(R.id.homeDynamicProductPrice)
        private val stockText = itemView.findViewById<TextView>(R.id.homeDynamicProductStockText)
        private val origin = itemView.findViewById<TextView>(R.id.homeDynamicProductOrigin)
        private val rating = itemView.findViewById<TextView>(R.id.homeDynamicProductRating)
        private val favorite = itemView.findViewById<View>(R.id.homeDynamicFavoriteButton)
        private val favoriteIcon = itemView.findViewById<ImageView>(R.id.homeDynamicFavoriteIcon)
        private val addButton = itemView.findViewById<MaterialButton>(R.id.homeDynamicAddButton)

        fun bind(product: Product) {
            image.loadCatalogImage(product.imageUrl, product.imageRes)
            title.text = product.title
            subtitle.text = product.subtitle
            price.text = formatDt(product.price)
            origin.text = formatOrigin(product.origin)
            rating.text = String.format(Locale.US, "%.1f", product.rating)
            stockText.visibility = View.GONE

            val isFavorite = FavoritesStore.isFavorite(itemView.context, product.id)
            favoriteIcon.setColorFilter(
                ContextCompat.getColor(
                    itemView.context,
                    if (isFavorite) R.color.home_heart_active else R.color.home_text_primary
                )
            )

            val ctx = itemView.context
            val (chipBg, chipText, chipTextColor) = when {
                !product.isActive -> Triple(ContextCompat.getColor(ctx, R.color.stock_chip_bg_hidden), ctx.getString(R.string.product_state_hidden), ContextCompat.getColor(ctx, R.color.stock_chip_text_hidden))
                product.stock <= 0 -> Triple(ContextCompat.getColor(ctx, R.color.stock_chip_bg_oos), ctx.getString(R.string.product_state_out_of_stock_short), ContextCompat.getColor(ctx, R.color.stock_chip_text_oos))
                product.stock <= 5 -> Triple(ContextCompat.getColor(ctx, R.color.stock_chip_bg_low), ctx.getString(R.string.product_state_low_stock, product.stock), ContextCompat.getColor(ctx, R.color.stock_chip_text_low))
                else -> Triple(ContextCompat.getColor(ctx, R.color.stock_chip_bg_available), ctx.getString(R.string.product_state_available), ContextCompat.getColor(ctx, R.color.stock_chip_text_available))
            }
            addButton.text = chipText
            addButton.backgroundTintList = ColorStateList.valueOf(chipBg)
            addButton.setTextColor(chipTextColor)

            favorite.setOnClickListener {
                onToggleFavorite(product)
                val adapterPosition = bindingAdapterPosition
                if (adapterPosition != RecyclerView.NO_POSITION) {
                    notifyItemChanged(adapterPosition)
                }
            }
            itemView.setOnClickListener { onOpenProduct(product) }
        }
    }

    private fun formatOrigin(origin: String): String {
        return origin.replace('_', ' ')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    }
}
