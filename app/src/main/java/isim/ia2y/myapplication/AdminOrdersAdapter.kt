package isim.ia2y.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

class AdminOrdersAdapter(
    private val items: List<Pair<String, AppOrder>>,
    private val onClick: (uid: String, order: AppOrder) -> Unit
) : RecyclerView.Adapter<AdminOrdersAdapter.OrderViewHolder>() {

    class OrderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvOrderId: TextView = view.findViewById(R.id.tvOrderId)
        val tvOrderSummary: TextView = view.findViewById(R.id.tvOrderSummary)
        val tvStatusLabel: TextView = view.findViewById(R.id.tvStatusLabel)
        val cardStatusBadge: MaterialCardView = view.findViewById(R.id.cardStatusBadge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): OrderViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_admin_order, parent, false)
        return OrderViewHolder(view)
    }

    override fun onBindViewHolder(holder: OrderViewHolder, position: Int) {
        val (uid, order) = items[position]
        val context = holder.itemView.context

        holder.tvOrderId.text = order.displayId
        
        val summary = order.items.entries.firstOrNull()?.let { (id, qty) ->
            val name = ProductCatalog.byId(id)?.title?.split(" ")?.firstOrNull() ?: id
            "$name x$qty"
        } ?: context.getString(R.string.admin_order_fallback_label)
        holder.tvOrderSummary.text = summary

        val statusLabel = order.statusLabel(context)
        holder.tvStatusLabel.text = statusLabel
        
        val (bgColor, textColor) = when (order.status) {
            "pending" -> R.color.status_bg_warning to R.color.status_text_warning
            "preparing" -> R.color.profile_card_icon_bg to R.color.colorPrimary
            "shipped" -> R.color.status_bg_success to R.color.status_text_success_dark
            "delivered" -> R.color.google_green to android.R.color.white
            else -> R.color.status_bg_error_light to R.color.status_text_error_dark
        }
        
        holder.cardStatusBadge.setCardBackgroundColor(ContextCompat.getColor(context, bgColor))
        holder.tvStatusLabel.setTextColor(ContextCompat.getColor(context, textColor))

        holder.itemView.setOnClickListener { onClick(uid, order) }
    }

    override fun getItemCount() = items.size
}
