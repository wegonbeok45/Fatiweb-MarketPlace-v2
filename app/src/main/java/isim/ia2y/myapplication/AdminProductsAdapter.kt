package isim.ia2y.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class AdminProductsAdapter(
    items: MutableList<Product>,
    private val onEdit: (Product) -> Unit,
    private val onDelete: (Product) -> Unit,
    private val canEdit: (Product) -> Boolean = { true },
    private val onEditBlocked: (Product) -> Unit = {},
    private val onOverflow: ((View, Product) -> Unit)? = null,
) : ListAdapter<Product, AdminProductsAdapter.ViewHolder>(ProductDiffCallback()) {

    init {
        submitList(items.toList())
    }

    fun updateItems(newItems: List<Product>) {
        submitList(newItems.toList())
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.adminProductImage)
        val title: TextView = view.findViewById(R.id.adminProductTitle)
        val subtitle: TextView = view.findViewById(R.id.adminProductSubtitle)
        val price: TextView = view.findViewById(R.id.adminProductPrice)
        val priceOriginal: TextView = view.findViewById(R.id.adminProductPriceOriginal)
        val discountBadge: TextView = view.findViewById(R.id.adminProductDiscountBadge)
        val stateChip: MaterialCardView = view.findViewById(R.id.adminProductStateChip)
        val stateText: TextView = view.findViewById(R.id.adminProductStateText)
        val edit: View = view.findViewById(R.id.adminProductBtnEdit)
        val delete: View = view.findViewById(R.id.adminProductBtnDelete)
        val overflow: View? = view.findViewById(R.id.adminProductOverflow)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_admin_product_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val product = getItem(position)
        holder.image.loadCatalogImage(product.previewImageUrl(), product.catalogFallbackImageRes(), requestedSizePx = 160)
        holder.title.text = product.title

        holder.subtitle.text = "${product.category.uppercase()} - ${product.sellerDisplayName} - Stock ${product.stock}"
        holder.price.text = formatDt(product.unitPrice)
        if (product.hasDiscount) {
            holder.priceOriginal.apply {
                text = formatDt(product.effectivePrice)
                paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                visibility = View.VISIBLE
            }
            holder.discountBadge.apply {
                text = context.getString(R.string.product_price_discount_badge, product.discountPercentClamped)
                visibility = View.VISIBLE
            }
        } else {
            holder.priceOriginal.visibility = View.GONE
            holder.discountBadge.visibility = View.GONE
        }

        val context = holder.itemView.context
        val (chipBg, chipLabel, chipColor) = when {
            !product.isActive -> Triple(
                ContextCompat.getColor(context, R.color.stock_chip_bg_hidden),
                context.getString(R.string.product_state_hidden),
                ContextCompat.getColor(context, R.color.stock_chip_text_hidden)
            )

            product.stock <= 0 -> Triple(
                ContextCompat.getColor(context, R.color.stock_chip_bg_oos),
                context.getString(R.string.product_state_out_of_stock_short),
                ContextCompat.getColor(context, R.color.stock_chip_text_oos)
            )

            product.stock <= 5 -> Triple(
                ContextCompat.getColor(context, R.color.stock_chip_bg_low),
                context.getString(R.string.product_state_low_stock, product.stock),
                ContextCompat.getColor(context, R.color.stock_chip_text_low)
            )

            else -> Triple(
                ContextCompat.getColor(context, R.color.stock_chip_bg_available),
                context.getString(R.string.product_state_available),
                ContextCompat.getColor(context, R.color.stock_chip_text_available)
            )
        }
        holder.stateChip.setCardBackgroundColor(chipBg)
        holder.stateText.text = chipLabel
        holder.stateText.setTextColor(chipColor)

        val editable = canEdit(product)
        holder.edit.alpha = if (editable) 1f else 0.38f
        holder.itemView.setOnClickListener {
            if (editable) onEdit(product) else onEditBlocked(product)
        }
        holder.edit.setOnClickListener {
            if (editable) onEdit(product) else onEditBlocked(product)
        }
        holder.delete.setOnClickListener { onDelete(product) }
        val overflowCb = onOverflow
        if (overflowCb != null) {
            holder.overflow?.visibility = View.VISIBLE
            holder.overflow?.setOnClickListener { anchor -> overflowCb(anchor, product) }
        } else {
            holder.overflow?.visibility = View.GONE
        }
    }

}
