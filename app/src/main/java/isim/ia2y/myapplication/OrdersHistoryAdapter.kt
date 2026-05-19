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
        val primaryAction: com.google.android.material.button.MaterialButton =
            view.findViewById(R.id.btnOrderPrimary)
        val secondaryAction: com.google.android.material.button.MaterialButton =
            view.findViewById(R.id.btnOrderSecondary)
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
        holder.orderDate.text = buildHeadline(holder.itemView.context, order)
        holder.orderItems.text = itemsSummary
        holder.orderTotal.text = formatDt(order.total)
        holder.orderStatus.text = order.statusLabel(holder.itemView.context)
        holder.primaryAction.text = holder.itemView.context.getString(R.string.orders_history_action_details)
        holder.secondaryAction.text = when (order.status.lowercase()) {
            "delivered" -> holder.itemView.context.getString(R.string.orders_history_action_reorder)
            else -> holder.itemView.context.getString(R.string.orders_history_action_track)
        }
        
        holder.thumbnailsLayout.removeAllViews()
        val inflater = LayoutInflater.from(holder.itemView.context)
        order.items.take(4).forEach { item ->
            val thumbView = inflater.inflate(R.layout.item_checkout_thumbnail, holder.thumbnailsLayout, false)
            val image = thumbView.findViewById<android.widget.ImageView>(R.id.ivThumbnail)
            val fallbackProduct = ProductCatalog.byId(item.productId)
            image.loadCatalogImage(
                item.thumbnailUrl.ifBlank { fallbackProduct?.previewImageUrl() },
                fallbackProduct?.catalogFallbackImageRes() ?: R.drawable.placeholder
            )
            
            val lp = thumbView.layoutParams
            lp.width = (44 * holder.itemView.context.resources.displayMetrics.density).toInt()
            lp.height = (44 * holder.itemView.context.resources.displayMetrics.density).toInt()
            thumbView.layoutParams = lp
            
            holder.thumbnailsLayout.addView(thumbView)
        }
        
        holder.primaryAction.setOnClickListener { onClick(order) }
        holder.secondaryAction.setOnClickListener { onClick(order) }
        holder.itemView.setOnClickListener { onClick(order) }
    }

    private fun buildHeadline(context: android.content.Context, order: AppOrder): String {
        return when (order.status.lowercase()) {
            "delivered" -> context.getString(R.string.orders_history_filter_delivered) + " " + order.formattedDate
            "cancelled" -> context.getString(R.string.orders_history_filter_cancelled) + " " + order.formattedDate
            else -> "Arriving soon"
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<AppOrder>() {
        override fun areItemsTheSame(old: AppOrder, new: AppOrder) = old.id == new.id
        override fun areContentsTheSame(old: AppOrder, new: AppOrder) = old == new
    }
}
