package isim.ia2y.myapplication

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class MessagingInboxAdapter(
    private val currentUid: String,
    private val onClick: (Conversation) -> Unit
) : RecyclerView.Adapter<MessagingInboxAdapter.Holder>() {
    private val items = mutableListOf<Conversation>()

    fun submitList(next: List<Conversation>) {
        items.clear()
        items += next
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 10.dp, 14.dp, 10.dp)
        }
        val avatarWrap = FrameLayout(context).apply {
            background = ovalShape(context.messagingColor(R.color.colorSurface))
        }
        val avatar = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ovalShape(context.messagingColor(R.color.colorSurface))
            clipToOutline = true
        }
        avatarWrap.addView(avatar, FrameLayout.LayoutParams(46.dp, 46.dp, Gravity.CENTER))
        val status = View(context).apply {
            background = ovalShape(context.messagingColor(R.color.messaging_online_green), context.messagingColor(R.color.colorSurface), 2)
        }
        avatarWrap.addView(status, FrameLayout.LayoutParams(12.dp, 12.dp, Gravity.END or Gravity.BOTTOM))
        row.addView(avatarWrap, LinearLayout.LayoutParams(46.dp, 46.dp))

        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12.dp
            }
        }
        val title = TextView(context).apply {
            textSize = 15f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_bold)
            setTextColor(context.messagingColor(R.color.home_text_primary))
            maxLines = 1
        }
        val preview = TextView(context).apply {
            textSize = 13f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_regular)
            setTextColor(context.messagingColor(R.color.home_text_secondary))
            maxLines = 1
            setPadding(0, 3.dp, 0, 0)
        }
        val product = TextView(context).apply {
            textSize = 11f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_regular)
            setTextColor(context.messagingColor(R.color.text_tertiary))
            maxLines = 1
            setPadding(0, 2.dp, 0, 0)
        }
        copy.addView(title)
        copy.addView(preview)
        copy.addView(product)
        row.addView(copy)

        val meta = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        val time = TextView(context).apply {
            textSize = 11f
            includeFontPadding = false
            setTextColor(context.messagingColor(R.color.home_text_primary))
            typeface = resources.getFont(R.font.manrope_regular)
            gravity = Gravity.END
        }
        val unread = TextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 11f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_bold)
            setTextColor(context.messagingColor(R.color.colorOnPrimary))
            background = ovalShape(context.messagingColor(R.color.home_ref_gold))
            visibility = View.INVISIBLE
        }
        meta.addView(time, LinearLayout.LayoutParams(48.dp, 16.dp))
        meta.addView(unread, LinearLayout.LayoutParams(20.dp, 20.dp).apply { topMargin = 8.dp })
        row.addView(meta)

        val divider = View(context).apply {
            setBackgroundColor(context.messagingColor(R.color.home_divider))
            alpha = 0.6f
        }
        outer.addView(row)
        outer.addView(divider, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dp).apply {
            marginStart = 14.dp
            marginEnd = 14.dp
        })
        return Holder(outer, avatar, title, preview, product, time, unread)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val unreadCount = item.unreadCounts[currentUid] ?: 0
        val hasUnreadImage = unreadCount > 0 && item.lastMessageType == "image"
        val hasUnread = unreadCount > 0
        holder.avatar.loadAvatarImage(item.otherParticipantAvatar(currentUid), 180)
        holder.title.text = item.otherParticipantName(currentUid)
        holder.title.typeface = context.resources.getFont(if (hasUnread) R.font.manrope_bold else R.font.manrope_semibold)
        holder.title.setTextColor(context.messagingColor(if (hasUnread) R.color.home_text_primary else R.color.home_text_primary))

        holder.preview.text = previewText(item, context)
        holder.preview.setTextColor(context.messagingColor(if (hasUnreadImage || hasUnread) R.color.home_text_primary else R.color.home_text_secondary))
        holder.preview.typeface = context.resources.getFont(if (hasUnread) R.font.manrope_semibold else R.font.manrope_regular)

        holder.product.text = item.product.title
        holder.product.visibility = if (item.product.title.isBlank()) View.GONE else View.VISIBLE
        holder.time.text = formatConversationClock(item.lastMessageAt)
        holder.time.setTextColor(context.messagingColor(if (hasUnread) R.color.home_text_primary else R.color.home_text_primary))
        holder.unread.text = unreadCount.coerceAtMost(99).toString()
        holder.unread.visibility = if (hasUnread) View.VISIBLE else View.INVISIBLE
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount(): Int = items.size

    private fun previewText(item: Conversation, context: android.content.Context): String =
        when {
            item.lastMessageType == "image" -> context.getString(R.string.messaging_photo_preview)
            item.lastMessageText.isNotBlank() -> item.lastMessageText
            else -> context.getString(R.string.messaging_start_conversation)
        }

    class Holder(
        itemView: View,
        val avatar: ImageView,
        val title: TextView,
        val preview: TextView,
        val product: TextView,
        val time: TextView,
        val unread: TextView
    ) : RecyclerView.ViewHolder(itemView)
}
