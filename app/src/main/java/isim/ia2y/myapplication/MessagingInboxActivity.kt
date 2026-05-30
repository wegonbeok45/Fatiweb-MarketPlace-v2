package isim.ia2y.myapplication

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.google.firebase.firestore.ListenerRegistration

class MessagingInboxActivity : AppCompatActivity() {
    private enum class InboxFilter { ALL, UNREAD }

    private var listener: ListenerRegistration? = null
    private lateinit var adapter: MessagingInboxAdapter
    private lateinit var emptyLayout: LinearLayout
    private lateinit var shimmerLayout: LinearLayout
    private lateinit var recycler: RecyclerView
    private lateinit var searchInput: EditText
    private lateinit var allChip: TextView
    private lateinit var unreadChip: TextView
    private lateinit var aiRow: View
    private var selectedFilter = InboxFilter.ALL
    private var conversations: List<Conversation> = emptyList()
    private var firstSnapshotReceived = false
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
        showShimmer(true)
        listen()
    }

    override fun onDestroy() {
        listener?.remove()
        super.onDestroy()
    }

    // ── UI build ──────────────────────────────────────────────────────────────

    private fun buildUi(): View {
        val bg = ContextCompat.getColor(this, R.color.ms_surface_canvas)
        val textPrimary = ContextCompat.getColor(this, R.color.ms_text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.ms_text_secondary)
        val textTertiary = ContextCompat.getColor(this, R.color.ms_text_tertiary)
        val surface = ContextCompat.getColor(this, R.color.ms_surface_card)
        val border = ContextCompat.getColor(this, R.color.ms_border_subtle)
        val gold = ContextCompat.getColor(this, R.color.ms_accent_gold)
        val goldBg = ContextCompat.getColor(this, R.color.ms_accent_gold_bg)
        val goldSoft = ContextCompat.getColor(this, R.color.ms_accent_gold_soft)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // ── Top header bar ────────────────────────────────────────────────────
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(22.dp, 36.dp, 20.dp, 14.dp)
        }
        top.addView(TextView(this).apply {
            text = getString(R.string.messaging_title)
            textSize = 32f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_bold)
            setTextColor(textPrimary)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_profile_edit)
            imageTintList = ColorStateList.valueOf(textPrimary)
            background = roundedRect(surface, 18, border, 1)
            setPadding(12.dp, 12.dp, 12.dp, 12.dp)
            contentDescription = getString(R.string.cd_menu)
            setOnClickListener { showMotionSnackbar(getString(R.string.action_unavailable)) }
        }, LinearLayout.LayoutParams(48.dp, 48.dp))
        root.addView(top)

        // ── Search bar ────────────────────────────────────────────────────────
        searchInput = EditText(this).apply {
            hint = getString(R.string.messaging_search_hint)
            setSingleLine(true)
            textSize = 16f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_regular)
            setTextColor(textPrimary)
            setHintTextColor(textTertiary)
            setPadding(20.dp, 0, 16.dp, 0)
            minHeight = 52.dp
            background = roundedRect(surface, 26, border, 1)
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_search_20, 0, 0, 0)
            compoundDrawablePadding = 12.dp
            compoundDrawableTintList = ColorStateList.valueOf(textTertiary)
            addTextChangedListener { filterConversations() }
        }
        root.addView(searchInput, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = 20.dp
            marginEnd = 20.dp
            topMargin = 4.dp
        })

        // ── Filter chips ──────────────────────────────────────────────────────
        val chips = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(20.dp, 20.dp, 20.dp, 16.dp)
        }
        allChip = makeChip(getString(R.string.messaging_filter_all), InboxFilter.ALL)
        unreadChip = makeChip(getString(R.string.messaging_filter_unread), InboxFilter.UNREAD)
        chips.addView(allChip)
        chips.addView(unreadChip, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            40.dp
        ).apply { marginStart = 10.dp })
        root.addView(chips, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        // ── List area (AI row + RecyclerView + shimmer + empty) ───────────────
        val listArea = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }

        // AI support row — pinned above the RecyclerView
        val scrollWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        aiRow = buildAiRow(textPrimary, textSecondary, textTertiary, gold, goldBg, goldSoft, surface, border)
        scrollWrapper.addView(aiRow)

        // RecyclerView
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MessagingInboxActivity)
            adapter = MessagingInboxAdapter(currentUid) { conversation ->
                startActivity(ConversationActivity.createIntent(this@MessagingInboxActivity, conversation.id))
            }.also { this@MessagingInboxActivity.adapter = it }
            clipToPadding = false
            setPadding(0, 0, 0, 16.dp)
        }
        scrollWrapper.addView(recycler, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        listArea.addView(scrollWrapper)

        // ── Empty state (Lottie + text) ────────────────────────────────────────
        emptyLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val lottieEmpty = LottieAnimationView(this).apply {
            setAnimation(R.raw.anim_empty_orders)
            repeatCount = com.airbnb.lottie.LottieDrawable.INFINITE
            playAnimation()
        }
        emptyLayout.addView(lottieEmpty, LinearLayout.LayoutParams(140.dp, 140.dp).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        emptyLayout.addView(TextView(this).apply {
            text = getString(R.string.messaging_empty_title)
            textSize = 18f
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_bold)
            setTextColor(ContextCompat.getColor(this@MessagingInboxActivity, R.color.ms_text_primary))
            setPadding(24.dp, 20.dp, 24.dp, 0)
        })
        emptyLayout.addView(TextView(this).apply {
            text = getString(R.string.messaging_empty_subtitle)
            textSize = 14f
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_regular)
            setTextColor(ContextCompat.getColor(this@MessagingInboxActivity, R.color.ms_text_secondary))
            setPadding(32.dp, 8.dp, 32.dp, 0)
        })
        listArea.addView(emptyLayout)

        // ── Shimmer loading skeleton ──────────────────────────────────────────
        shimmerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        repeat(6) {
            val shimmerItem = LayoutInflater.from(this)
                .inflate(R.layout.item_messaging_inbox_shimmer, shimmerLayout, false)
            shimmerLayout.addView(shimmerItem)
        }
        listArea.addView(shimmerLayout)

        root.addView(listArea)

        // ── Footer caption ────────────────────────────────────────────────────
        root.addView(TextView(this).apply {
            text = getString(R.string.messaging_encrypted)
            gravity = Gravity.CENTER
            textSize = 12f
            includeFontPadding = false
            setTextColor(textTertiary)
            typeface = resources.getFont(R.font.manrope_regular)
            setPadding(16.dp, 10.dp, 16.dp, 20.dp)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        updateFilterChips()
        return root
    }

    private fun makeChip(label: String, filter: InboxFilter): TextView =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_semibold)
            minWidth = 90.dp
            minHeight = 40.dp
            setPadding(18.dp, 0, 18.dp, 0)
            setOnClickListener {
                selectedFilter = filter
                updateFilterChips()
                filterConversations()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, 40.dp
            )
        }

    private fun updateFilterChips() {
        val gold = ContextCompat.getColor(this, R.color.ms_accent_gold)
        val goldBg = ContextCompat.getColor(this, R.color.ms_accent_gold_bg)
        val surface = ContextCompat.getColor(this, R.color.ms_surface_canvas)
        val border = ContextCompat.getColor(this, R.color.ms_border_subtle)
        val textPrimary = ContextCompat.getColor(this, R.color.ms_text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.ms_text_secondary)

        fun render(chip: TextView, selected: Boolean) {
            chip.background = roundedRect(
                fillColor = if (selected) goldBg else surface,
                radiusDp = 20,
                strokeColor = if (selected) gold else border,
                strokeWidthDp = 1
            )
            chip.setTextColor(if (selected) textPrimary else textSecondary)
        }
        render(allChip, selectedFilter == InboxFilter.ALL)
        render(unreadChip, selectedFilter == InboxFilter.UNREAD)
    }

    private fun buildAiRow(
        textPrimary: Int, textSecondary: Int, textTertiary: Int,
        gold: Int, goldBg: Int, goldSoft: Int, surface: Int, border: Int
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp, 14.dp, 20.dp, 16.dp)
            background = roundedRect(surface, 0, border, 0)
            setOnClickListener { startActivity(ChatActivity.createIntent(this@MessagingInboxActivity)) }
        }
        val avatarWrap = FrameLayout(this).apply {
            background = ovalShape(goldBg)
        }
        avatarWrap.addView(ImageView(this).apply {
            setImageResource(R.drawable.ai_pfp)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }, FrameLayout.LayoutParams(64.dp, 64.dp, Gravity.CENTER))
        avatarWrap.addView(View(this).apply {
            background = ovalShape(
                ContextCompat.getColor(this@MessagingInboxActivity, R.color.messaging_online_green),
                surface, 2
            )
        }, FrameLayout.LayoutParams(14.dp, 14.dp, Gravity.END or Gravity.BOTTOM))
        row.addView(avatarWrap, LinearLayout.LayoutParams(64.dp, 64.dp))

        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 16.dp
            }
        }
        val titleLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleLine.addView(TextView(this).apply {
            text = getString(R.string.messaging_ai_title)
            textSize = 17f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_bold)
            setTextColor(textPrimary)
            maxLines = 1
        })
        titleLine.addView(TextView(this).apply {
            text = getString(R.string.messaging_ai_short)
            gravity = Gravity.CENTER
            textSize = 11f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_bold)
            setTextColor(gold)
            background = roundedRect(goldBg, 8)
            setPadding(7.dp, 2.dp, 7.dp, 2.dp)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 22.dp).apply {
            marginStart = 8.dp
        })
        copy.addView(titleLine)
        copy.addView(TextView(this).apply {
            text = getString(R.string.messaging_ai_preview)
            textSize = 13f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_regular)
            setTextColor(textSecondary)
            setPadding(0, 5.dp, 0, 0)
            maxLines = 1
        })
        row.addView(copy)
        row.addView(TextView(this).apply {
            text = formatConversationClock(System.currentTimeMillis())
            textSize = 11f
            includeFontPadding = false
            setTextColor(textTertiary)
            typeface = resources.getFont(R.font.manrope_regular)
            gravity = Gravity.TOP or Gravity.END
        }, LinearLayout.LayoutParams(44.dp, LinearLayout.LayoutParams.MATCH_PARENT))

        // Bottom divider
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        wrapper.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        wrapper.addView(View(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MessagingInboxActivity, R.color.ms_border_subtle))
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dp))
        return wrapper
    }

    // ── Loading states ────────────────────────────────────────────────────────

    private fun showShimmer(show: Boolean) {
        shimmerLayout.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            emptyLayout.visibility = View.GONE
            recycler.visibility = View.GONE
            aiRow.visibility = View.GONE
        }
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private fun listen() {
        listener?.remove()
        listener = MessagingRepository.listenConversations(
            uid = currentUid,
            onChange = { list ->
                if (!firstSnapshotReceived) {
                    firstSnapshotReceived = true
                    showShimmer(false)
                    recycler.visibility = View.VISIBLE
                    aiRow.visibility = View.VISIBLE
                }
                conversations = list
                filterConversations()
            },
            onError = {
                showShimmer(false)
                recycler.visibility = View.VISIBLE
                aiRow.visibility = View.VISIBLE
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
        emptyLayout.visibility = if (filtered.isEmpty() && firstSnapshotReceived) View.VISIBLE else View.GONE
    }

    companion object {
        fun createIntent(context: Context): Intent = Intent(context, MessagingInboxActivity::class.java)
    }
}
