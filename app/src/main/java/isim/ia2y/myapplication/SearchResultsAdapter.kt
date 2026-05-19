package isim.ia2y.myapplication

import android.graphics.Paint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class SearchResultsAdapter(
    private val onToggleFavorite: (Product) -> Unit,
    private val onAddToCart: (Product) -> Unit,
    private val onOpenProduct: (Product) -> Unit
) : ListAdapter<Product, SearchResultsAdapter.SearchResultViewHolder>(ProductDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchResultViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_search_product_card, parent, false)
        return SearchResultViewHolder(view)
    }

    override fun onBindViewHolder(holder: SearchResultViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SearchResultViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image = itemView.findViewById<ImageView>(R.id.ivSearchProductImage)
        private val title = itemView.findViewById<TextView>(R.id.tvSearchProductTitle)
        private val subtitle = itemView.findViewById<TextView>(R.id.tvSearchProductSubtitle)
        private val price = itemView.findViewById<TextView>(R.id.tvSearchPriceAmount)
        private val priceOriginal = itemView.findViewById<TextView>(R.id.tvSearchPriceOriginal)
        private val rating = itemView.findViewById<TextView>(R.id.tvSearchProductRating)
        private val category = itemView.findViewById<TextView>(R.id.tvSearchProductCategory)
        private val favoriteButton = itemView.findViewById<ImageView>(R.id.btnSearchFavorite)
        private val addButton = itemView.findViewById<ImageView>(R.id.btnSearchAddCart)

        fun bind(product: Product) {
            image.loadCatalogImage(product.previewImageUrl(), product.catalogFallbackImageRes(), requestedSizePx = 640)
            title.text = product.title
            subtitle.text = product.sellerDisplayName
            price.text = formatDt(product.unitPrice)
            if (product.hasDiscount) {
                priceOriginal.text = formatDt(product.effectivePrice)
                priceOriginal.paintFlags = priceOriginal.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                priceOriginal.visibility = View.VISIBLE
            } else {
                priceOriginal.visibility = View.GONE
            }
            rating.text = productCardRatingText(product)
            category.text = productCardCategoryLabel(product)
            fun refreshFavoriteTint() {
                val isFavorite = FavoritesStore.isFavorite(itemView.context, product.id)
                favoriteButton.setImageResource(if (isFavorite) R.drawable.ic_home_heart_filled else R.drawable.ic_home_heart)
                val tint = if (isFavorite) {
                    R.color.home_heart_active
                } else {
                    R.color.home_ref_text_primary
                }
                favoriteButton.setColorFilter(ContextCompat.getColor(itemView.context, tint))
            }

            refreshFavoriteTint()
            favoriteButton.setOnClickListener {
                onToggleFavorite(product)
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    notifyItemChanged(position)
                }
            }

            addButton.alpha = if (product.stock > 0) 1f else 0.45f
            addButton.isEnabled = product.stock > 0
            addButton.setOnClickListener { onAddToCart(product) }
            itemView.setOnClickListener { onOpenProduct(product) }
        }
    }
}
