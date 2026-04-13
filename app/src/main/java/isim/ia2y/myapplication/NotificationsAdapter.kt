package isim.ia2y.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationsAdapter :
    ListAdapter<AppNotification, NotificationsAdapter.ViewHolder>(DiffCallback()) {

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val MILLIS_PER_HOUR = 3_600_000L
        private const val MILLIS_PER_DAY = 86_400_000L
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.tvNotificationTitle)
        val message: TextView = view.findViewById(R.id.tvNotificationMessage)
        val time: TextView = view.findViewById(R.id.tvNotificationTime)
        val unreadDot: View = view.findViewById(R.id.viewUnreadDot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.title.text = sanitize(item.title, fallback = holder.itemView.context.getString(R.string.notifications_fallback_title))
        holder.message.text = sanitize(item.message, fallback = holder.itemView.context.getString(R.string.notifications_fallback_message))
        holder.time.text = formatTime(item.timestamp)
        holder.unreadDot.visibility = if (item.isRead) View.GONE else View.VISIBLE
        holder.title.alpha = if (item.isRead) 0.86f else 1f
        holder.message.alpha = if (item.isRead) 0.72f else 1f
    }

    private fun sanitize(raw: String, fallback: String): String {
        val cleaned = raw
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.isBlank() || cleaned.startsWith("{") || cleaned.startsWith("[")) fallback else cleaned
    }

    private fun formatTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        return when {
            diff < MILLIS_PER_MINUTE -> "À l'instant"
            diff < MILLIS_PER_HOUR -> "${diff / MILLIS_PER_MINUTE} min"
            diff < MILLIS_PER_DAY -> "${diff / MILLIS_PER_HOUR} h"
            else -> SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(timestamp))
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<AppNotification>() {
        override fun areItemsTheSame(old: AppNotification, new: AppNotification) = old.id == new.id
        override fun areContentsTheSame(old: AppNotification, new: AppNotification) = old == new
    }
}
