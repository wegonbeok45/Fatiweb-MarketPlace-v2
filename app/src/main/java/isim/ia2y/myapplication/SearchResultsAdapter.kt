package isim.ia2y.myapplication

import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
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
        private val variantHints = itemView.findViewById<View?>(R.id.searchVariantHints)
        private val colorDotsRow = itemView.findViewById<LinearLayout?>(R.id.searchColorDotsRow)
        private val sizeHint = itemView.findViewById<TextView?>(R.id.searchSizeHint)

        fun bind(product: Product) {
            val ctx = itemView.context
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
                val isFavorite = FavoritesStore.isFavorite(ctx, product.id)
                favoriteButton.setImageResource(if (isFavorite) R.drawable.ic_home_heart_filled else R.drawable.ic_home_heart)
                val tint = if (isFavorite) R.color.home_heart_active else R.color.home_ref_text_primary
                favoriteButton.setColorFilter(ContextCompat.getColor(ctx, tint))
            }

            refreshFavoriteTint()
            favoriteButton.setOnClickListener {
                onToggleFavorite(product)
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) notifyItemChanged(position)
            }

            addButton.alpha = if (product.effectiveStock > 0) 1f else 0.45f
            addButton.isEnabled = product.effectiveStock > 0
            addButton.setOnClickListener { onAddToCart(product) }
            itemView.setOnClickListener { onOpenProduct(product) }

            bindVariantHints(product)
        }

        private fun bindVariantHints(product: Product) {
            val ctx = itemView.context
            val colors = product.colorOptions.take(5)
            val sizes = product.sizeOptions
            val hasColors = colors.isNotEmpty()
            val hasSizes = sizes.isNotEmpty()
            val showHints = hasColors || hasSizes

            variantHints?.visibility = if (showHints) View.VISIBLE else View.GONE
            if (!showHints) return

            colorDotsRow?.removeAllViews()
            if (hasColors) {
                val dotSizePx = (10 * ctx.resources.displayMetrics.density).toInt()
                val dotMarginPx = (3 * ctx.resources.displayMetrics.density).toInt()
                colors.take(4).forEach { color ->
                    val dot = View(ctx)
                    val bg = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        val parsedColor = runCatching {
                            Color.parseColor(if (color.hex.startsWith("#")) color.hex else "#${color.hex}")
                        }.getOrNull()
                        if (parsedColor != null) {
                            setColor(parsedColor)
                            setStroke((1 * ctx.resources.displayMetrics.density).toInt(),
                                ContextCompat.getColor(ctx, R.color.ms_border_default))
                        } else {
                            setColor(ContextCompat.getColor(ctx, R.color.ms_surface_sunken))
                            setStroke((1 * ctx.resources.displayMetrics.density).toInt(),
                                ContextCompat.getColor(ctx, R.color.ms_border_default))
                        }
                    }
                    dot.background = bg
                    colorDotsRow?.addView(dot, LinearLayout.LayoutParams(dotSizePx, dotSizePx).apply {
                        marginEnd = dotMarginPx
                    })
                }
                if (colors.size > 4) {
                    val overflow = TextView(ctx).apply {
                        text = "+${colors.size - 4}"
                        textSize = 9f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(ContextCompat.getColor(ctx, R.color.ms_text_tertiary))
                    }
                    colorDotsRow?.addView(overflow)
                }
                colorDotsRow?.visibility = View.VISIBLE
            } else {
                colorDotsRow?.visibility = View.GONE
            }

            if (hasSizes && sizeHint != null) {
                val sizeText = sizes.take(4).joinToString(" · ")
                val suffix = if (sizes.size > 4) " ..." else ""
                sizeHint.text = "$sizeText$suffix"
                sizeHint.visibility = View.VISIBLE
                if (!hasColors) {
                    (sizeHint.layoutParams as? LinearLayout.LayoutParams)?.marginStart = 0
                }
            } else {
                sizeHint?.visibility = View.GONE
            }
        }
    }
}
