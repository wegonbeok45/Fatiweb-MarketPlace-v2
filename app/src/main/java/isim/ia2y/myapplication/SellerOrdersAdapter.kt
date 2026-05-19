package isim.ia2y.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class SellerOrdersAdapter(
    private val onClick: (AdminService.SellerOrderRow) -> Unit
) : ListAdapter<AdminService.SellerOrderRow, SellerOrdersAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_seller_order_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(row: AdminService.SellerOrderRow) {
            val context = itemView.context
            val order = row.order
            val clientName = order.shippingAddress?.recipientName?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.seller_orders_unknown_client)
            itemView.findViewById<TextView>(R.id.sellerOrderId).text = order.displayId
            itemView.findViewById<TextView>(R.id.sellerOrderClient).text =
                context.getString(R.string.seller_orders_row_client, clientName, row.itemCount)
            itemView.findViewById<TextView>(R.id.sellerOrderTotal).text = formatDt(row.sellerTotal)

            val badge = itemView.findViewById<MaterialCardView>(R.id.sellerOrderBadge)
            val badgeText = itemView.findViewById<TextView>(R.id.sellerOrderBadgeText)
            badgeText.text = order.statusLabel(context)
            applyStatusColors(badge, badgeText, order.status)

            itemView.applyPressFeedback()
            itemView.setOnClickListener { onClick(row) }
        }
    }

    private fun applyStatusColors(badge: MaterialCardView, text: TextView, status: String) {
        val context = badge.context
        val (bg, fg) = when (OrderStatuses.normalize(status)) {
            OrderStatuses.DELIVERED -> R.color.status_chip_bg_delivered to R.color.status_chip_text_delivered
            OrderStatuses.PREPARING, OrderStatuses.CONFIRMED -> R.color.status_chip_bg_preparing to R.color.status_chip_text_preparing
            OrderStatuses.SHIPPED -> R.color.status_chip_bg_shipped to R.color.status_chip_text_shipped
            OrderStatuses.PENDING -> R.color.status_chip_bg_pending to R.color.status_chip_text_pending
            else -> R.color.status_chip_bg_cancelled to R.color.status_chip_text_cancelled
        }
        badge.setCardBackgroundColor(ContextCompat.getColor(context, bg))
        text.setTextColor(ContextCompat.getColor(context, fg))
    }

    private class DiffCallback : DiffUtil.ItemCallback<AdminService.SellerOrderRow>() {
        override fun areItemsTheSame(
            oldItem: AdminService.SellerOrderRow,
            newItem: AdminService.SellerOrderRow
        ): Boolean = oldItem.order.id == newItem.order.id

        override fun areContentsTheSame(
            oldItem: AdminService.SellerOrderRow,
            newItem: AdminService.SellerOrderRow
        ): Boolean = oldItem == newItem
    }
}
