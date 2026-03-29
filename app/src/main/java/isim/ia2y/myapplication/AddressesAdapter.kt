package isim.ia2y.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class AddressesAdapter(
    private val onDefault: (DeliveryAddress) -> Unit,
    private val onEdit: (DeliveryAddress) -> Unit,
    private val onDelete: (DeliveryAddress) -> Unit,
    private val onSelect: (DeliveryAddress) -> Unit
) : ListAdapter<DeliveryAddress, AddressesAdapter.ViewHolder>(DiffCallback()) {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvAddressTitle)
        val summary: TextView = view.findViewById(R.id.tvAddressSummary)
        val details: TextView = view.findViewById(R.id.tvAddressDetails)
        val state: TextView = view.findViewById(R.id.tvAddressState)
        val setDefault: TextView = view.findViewById(R.id.tvAddressSetDefault)
        val edit: TextView = view.findViewById(R.id.tvAddressEdit)
        val remove: TextView = view.findViewById(R.id.tvAddressRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_address_entry, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.title.text = item.titleLine.ifBlank { item.label.ifBlank { "Adresse" } }
        holder.summary.text = item.summaryLine
        holder.details.text = item.detailsLine.ifBlank { "Aucun détail supplémentaire" }
        holder.state.text = if (item.isDefault) "Par défaut" else "Secondaire"
        holder.setDefault.visibility = if (item.isDefault) View.GONE else View.VISIBLE

        holder.itemView.setOnClickListener { onSelect(item) }
        holder.setDefault.setOnClickListener { onDefault(item) }
        holder.edit.setOnClickListener { onEdit(item) }
        holder.remove.setOnClickListener { onDelete(item) }
    }

    private class DiffCallback : DiffUtil.ItemCallback<DeliveryAddress>() {
        override fun areItemsTheSame(old: DeliveryAddress, new: DeliveryAddress) = old.id == new.id
        override fun areContentsTheSame(old: DeliveryAddress, new: DeliveryAddress) = old == new
    }
}
