package isim.ia2y.myapplication

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ConversationMessagesAdapter(
    private val currentUid: String,
    private val onRetry: (ConversationMessage) -> Unit,
    private val onToggleHeart: (ConversationMessage) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    var otherAvatarUrl: String = ""
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    private val rows = mutableListOf<Row>()

    fun submitList(next: List<ConversationMessage>) {
        rows.clear()
        var lastDay = ""
        next.forEach { message ->
            val day = formatConversationDay(message.createdAt)
            if (day.isNotBlank() && day != lastDay) {
                rows += Row.Day(day)
                lastDay = day
            }
            rows += Row.Message(message)
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Day -> VIEW_DAY
        is Row.Message -> VIEW_MESSAGE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_DAY) {
            DayHolder(createDayView(parent))
        } else {
            MessageHolder(createMessageView(parent))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Day -> (holder as DayHolder).bind(row.label)
            is Row.Message -> (holder as MessageHolder).bind(row.message)
        }
    }

    override fun getItemCount(): Int = rows.size

    private fun createDayView(parent: ViewGroup): View {
        val context = parent.context
        return FrameLayout(context).apply {
            setPadding(0, 6.dp, 0, 8.dp)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(TextView(context).apply {
                id = R.id.messagingDayLabel
                gravity = Gravity.CENTER
                textSize = 12f
                includeFontPadding = false
                typeface = resources.getFont(R.font.manrope_regular)
                setTextColor(context.messagingColor(R.color.home_text_secondary))
                background = roundedRect(
                    fillColor = context.messagingColor(R.color.home_screen_bg),
                    radiusDp = 14,
                    strokeColor = context.messagingColor(R.color.home_divider)
                )
                setPadding(12.dp, 5.dp, 12.dp, 5.dp)
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, 28.dp, Gravity.CENTER))
        }
    }

    private fun createMessageView(parent: ViewGroup): View {
        val context = parent.context
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 2.dp, 12.dp, 2.dp)
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val line = LinearLayout(context).apply {
            id = R.id.messagingMessageLine
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.BOTTOM
        }
        val avatar = ImageView(context).apply {
            id = R.id.messagingMessageAvatar
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ovalShape(context.messagingColor(R.color.colorSurface))
            clipToOutline = true
        }
        val bubble = LinearLayout(context).apply {
            id = R.id.messagingMessageBubble
            orientation = LinearLayout.VERTICAL
            setPadding(12.dp, 8.dp, 12.dp, 7.dp)
        }
        val image = ImageView(context).apply {
            id = R.id.messagingMessageImage
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
            background = roundedRect(context.messagingColor(R.color.home_screen_bg), 18)
            clipToOutline = true
        }
        val text = TextView(context).apply {
            id = R.id.messagingMessageText
            textSize = 15f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_regular)
            setLineSpacing(0f, 1.08f)
        }
        val meta = TextView(context).apply {
            id = R.id.messagingMessageMeta
            textSize = 10f
            includeFontPadding = false
            gravity = Gravity.END
            setPadding(0, 3.dp, 0, 0)
            typeface = resources.getFont(R.font.manrope_regular)
        }
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            id = R.id.messagingMessageProgress
            visibility = View.GONE
            max = 100
            setPadding(0, 8.dp, 0, 0)
        }
        bubble.addView(image)
        bubble.addView(text)
        bubble.addView(meta)
        bubble.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 4.dp))
        line.addView(avatar, LinearLayout.LayoutParams(28.dp, 28.dp).apply {
            marginEnd = 6.dp
            bottomMargin = 1.dp
        })
        line.addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(line)
        root.addView(TextView(context).apply {
            id = R.id.messagingMessageReaction
            this.text = "\u2764\uFE0F"
            gravity = Gravity.CENTER
            textSize = 17f
            includeFontPadding = false
            background = roundedRect(
                fillColor = context.messagingColor(R.color.colorSurface),
                radiusDp = 18,
                strokeColor = context.messagingColor(R.color.home_divider)
            )
            visibility = View.GONE
        }, LinearLayout.LayoutParams(56.dp, 36.dp).apply { topMargin = (-3).dp })
        return root
    }

    inner class DayHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val label: TextView = view.findViewById(R.id.messagingDayLabel)
        fun bind(value: String) {
            label.text = value
        }
    }

    inner class MessageHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val line: LinearLayout = view.findViewById(R.id.messagingMessageLine)
        private val avatar: ImageView = view.findViewById(R.id.messagingMessageAvatar)
        private val bubble: LinearLayout = view.findViewById(R.id.messagingMessageBubble)
        private val image: ImageView = view.findViewById(R.id.messagingMessageImage)
        private val text: TextView = view.findViewById(R.id.messagingMessageText)
        private val meta: TextView = view.findViewById(R.id.messagingMessageMeta)
        private val progress: ProgressBar = view.findViewById(R.id.messagingMessageProgress)
        private val reaction: TextView = view.findViewById(R.id.messagingMessageReaction)
        private var lastTapAt = 0L

        fun bind(message: ConversationMessage) {
            val context = itemView.context
            val mine = message.senderId == currentUid
            val maxBubbleWidth = (context.resources.displayMetrics.widthPixels * 0.68f).toInt()
            line.gravity = if (mine) Gravity.END or Gravity.BOTTOM else Gravity.START or Gravity.BOTTOM
            avatar.visibility = if (mine) View.GONE else View.VISIBLE
            if (!mine) avatar.loadAvatarImage(otherAvatarUrl, 96)
            bubble.background = roundedRect(
                fillColor = context.messagingColor(if (mine) R.color.messaging_bubble_mine else R.color.colorSurface),
                radiusDp = 18
            )
            val bubbleParams = bubble.layoutParams as LinearLayout.LayoutParams
            bubbleParams.marginStart = if (mine) 36.dp else 0
            bubbleParams.marginEnd = if (mine) 0 else 36.dp
            bubble.layoutParams = bubbleParams

            text.maxWidth = maxBubbleWidth
            meta.maxWidth = maxBubbleWidth
            val isImage = message.type == "image"
            image.visibility = if (isImage) View.VISIBLE else View.GONE
            if (isImage) {
                val side = minOf(230.dp, (context.resources.displayMetrics.widthPixels * 0.58f).toInt())
                image.layoutParams = LinearLayout.LayoutParams(side, (side * 0.82f).toInt()).apply {
                    bottomMargin = if (message.text.isBlank()) 0 else 10.dp
                }
                image.loadCatalogImage(
                    message.thumbnailUrl.ifBlank { message.imageUrl },
                    R.drawable.placeholder,
                    520
                )
            }

            val rawText = when {
                message.localError != null -> "${message.text.ifBlank { "Photo" }}\nTap to retry"
                isImage && message.text.isBlank() -> ""
                else -> message.text
            }
            text.visibility = if (rawText.isBlank()) View.GONE else View.VISIBLE
            text.setDtHighlightedText(
                rawText = rawText,
                textColor = context.messagingColor(R.color.home_text_primary),
                amountColor = context.messagingColor(R.color.home_ref_gold)
            )
            meta.setTextColor(context.messagingColor(R.color.home_text_secondary))
            meta.text = buildString {
                append(formatConversationClock(message.createdAt))
                if (message.isLocalPending && message.localError == null) append(" sending")
                if (mine && message.deliveryStatus == "sent" && !message.isLocalPending) append(" \u2713\u2713")
            }
            progress.visibility = if (message.isLocalPending && message.localProgress in 1..99) View.VISIBLE else View.GONE
            progress.progress = message.localProgress
            reaction.visibility = if (message.reactions.values.any { it == "heart" }) View.VISIBLE else View.GONE
            val reactionParams = reaction.layoutParams as LinearLayout.LayoutParams
            reactionParams.gravity = if (mine) Gravity.END else Gravity.START
            reactionParams.marginStart = if (mine) 0 else 48.dp
            reactionParams.marginEnd = if (mine) 8.dp else 0
            reaction.layoutParams = reactionParams

            itemView.setOnClickListener {
                if (message.localError != null) onRetry(message)
            }
            bubble.setOnClickListener {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastTapAt <= DOUBLE_TAP_MS && !message.isLocalPending) {
                    onToggleHeart(message)
                    lastTapAt = 0L
                } else {
                    lastTapAt = now
                }
            }
        }
    }

    private sealed class Row {
        data class Day(val label: String) : Row()
        data class Message(val message: ConversationMessage) : Row()
    }

    companion object {
        private const val VIEW_DAY = 0
        private const val VIEW_MESSAGE = 1
        private const val DOUBLE_TAP_MS = 320L
    }
}
