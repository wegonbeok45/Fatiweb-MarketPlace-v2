package isim.ia2y.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import isim.ia2y.myapplication.ui.base.MsStatusPill

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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_order_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val order = getItem(position)
        val context = holder.itemView.context

        holder.orderId.text = order.displayId
        holder.orderDate.text = order.formattedDate

        val itemsSummary = order.items.take(3).joinToString(", ") { item ->
            val name = item.name.takeIf { it.isNotBlank() } ?: item.productId
            "$name ×${item.quantity}"
        }.let { if (order.items.size > 3) "$it…" else it }
        holder.orderItems.text = itemsSummary

        holder.orderTotal.text = formatDt(order.total)

        val (pillKind, pillLabel) = statusPillFor(order.status)
        MsStatusPill.bind(holder.orderStatus, pillKind, pillLabel)

        holder.primaryAction.text = when (OrderStatuses.normalize(order.status)) {
            OrderStatuses.DELIVERED -> context.getString(R.string.orders_history_action_reorder)
            OrderStatuses.CANCELLED -> context.getString(R.string.orders_history_action_details)
            else -> context.getString(R.string.orders_history_action_track)
        }

        holder.thumbnailsLayout.removeAllViews()
        val inflater = LayoutInflater.from(context)
        order.items.take(4).forEach { item ->
            val thumbView = inflater.inflate(R.layout.item_checkout_thumbnail, holder.thumbnailsLayout, false)
            val image = thumbView.findViewById<android.widget.ImageView>(R.id.ivThumbnail)
            val fallbackProduct = ProductCatalog.byId(item.productId)
            image.loadCatalogImage(
                item.thumbnailUrl.ifBlank { fallbackProduct?.previewImageUrl() },
                fallbackProduct?.catalogFallbackImageRes() ?: R.drawable.placeholder
            )

            val lp = thumbView.layoutParams
            lp.width = (48 * context.resources.displayMetrics.density).toInt()
            lp.height = (48 * context.resources.displayMetrics.density).toInt()
            thumbView.layoutParams = lp

            holder.thumbnailsLayout.addView(thumbView)
        }

        holder.primaryAction.setOnClickListener { onClick(order) }
        holder.itemView.setOnClickListener { onClick(order) }
    }

    private fun statusPillFor(rawStatus: String): Pair<MsStatusPill.Kind, Int> {
        return when (OrderStatuses.normalize(rawStatus)) {
            OrderStatuses.PENDING -> MsStatusPill.Kind.Pending to R.string.ms_order_status_pending
            OrderStatuses.CONFIRMED -> MsStatusPill.Kind.Info to R.string.ms_order_status_confirmed
            OrderStatuses.PREPARING -> MsStatusPill.Kind.Info to R.string.ms_order_status_preparing
            OrderStatuses.SHIPPED -> MsStatusPill.Kind.Info to R.string.ms_order_status_shipped
            OrderStatuses.DELIVERED -> MsStatusPill.Kind.Approved to R.string.ms_order_status_delivered
            else -> MsStatusPill.Kind.Archived to R.string.ms_order_status_cancelled
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<AppOrder>() {
        override fun areItemsTheSame(old: AppOrder, new: AppOrder) = old.id == new.id
        override fun areContentsTheSame(old: AppOrder, new: AppOrder) = old == new
    }
}
