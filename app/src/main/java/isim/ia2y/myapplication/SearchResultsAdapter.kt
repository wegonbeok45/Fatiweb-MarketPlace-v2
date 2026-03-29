package isim.ia2y.myapplication

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
import com.google.android.material.card.MaterialCardView
import java.util.Locale

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
        private val favoriteIcon = itemView.findViewById<ImageView>(R.id.ivSearchFavoriteIcon)
        private val favoriteButton = itemView.findViewById<MaterialCardView>(R.id.btnSearchFavorite)
        private val addButton = itemView.findViewById<MaterialButton>(R.id.btnSearchAddCart)

        fun bind(product: Product) {
            image.loadCatalogImage(product.imageUrl, product.imageRes)
            title.text = product.title
            subtitle.text = product.subtitle
            price.text = String.format(Locale.US, "%.3f", product.price)

            fun refreshFavoriteTint() {
                val tint = if (FavoritesStore.isFavorite(itemView.context, product.id)) {
                    R.color.home_heart_active
                } else {
                    R.color.home_text_primary
                }
                favoriteIcon.setColorFilter(ContextCompat.getColor(itemView.context, tint))
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
            addButton.setOnClickListener { onAddToCart(product) }
            itemView.setOnClickListener { onOpenProduct(product) }
        }
    }
}
