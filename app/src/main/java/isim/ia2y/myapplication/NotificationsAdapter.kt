package isim.ia2y.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.*

// Cette classe organise cette partie de l'app.
class NotificationsAdapter(private val items: List<AppNotification>) :
    RecyclerView.Adapter<NotificationsAdapter.ViewHolder>() {

    // Cette classe organise cette partie de l'app.
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvNotificationTitle)
        val message: TextView = view.findViewById(R.id.tvNotificationMessage)
        val time: TextView = view.findViewById(R.id.tvNotificationTime)
        val unreadDot: View = view.findViewById(R.id.viewUnreadDot)
    }

    // Cette fonction fait une action de cette partie de l'app.
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    // Cette fonction fait une action de cette partie de l'app.
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.message.text = item.message
        holder.time.text = formatTime(item.timestamp)
        holder.unreadDot.visibility = if (item.isRead) View.GONE else View.VISIBLE
    }

    // Cette fonction fait une action de cette partie de l'app.
    override fun getItemCount() = items.size

    // Cette fonction fait une action de cette partie de l'app.
    private fun formatTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < 60000 -> "À l\'instant"
            diff < 3600000 -> "${diff / 60000} min"
            diff < 86400000 -> "${diff / 3600000} h"
            else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(timestamp))
        }
    }
}
