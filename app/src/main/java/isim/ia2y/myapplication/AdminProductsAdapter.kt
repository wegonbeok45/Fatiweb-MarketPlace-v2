package isim.ia2y.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.util.Locale

class AdminProductsAdapter(
    private val items: List<Product>,
    private val onEdit: (Product) -> Unit,
    private val onDelete: (Product) -> Unit
) : RecyclerView.Adapter<AdminProductsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.adminProductImage)
        val title: TextView = view.findViewById(R.id.adminProductTitle)
        val subtitle: TextView = view.findViewById(R.id.adminProductSubtitle)
        val price: TextView = view.findViewById(R.id.adminProductPrice)
        val stateChip: MaterialCardView = view.findViewById(R.id.adminProductStateChip)
        val stateText: TextView = view.findViewById(R.id.adminProductStateText)
        val edit: View = view.findViewById(R.id.adminProductBtnEdit)
        val delete: View = view.findViewById(R.id.adminProductBtnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_admin_product_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val product = items[position]
        holder.image.loadCatalogImage(product.imageUrl, product.imageRes)
        holder.title.text = product.title

        val originLabel = product.origin
            .replace('_', ' ')
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        holder.subtitle.text = "${product.category.uppercase()} - $originLabel - Stock ${product.stock}"
        holder.price.text = formatDt(product.price)

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

        holder.itemView.setOnClickListener { onEdit(product) }
        holder.edit.setOnClickListener { onEdit(product) }
        holder.delete.setOnClickListener { onDelete(product) }
    }

    override fun getItemCount(): Int = items.size
}
