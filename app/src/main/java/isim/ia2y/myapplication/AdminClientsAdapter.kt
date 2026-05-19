package isim.ia2y.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AdminClientsStaticAdapter(
    private val items: List<FirestoreService.ClientInfo>,
    private val onClick: (FirestoreService.ClientInfo) -> Unit
) : RecyclerView.Adapter<AdminClientsStaticAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val avatarInitial: TextView = view.findViewById(R.id.adminClientAvatarInitial)
        val avatarImage: ImageView = view.findViewById(R.id.adminClientAvatarImage)
        val clientId: TextView = view.findViewById(R.id.adminClientId)
        val name: TextView = view.findViewById(R.id.adminClientName)
        val email: TextView = view.findViewById(R.id.adminClientEmail)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_admin_client_row, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val client = items[position]
        val resources = holder.itemView.context.resources
        holder.avatarInitial.text = client.name.take(1).ifBlank { "?" }.uppercase()
        if (client.avatarUrl.isNotBlank()) {
            holder.avatarImage.visibility = View.VISIBLE
            holder.avatarInitial.visibility = View.GONE
            holder.avatarImage.loadAvatarImage(client.avatarUrl)
        } else {
            holder.avatarImage.visibility = View.GONE
            holder.avatarInitial.visibility = View.VISIBLE
        }
        val orderCount = resources.getQuantityString(
            R.plurals.admin_order_count,
            client.orderCount,
            client.orderCount
        )
        holder.clientId.text = "$orderCount - ${client.roleLabel()}"
        holder.name.text = client.name
        holder.email.text = listOf(client.phone, client.email)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .ifBlank { client.uid }
        holder.itemView.setOnClickListener { onClick(client) }
    }

    override fun getItemCount(): Int = items.size
}

private fun FirestoreService.ClientInfo.roleLabel(): String = when (role) {
    UserRoles.ADMIN -> "Admin"
    UserRoles.VENDEUR -> "Vendeur"
    else -> "Client"
}
