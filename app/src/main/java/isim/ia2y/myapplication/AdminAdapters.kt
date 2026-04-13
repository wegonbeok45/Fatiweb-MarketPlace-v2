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

class AdminOrdersAdapter(
    private val onClick: (String, AppOrder) -> Unit
) : ListAdapter<Pair<String, AppOrder>, AdminOrdersAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_admin_inline_order_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item.first, item.second)
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(uid: String, order: AppOrder) {
            itemView.findViewById<TextView>(R.id.adminInlineOrderId).text = order.displayId
            itemView.findViewById<TextView>(R.id.adminInlineOrderName).text = order.items.firstOrNull()?.let { item ->
                val name = item.name.split(" ").firstOrNull() ?: item.productId
                "$name x${item.quantity}"
            } ?: itemView.context.getString(R.string.admin_order_fallback_label)
            
            val badge = itemView.findViewById<MaterialCardView>(R.id.adminInlineOrderBadge)
            val badgeText = itemView.findViewById<TextView>(R.id.adminInlineOrderBadgeText)
            badgeText.text = order.statusLabel(itemView.context)
            
            // Reusing status styling logic if possible, or copied from Activity
            val ctx = itemView.context
            when (order.status) {
                "delivered" -> {
                    badge.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.status_chip_bg_delivered))
                    badgeText.setTextColor(ContextCompat.getColor(ctx, R.color.status_chip_text_delivered))
                }
                "pending" -> {
                    badge.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.status_chip_bg_pending))
                    badgeText.setTextColor(ContextCompat.getColor(ctx, R.color.status_chip_text_pending))
                }
                "preparing" -> {
                    badge.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.status_chip_bg_preparing))
                    badgeText.setTextColor(ContextCompat.getColor(ctx, R.color.status_chip_text_preparing))
                }
                "shipped" -> {
                    badge.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.status_chip_bg_shipped))
                    badgeText.setTextColor(ContextCompat.getColor(ctx, R.color.status_chip_text_shipped))
                }
                else -> {
                    badge.setCardBackgroundColor(ContextCompat.getColor(ctx, R.color.status_chip_bg_cancelled))
                    badgeText.setTextColor(ContextCompat.getColor(ctx, R.color.status_chip_text_cancelled))
                }
            }

            itemView.applyPressFeedback()
            itemView.setOnClickListener { onClick(uid, order) }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<Pair<String, AppOrder>>() {
        override fun areItemsTheSame(oldItem: Pair<String, AppOrder>, newItem: Pair<String, AppOrder>): Boolean =
            oldItem.second.id == newItem.second.id

        override fun areContentsTheSame(oldItem: Pair<String, AppOrder>, newItem: Pair<String, AppOrder>): Boolean =
            oldItem.second == newItem.second
    }
}

class AdminClientsAdapter(
    private val onClick: (FirestoreService.ClientInfo) -> Unit
) : ListAdapter<FirestoreService.ClientInfo, AdminClientsAdapter.ViewHolder>(ClientDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_admin_client_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(client: FirestoreService.ClientInfo) {
            val resources = itemView.context.resources
            itemView.findViewById<TextView>(R.id.adminClientName).text = client.name
            itemView.findViewById<TextView>(R.id.adminClientEmail).text = client.email
            itemView.findViewById<TextView>(R.id.adminClientId).text =
                resources.getQuantityString(
                    R.plurals.admin_order_count,
                    client.orderCount,
                    client.orderCount
                )
            itemView.findViewById<TextView>(R.id.adminClientAvatarInitial).text = if (client.name.isNotBlank()) client.name.take(1).uppercase() else "?"
            itemView.setOnClickListener { onClick(client) }
        }
    }

    class ClientDiffCallback : DiffUtil.ItemCallback<FirestoreService.ClientInfo>() {
        override fun areItemsTheSame(oldItem: FirestoreService.ClientInfo, newItem: FirestoreService.ClientInfo): Boolean =
            oldItem.email == newItem.email

        override fun areContentsTheSame(oldItem: FirestoreService.ClientInfo, newItem: FirestoreService.ClientInfo): Boolean =
            oldItem == newItem
    }
}
