package isim.ia2y.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class MessagingInboxAdapter(
    private val currentUid: String,
    private val onClick: (Conversation) -> Unit
) : ListAdapter<Conversation, MessagingInboxAdapter.Holder>(ConversationDiff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_messaging_inbox, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position), currentUid, onClick)
    }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatar: ImageView = itemView.findViewById(R.id.ivInboxAvatar)
        private val onlineDot: View = itemView.findViewById(R.id.vInboxOnline)
        private val name: TextView = itemView.findViewById(R.id.tvInboxName)
        private val time: TextView = itemView.findViewById(R.id.tvInboxTime)
        private val preview: TextView = itemView.findViewById(R.id.tvInboxPreview)
        private val unread: TextView = itemView.findViewById(R.id.tvInboxUnread)
        private val product: TextView = itemView.findViewById(R.id.tvInboxProduct)

        fun bind(item: Conversation, currentUid: String, onClick: (Conversation) -> Unit) {
            val ctx = itemView.context
            val unreadCount = item.unreadCounts[currentUid] ?: 0
            val hasUnread = unreadCount > 0
            val isImagePreview = hasUnread && item.lastMessageType == "image"

            // Avatar
            val avatarSize = ctx.resources.getDimensionPixelSize(R.dimen.ms_avatar_md)
            avatar.background = ovalShape(ContextCompat.getColor(ctx, R.color.ms_surface_sunken))
            avatar.clipToOutline = true
            avatar.loadAvatarImage(item.otherParticipantAvatar(currentUid), avatarSize)

            // Online dot — hidden; no presence signal in current data model
            onlineDot.visibility = View.GONE

            // Name
            name.text = item.otherParticipantName(currentUid)
            name.typeface = ctx.resources.getFont(
                if (hasUnread) R.font.manrope_bold else R.font.manrope_semibold
            )
            name.setTextColor(ContextCompat.getColor(ctx, R.color.ms_text_primary))

            // Time
            time.text = formatConversationClock(item.lastMessageAt)
            time.setTextColor(
                ContextCompat.getColor(ctx, if (hasUnread) R.color.ms_accent_gold else R.color.ms_text_tertiary)
            )

            // Preview
            preview.text = when {
                item.lastMessageType == "image" -> ctx.getString(R.string.messaging_photo_preview)
                item.lastMessageText.isNotBlank() -> item.lastMessageText
                else -> ctx.getString(R.string.messaging_start_conversation)
            }
            preview.typeface = ctx.resources.getFont(
                if (isImagePreview || hasUnread) R.font.manrope_semibold else R.font.manrope_regular
            )
            preview.setTextColor(
                ContextCompat.getColor(ctx, if (isImagePreview || hasUnread) R.color.ms_text_primary else R.color.ms_text_secondary)
            )

            // Unread badge
            if (hasUnread) {
                val count = unreadCount.coerceAtMost(99)
                unread.text = count.toString()
                unread.visibility = View.VISIBLE
                unread.background = ovalShape(ContextCompat.getColor(ctx, R.color.ms_accent_gold))
                unread.setTextColor(ContextCompat.getColor(ctx, R.color.ms_text_inverse))
            } else {
                unread.visibility = View.INVISIBLE
            }

            // Product chip
            val productName = item.product.title
            if (productName.isNotBlank()) {
                product.text = productName
                product.visibility = View.VISIBLE
                product.background = roundedRect(
                    fillColor = ContextCompat.getColor(ctx, R.color.ms_accent_gold_bg),
                    radiusDp = 6
                )
            } else {
                product.visibility = View.GONE
            }

            itemView.setOnClickListener { onClick(item) }
        }
    }

    // ── DiffUtil ──────────────────────────────────────────────────────────────

    private object ConversationDiff : DiffUtil.ItemCallback<Conversation>() {
        override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation): Boolean =
            oldItem == newItem
    }
}
