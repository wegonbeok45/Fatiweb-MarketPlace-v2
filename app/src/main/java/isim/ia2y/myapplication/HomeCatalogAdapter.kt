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
import java.util.Locale

class HomeCatalogAdapter(
    private val onToggleFavorite: (Product) -> Unit,
    private val onOpenProduct: (Product) -> Unit,
    private val fixedItemWidthRes: Int? = null
) : ListAdapter<Product, HomeCatalogAdapter.ViewHolder>(ProductDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_home_catalog_product, parent, false)
        fixedItemWidthRes?.let { widthRes ->
            val width = parent.resources.getDimensionPixelSize(widthRes)
            view.layoutParams = RecyclerView.LayoutParams(
                width,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image = itemView.findViewById<ImageView>(R.id.homeDynamicProductImage)
        private val title = itemView.findViewById<TextView>(R.id.homeDynamicProductTitle)
        private val subtitle = itemView.findViewById<TextView?>(R.id.homeDynamicProductSubtitle)
        private val price = itemView.findViewById<TextView>(R.id.homeDynamicProductPrice)
        private val stockText = itemView.findViewById<TextView>(R.id.homeDynamicProductStockText)
        private val origin = itemView.findViewById<TextView>(R.id.homeDynamicProductOrigin)
        private val rating = itemView.findViewById<TextView>(R.id.homeDynamicProductRating)
        private val favorite = itemView.findViewById<View>(R.id.homeDynamicFavoriteButton)
        private val favoriteIcon = itemView.findViewById<ImageView>(R.id.homeDynamicFavoriteIcon)

        fun bind(product: Product) {
            val ctx = itemView.context

            image.loadCatalogImage(product.imageUrl, product.imageRes)
            title.text = product.title
            subtitle?.text = product.subtitle
            price.text = formatDt(product.price)
            origin.text = formatOrigin(product.origin)
            rating.text = String.format(Locale.US, "%.1f", product.rating)

            when {
                !product.isActive -> {
                    stockText.visibility = View.VISIBLE
                    stockText.text = ctx.getString(R.string.product_state_hidden)
                    stockText.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.stock_chip_bg_hidden)
                    )
                    stockText.setTextColor(ContextCompat.getColor(ctx, R.color.stock_chip_text_hidden))
                }
                product.stock <= 0 -> {
                    stockText.visibility = View.VISIBLE
                    stockText.text = ctx.getString(R.string.product_state_out_of_stock_short)
                    stockText.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.stock_chip_bg_oos)
                    )
                    stockText.setTextColor(ContextCompat.getColor(ctx, R.color.stock_chip_text_oos))
                }
                product.stock in 1..5 -> {
                    stockText.visibility = View.VISIBLE
                    stockText.text = ctx.getString(R.string.product_state_low_stock, product.stock)
                    stockText.backgroundTintList = ColorStateList.valueOf(
                        ContextCompat.getColor(ctx, R.color.stock_chip_bg_low)
                    )
                    stockText.setTextColor(ContextCompat.getColor(ctx, R.color.stock_chip_text_low))
                }
                else -> stockText.visibility = View.GONE
            }

            val isFavorite = FavoritesStore.isFavorite(ctx, product.id)
            favoriteIcon.setColorFilter(
                ContextCompat.getColor(
                    ctx,
                    if (isFavorite) R.color.home_heart_active else R.color.home_text_primary
                )
            )
            favorite.setOnClickListener {
                onToggleFavorite(product)
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
            }

            itemView.setOnClickListener { onOpenProduct(product) }
        }
    }
}
