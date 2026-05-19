package isim.ia2y.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class HomeCatalogAdapter(
    private val onToggleFavorite: (Product) -> Unit,
    private val onOpenProduct: (Product) -> Unit,
    private val fixedItemWidthRes: Int? = null
) : ListAdapter<Product, HomeCatalogAdapter.ViewHolder>(ProductDiffCallback()) {

    init {
        setHasStableIds(true)
    }

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

    override fun getItemId(position: Int): Long {
        return getItem(position).id.hashCode().toLong()
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.unbind()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val image = itemView.findViewById<ImageView>(R.id.homeDynamicProductImage)
        private val title = itemView.findViewById<TextView>(R.id.homeDynamicProductTitle)
        private val subtitle = itemView.findViewById<TextView?>(R.id.homeDynamicProductSubtitle)
        private val price = itemView.findViewById<TextView>(R.id.homeDynamicProductPrice)
        private val priceOriginal = itemView.findViewById<TextView?>(R.id.homeDynamicProductPriceOriginal)
        private val discountBadge = itemView.findViewById<TextView?>(R.id.homeDynamicProductDiscountBadge)
        private val origin = itemView.findViewById<TextView>(R.id.homeDynamicProductOrigin)
        private val rating = itemView.findViewById<TextView>(R.id.homeDynamicProductRating)
        private val category = itemView.findViewById<TextView>(R.id.homeDynamicProductCategory)
        private val favorite = itemView.findViewById<ImageView>(R.id.homeDynamicFavoriteButton)

        fun bind(product: Product) {
            val ctx = itemView.context

            image.loadCatalogImage(product.previewImageUrl(), product.catalogFallbackImageRes(), requestedSizePx = 640)
            title.text = product.title
            subtitle?.text = product.subtitle
            price.text = formatDt(product.unitPrice)
            if (product.hasDiscount) {
                priceOriginal?.apply {
                    text = formatDt(product.effectivePrice)
                    paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    visibility = View.VISIBLE
                }
                discountBadge?.apply {
                    text = ctx.getString(R.string.product_price_discount_badge, product.discountPercentClamped)
                    visibility = View.VISIBLE
                }
            } else {
                priceOriginal?.visibility = View.GONE
                discountBadge?.visibility = View.GONE
            }
            origin.text = product.sellerDisplayName
            rating.text = productCardRatingText(product)
            category.text = productCardCategoryLabel(product)
            val isFavorite = FavoritesStore.isFavorite(ctx, product.id)
            favorite.setImageResource(if (isFavorite) R.drawable.ic_home_heart_filled else R.drawable.ic_home_heart)
            favorite.setColorFilter(
                ContextCompat.getColor(
                    ctx,
                    if (isFavorite) R.color.home_heart_active else R.color.home_ref_text_primary
                )
            )
            favorite.setOnClickListener {
                onToggleFavorite(product)
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) notifyItemChanged(pos)
            }

            itemView.setOnClickListener { onOpenProduct(product) }
            itemView.setOnTouchListener { v, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(150).start()
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                    }
                }
                false
            }
        }

        fun unbind() {
            favorite.setOnClickListener(null)
            itemView.setOnClickListener(null)
            itemView.setOnTouchListener(null)
        }
    }
}
