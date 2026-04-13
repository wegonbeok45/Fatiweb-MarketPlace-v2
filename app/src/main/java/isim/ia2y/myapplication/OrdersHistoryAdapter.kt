package isim.ia2y.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class OrdersHistoryAdapter(
    private val onClick: (AppOrder) -> Unit
) : ListAdapter<AppOrder, OrdersHistoryAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val orderId: TextView = view.findViewById(R.id.tvOrderId)
        val orderDate: TextView = view.findViewById(R.id.tvOrderDate)
        val orderItems: TextView = view.findViewById(R.id.tvOrderItems)
        val orderTotal: TextView = view.findViewById(R.id.tvOrderTotal)
        val orderStatus: TextView = view.findViewById(R.id.tvOrderStatus)
        val thumbnailsLayout: android.widget.LinearLayout = view.findViewById(R.id.layoutOrderThumbnails)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_order_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val order = getItem(position)
        val itemsSummary = order.items.take(2).joinToString(", ") { item ->
            val name = item.name.split(" ").firstOrNull() ?: item.productId
            "$name x${item.quantity}"
        }.let { if (order.items.size > 2) "$it..." else it }

        holder.orderId.text = order.displayId
        holder.orderDate.text = order.formattedDate
        holder.orderItems.text = itemsSummary
        holder.orderTotal.text = formatDt(order.total)
        holder.orderStatus.text = order.statusLabel(holder.itemView.context)
        
        holder.thumbnailsLayout.removeAllViews()
        val inflater = LayoutInflater.from(holder.itemView.context)
        order.items.take(4).forEach { item ->
            val thumbView = inflater.inflate(R.layout.item_checkout_thumbnail, holder.thumbnailsLayout, false)
            val image = thumbView.findViewById<android.widget.ImageView>(R.id.ivThumbnail)
            val fallbackProduct = ProductCatalog.byId(item.productId)
            image.loadCatalogImage(
                item.thumbnailUrl.ifBlank { fallbackProduct?.imageUrl },
                fallbackProduct?.imageRes ?: R.drawable.placeholder
            )
            
            val lp = thumbView.layoutParams
            lp.width = (44 * holder.itemView.context.resources.displayMetrics.density).toInt()
            lp.height = (44 * holder.itemView.context.resources.displayMetrics.density).toInt()
            thumbView.layoutParams = lp
            
            holder.thumbnailsLayout.addView(thumbView)
        }
        
        holder.itemView.setOnClickListener { onClick(order) }
    }

    private class DiffCallback : DiffUtil.ItemCallback<AppOrder>() {
        override fun areItemsTheSame(old: AppOrder, new: AppOrder) = old.id == new.id
        override fun areContentsTheSame(old: AppOrder, new: AppOrder) = old == new
    }
}
