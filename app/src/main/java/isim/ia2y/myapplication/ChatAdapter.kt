package isim.ia2y.myapplication

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class ChatAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val VIEW_USER = 0
        private const val VIEW_BOT = 1
        private const val VIEW_BOT_LOADING = 2

        private val DIFF = object : DiffUtil.ItemCallback<ChatMessage>() {
            override fun areItemsTheSame(a: ChatMessage, b: ChatMessage) = a.id == b.id
            override fun areContentsTheSame(a: ChatMessage, b: ChatMessage) = a == b
        }
    }

    override fun getItemViewType(position: Int): Int {
        val msg = getItem(position)
        return when {
            msg.role == ChatMessage.Role.USER -> VIEW_USER
            msg.isLoading -> VIEW_BOT_LOADING
            else -> VIEW_BOT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_USER -> UserVH(inflater.inflate(R.layout.item_chat_user, parent, false))
            VIEW_BOT_LOADING -> BotLoadingVH(inflater.inflate(R.layout.item_chat_bot_loading, parent, false))
            else -> BotVH(inflater.inflate(R.layout.item_chat_bot, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = getItem(position)
        when (holder) {
            is UserVH -> holder.bind(msg)
            is BotVH -> holder.bind(msg)
            is BotLoadingVH -> holder.bind()
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        super.onViewRecycled(holder)
        if (holder is BotLoadingVH) {
            holder.cleanup()
        }
    }

    // ─── ViewHolders ──────────────────────────────────────────────────────────

    class UserVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvText: TextView = view.findViewById(R.id.chatUserText)
        fun bind(msg: ChatMessage) {
            tvText.text = msg.text
        }
    }

    class BotVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvText: TextView = view.findViewById(R.id.chatBotText)
        fun bind(msg: ChatMessage) {
            tvText.text = msg.text
        }
    }

    class BotLoadingVH(view: View) : RecyclerView.ViewHolder(view) {
        private val dot1: View = view.findViewById(R.id.chatDot1)
        private val dot2: View = view.findViewById(R.id.chatDot2)
        private val dot3: View = view.findViewById(R.id.chatDot3)
        private val animators = mutableListOf<AnimatorSet>()

        fun bind() {
            if (animators.any { it.isStarted }) return
            val dots = listOf(dot1, dot2, dot3)
            dots.forEachIndexed { i, dot ->
                dot.visibility = View.VISIBLE
                dot.alpha = 0.35f
                dot.translationY = 0f
                val alpha = ObjectAnimator.ofFloat(dot, View.ALPHA, 0.35f, 1f).apply {
                    duration = 420
                    startDelay = (i * 200).toLong()
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                }
                val bounce = ObjectAnimator.ofFloat(dot, View.TRANSLATION_Y, 0f, -8f).apply {
                    duration = 420
                    startDelay = (i * 200).toLong()
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                }
                AnimatorSet().apply {
                    playTogether(alpha, bounce)
                    start()
                    animators.add(this)
                }
            }
        }

        fun cleanup() {
            animators.forEach { it.cancel() }
            animators.clear()
            listOf(dot1, dot2, dot3).forEach { dot ->
                dot.alpha = 1f
                dot.translationY = 0f
            }
        }
    }
}
