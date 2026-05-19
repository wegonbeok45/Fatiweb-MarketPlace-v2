package isim.ia2y.myapplication

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.ListenerRegistration

class MessagingInboxActivity : AppCompatActivity() {
    private enum class InboxFilter { ALL, UNREAD }

    private var listener: ListenerRegistration? = null
    private lateinit var adapter: MessagingInboxAdapter
    private lateinit var emptyText: TextView
    private lateinit var searchInput: EditText
    private lateinit var allChip: TextView
    private lateinit var unreadChip: TextView
    private lateinit var aiRow: View
    private var selectedFilter = InboxFilter.ALL
    private var conversations: List<Conversation> = emptyList()
    private val currentUid: String get() = FirebaseAuthManager.currentUser?.uid.orEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = buildUi()
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        if (!FirebaseAuthManager.isLoggedIn) {
            showMotionSnackbar(getString(R.string.messaging_login_required))
            navigateNoShift(LoginActivity::class.java)
            finish()
            return
        }
        listen()
    }

    override fun onDestroy() {
        listener?.remove()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(messagingColor(R.color.home_screen_bg))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22.dp, 34.dp, 20.dp, 12.dp)
        }
        top.addView(TextView(this).apply {
            text = getString(R.string.messaging_title)
            textSize = 34f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_bold)
            setTextColor(messagingColor(R.color.home_text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_profile_edit)
            imageTintList = ColorStateList.valueOf(messagingColor(R.color.home_text_primary))
            background = roundedRect(
                fillColor = messagingColor(R.color.colorSurface),
                radiusDp = 18,
                strokeColor = messagingColor(R.color.home_divider),
                strokeWidthDp = 1
            )
            setPadding(13.dp, 13.dp, 13.dp, 13.dp)
            setOnClickListener { showMotionSnackbar(getString(R.string.action_unavailable)) }
        }, LinearLayout.LayoutParams(56.dp, 56.dp))
        root.addView(top)

        searchInput = EditText(this).apply {
            hint = getString(R.string.messaging_search_hint)
            setSingleLine(true)
            textSize = 18f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_regular)
            setTextColor(messagingColor(R.color.home_text_primary))
            setHintTextColor(messagingColor(R.color.text_tertiary))
            setPadding(22.dp, 0, 18.dp, 0)
            minHeight = 70.dp
            background = roundedRect(messagingColor(R.color.colorSurface), 32)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search_20, 0, 0, 0)
            compoundDrawablePadding = 14.dp
            compoundDrawableTintList = ColorStateList.valueOf(messagingColor(R.color.surface_warm_muted))
            addTextChangedListener { filterConversations() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                70.dp
            ).apply {
                marginStart = 22.dp
                marginEnd = 22.dp
                topMargin = 16.dp
            }
        }
        root.addView(searchInput)

        val chips = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 28.dp, 0, 24.dp)
        }
        allChip = filterChip(getString(R.string.messaging_filter_all), InboxFilter.ALL)
        unreadChip = filterChip(getString(R.string.messaging_filter_unread), InboxFilter.UNREAD)
        chips.addView(allChip)
        chips.addView(unreadChip, LinearLayout.LayoutParams(118.dp, 56.dp).apply { marginStart = 14.dp })
        root.addView(chips, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val listArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        aiRow = aiSupportRow()
        listArea.addView(aiRow)

        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MessagingInboxActivity)
            adapter = MessagingInboxAdapter(currentUid) { conversation ->
                startActivity(ConversationActivity.createIntent(this@MessagingInboxActivity, conversation.id))
            }.also { this@MessagingInboxActivity.adapter = it }
            clipToPadding = false
            setPadding(0, 0, 0, 12.dp)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        listArea.addView(recycler)

        emptyText = TextView(this).apply {
            text = getString(R.string.messaging_empty_state)
            gravity = Gravity.CENTER
            setTextColor(messagingColor(R.color.home_text_secondary))
            typeface = resources.getFont(R.font.manrope_regular)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 72.dp)
        }
        listArea.addView(emptyText)
        root.addView(listArea)

        root.addView(TextView(this).apply {
            text = getString(R.string.messaging_encrypted)
            gravity = Gravity.CENTER
            textSize = 14f
            includeFontPadding = false
            setTextColor(messagingColor(R.color.text_tertiary))
            typeface = resources.getFont(R.font.manrope_regular)
            setPadding(16.dp, 12.dp, 16.dp, 24.dp)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        updateFilterChips()
        return root
    }

    private fun filterChip(label: String, filter: InboxFilter): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 16f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_semibold)
            minWidth = 118.dp
            minHeight = 56.dp
            setOnClickListener {
                selectedFilter = filter
                updateFilterChips()
                filterConversations()
            }
            layoutParams = LinearLayout.LayoutParams(118.dp, 56.dp)
        }

    private fun updateFilterChips() {
        fun render(chip: TextView, selected: Boolean) {
            chip.setTextColor(messagingColor(if (selected) R.color.home_text_primary else R.color.home_text_primary))
            chip.background = roundedRect(
                fillColor = messagingColor(if (selected) R.color.home_ref_gold_soft else R.color.home_screen_bg),
                radiusDp = 24,
                strokeColor = messagingColor(R.color.home_divider),
                strokeWidthDp = 1
            )
        }
        render(allChip, selectedFilter == InboxFilter.ALL)
        render(unreadChip, selectedFilter == InboxFilter.UNREAD)
    }

    private fun aiSupportRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22.dp, 16.dp, 22.dp, 20.dp)
            setOnClickListener { startActivity(ChatActivity.createIntent(this@MessagingInboxActivity)) }
        }
        val avatarWrap = FrameLayout(this).apply {
            background = ovalShape(messagingColor(R.color.home_ref_gold_soft))
        }
        avatarWrap.addView(ImageView(this).apply {
            setImageResource(R.drawable.ai_pfp)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(72.dp, 72.dp, Gravity.CENTER))
        avatarWrap.addView(View(this).apply {
            background = ovalShape(messagingColor(R.color.messaging_online_green), messagingColor(R.color.colorSurface), 3)
        }, FrameLayout.LayoutParams(18.dp, 18.dp, Gravity.END or Gravity.BOTTOM))
        row.addView(avatarWrap, LinearLayout.LayoutParams(72.dp, 72.dp))

        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 22.dp
            }
        }
        val titleLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleLine.addView(TextView(this).apply {
            text = getString(R.string.messaging_ai_title)
            textSize = 21f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_bold)
            setTextColor(messagingColor(R.color.home_text_primary))
            maxLines = 1
        })
        titleLine.addView(TextView(this).apply {
            text = getString(R.string.messaging_ai_short)
            gravity = Gravity.CENTER
            textSize = 13f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_semibold)
            setTextColor(messagingColor(R.color.home_ref_gold))
            background = roundedRect(messagingColor(R.color.home_ref_gold_soft), 11)
            setPadding(8.dp, 2.dp, 8.dp, 2.dp)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 24.dp).apply { marginStart = 10.dp })
        copy.addView(titleLine)
        copy.addView(TextView(this).apply {
            text = getString(R.string.messaging_ai_preview)
            textSize = 16f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_regular)
            setTextColor(messagingColor(R.color.home_text_secondary))
            setPadding(0, 12.dp, 0, 0)
            maxLines = 1
        })
        row.addView(copy)
        row.addView(TextView(this).apply {
            text = formatConversationClock(System.currentTimeMillis())
            textSize = 14f
            includeFontPadding = false
            setTextColor(messagingColor(R.color.home_text_primary))
            typeface = resources.getFont(R.font.manrope_regular)
            gravity = Gravity.TOP or Gravity.END
        }, LinearLayout.LayoutParams(52.dp, LinearLayout.LayoutParams.MATCH_PARENT))
        return row
    }

    private fun listen() {
        listener?.remove()
        listener = MessagingRepository.listenConversations(
            uid = currentUid,
            onChange = {
                conversations = it
                filterConversations()
            },
            onError = {
                CrashlyticsHelper.recordNonFatal("MessagingInbox", "Conversation listener failed", it)
                showMotionSnackbar(getString(R.string.messaging_load_failed))
            }
        )
    }

    private fun filterConversations() {
        val query = searchInput.text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = conversations
            .filter {
                selectedFilter == InboxFilter.ALL || (it.unreadCounts[currentUid] ?: 0) > 0
            }
            .filter {
                query.isBlank() ||
                    it.otherParticipantName(currentUid).lowercase().contains(query) ||
                    it.product.title.lowercase().contains(query) ||
                    it.lastMessageText.lowercase().contains(query)
            }
        aiRow.visibility = if (selectedFilter == InboxFilter.ALL) View.VISIBLE else View.GONE
        adapter.submitList(filtered)
        emptyText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, MessagingInboxActivity::class.java)
    }
}
