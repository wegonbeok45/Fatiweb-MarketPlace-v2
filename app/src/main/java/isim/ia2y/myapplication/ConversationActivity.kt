package isim.ia2y.myapplication

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConversationActivity : AppCompatActivity() {
    private lateinit var adapter: ConversationMessagesAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var input: EditText
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var avatar: ImageView
    private lateinit var menuBtn: TextView
    private lateinit var productImage: ImageView
    private lateinit var productTitle: TextView
    private lateinit var productPrice: TextView
    private lateinit var offlineBanner: TextView
    private var listener: ListenerRegistration? = null
    private var conversation: Conversation? = null
    private var olderCursor: DocumentSnapshot? = null
    private var messages: List<ConversationMessage> = emptyList()
    private val pending = linkedMapOf<String, ConversationMessage>()
    private val pendingImages = mutableMapOf<String, Uri>()
    private val conversationId: String by lazy { intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty() }
    private val currentUid: String get() = FirebaseAuthManager.currentUser?.uid.orEmpty()

    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) sendImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val root = buildUi()
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        if (!FirebaseAuthManager.isLoggedIn || conversationId.isBlank()) {
            finishWithMotion()
            return
        }
        // Load cache on IO thread to avoid blocking the main thread (file I/O +
        // JSON parse for up to 200 messages). The guard `messages.isEmpty()` ensures
        // that if the Firestore listener delivers its first snapshot before the IO
        // coroutine finishes, we never overwrite fresh remote data with stale cache.
        val appCtx = applicationContext
        lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) {
                ConversationCache.load(appCtx, conversationId)
            }
            if (cached.isNotEmpty() && messages.isEmpty()) {
                messages = cached
                renderMessages(scroll = true)
            }
        }
        loadConversation()
        listenMessages()
    }

    override fun onResume() {
        super.onResume()
        markRead()
    }

    override fun onDestroy() {
        listener?.remove()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val bg = ContextCompat.getColor(this, R.color.ms_surface_canvas)
        val surface = ContextCompat.getColor(this, R.color.ms_surface_card)
        val textPrimary = ContextCompat.getColor(this, R.color.ms_text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.ms_text_secondary)
        val textTertiary = ContextCompat.getColor(this, R.color.ms_text_tertiary)
        val gold = ContextCompat.getColor(this, R.color.ms_accent_gold)
        val goldSoft = ContextCompat.getColor(this, R.color.ms_accent_gold_bg)
        val border = ContextCompat.getColor(this, R.color.ms_border_subtle)
        val sunken = ContextCompat.getColor(this, R.color.ms_surface_sunken)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // ── Top bar ───────────────────────────────────────────────────────────
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            setBackgroundColor(surface)
            elevation = 2.dp.toFloat()
        }
        // Back button
        top.addView(ImageButton(this).apply {
            setImageResource(R.drawable.back)
            imageTintList = ColorStateList.valueOf(textPrimary)
            background = null
            setPadding(4.dp, 4.dp, 8.dp, 4.dp)
            setOnClickListener { finishWithMotion() }
        }, LinearLayout.LayoutParams(40.dp, 40.dp))

        // Avatar — tapping opens participant profile sheet
        avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ovalShape(sunken)
            clipToOutline = true
            setOnClickListener { showParticipantProfile() }
        }
        top.addView(avatar, LinearLayout.LayoutParams(40.dp, 40.dp).apply { marginStart = 4.dp })

        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 10.dp
            }
        }
        title = TextView(this).apply {
            textSize = 16f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_bold)
            setTextColor(textPrimary)
            maxLines = 1
        }
        subtitle = TextView(this).apply {
            text = getString(R.string.messaging_seller_reply_hint)
            textSize = 12f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_regular)
            setTextColor(textTertiary)
            setPadding(0, 2.dp, 0, 0)
            maxLines = 1
        }
        names.addView(title)
        names.addView(subtitle)
        top.addView(names)

        // 3-dot menu button — wired to a real PopupMenu
        menuBtn = TextView(this).apply {
            text = "⋮"
            gravity = Gravity.CENTER
            textSize = 22f
            includeFontPadding = false
            setTextColor(gold)
            typeface = resources.getFont(R.font.manrope_bold)
            setPadding(8.dp, 0, 4.dp, 0)
            setOnClickListener { showConversationMenu(it) }
        }
        top.addView(menuBtn, LinearLayout.LayoutParams(36.dp, 40.dp))
        root.addView(top)

        // ── Product context banner ────────────────────────────────────────────
        val productBanner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 10.dp, 14.dp, 10.dp)
            setBackgroundColor(bg)
            // Subtle bottom border
            foreground = null
            setOnClickListener {
                conversation?.productId?.takeIf { id -> id.isNotBlank() }?.let { productId ->
                    startActivity(ProductDetailsScreen.createIntent(this@ConversationActivity, productId))
                }
            }
        }
        productImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedRect(sunken, 8)
            clipToOutline = true
        }
        productBanner.addView(productImage, LinearLayout.LayoutParams(44.dp, 44.dp))
        val productCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12.dp
            }
        }
        productTitle = TextView(this).apply {
            textSize = 13f
            includeFontPadding = false
            setTextColor(textPrimary)
            typeface = resources.getFont(R.font.manrope_semibold)
            maxLines = 1
        }
        productPrice = TextView(this).apply {
            textSize = 13f
            includeFontPadding = false
            setTextColor(gold)
            typeface = resources.getFont(R.font.manrope_bold)
            setPadding(0, 3.dp, 0, 0)
        }
        productCopy.addView(productTitle)
        productCopy.addView(productPrice)
        productBanner.addView(productCopy)
        productBanner.addView(TextView(this).apply {
            text = "›"
            gravity = Gravity.CENTER
            textSize = 20f
            includeFontPadding = false
            setTextColor(textTertiary)
        }, LinearLayout.LayoutParams(20.dp, LinearLayout.LayoutParams.WRAP_CONTENT))
        // Bottom hairline
        val productWrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        productWrapper.addView(productBanner, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        productWrapper.addView(View(this).apply {
            setBackgroundColor(border)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dp))
        root.addView(productWrapper)

        // ── Offline banner ────────────────────────────────────────────────────
        offlineBanner = TextView(this).apply {
            text = getString(R.string.messaging_offline_banner)
            gravity = Gravity.CENTER
            visibility = View.GONE
            textSize = 12f
            setTextColor(textSecondary)
            setBackgroundColor(ContextCompat.getColor(this@ConversationActivity, R.color.ms_status_pending_bg))
            setPadding(12.dp, 7.dp, 12.dp, 7.dp)
        }
        root.addView(offlineBanner)

        // ── Messages RecyclerView ─────────────────────────────────────────────
        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ConversationActivity).apply { stackFromEnd = true }
            adapter = ConversationMessagesAdapter(
                currentUid = currentUid,
                onRetry = { retryMessage(it) },
                onToggleHeart = { toggleHeart(it) }
            ).also { this@ConversationActivity.adapter = it }
            clipToPadding = false
            setPadding(0, 6.dp, 0, 8.dp)
            setBackgroundColor(bg)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(recycler)

        // ── Top hairline above composer ───────────────────────────────────────
        root.addView(View(this).apply {
            setBackgroundColor(border)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1.dp))

        // ── Composer bar ──────────────────────────────────────────────────────
        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(12.dp, 8.dp, 12.dp, 12.dp)
            setBackgroundColor(surface)
        }
        composer.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_gallery)
            imageTintList = ColorStateList.valueOf(textSecondary)
            background = null
            setPadding(6.dp, 6.dp, 6.dp, 6.dp)
            setOnClickListener {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }, LinearLayout.LayoutParams(40.dp, 40.dp))
        input = EditText(this).apply {
            hint = getString(R.string.messaging_input_hint)
            maxLines = 4
            minHeight = 40.dp
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(false)
            textSize = 15f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_regular)
            setTextColor(textPrimary)
            setHintTextColor(textTertiary)
            setPadding(14.dp, 8.dp, 14.dp, 8.dp)
            background = roundedRect(sunken, 20)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    sendText()
                    true
                } else {
                    false
                }
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 8.dp
            marginEnd = 8.dp
        })
        composer.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_send)
            imageTintList = ColorStateList.valueOf(gold)
            background = ovalShape(goldSoft)
            setPadding(10.dp, 10.dp, 10.dp, 10.dp)
            setOnClickListener { sendText() }
        }, LinearLayout.LayoutParams(40.dp, 40.dp))
        root.addView(composer)
        return root
    }

    // ── Conversation menu ─────────────────────────────────────────────────────

    private fun showConversationMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.apply {
            add(0, MENU_HIDE, 0, getString(R.string.messaging_menu_hide))
            add(0, MENU_BLOCK, 1, getString(R.string.messaging_menu_block))
            add(0, MENU_REPORT, 2, getString(R.string.messaging_menu_report))
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_HIDE -> { hideConversation(); true }
                MENU_BLOCK -> { blockUser(); true }
                MENU_REPORT -> { reportConversation(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun hideConversation() {
        lifecycleScope.launch {
            runCatching { MessagingRepository.hide(conversationId) }
                .onSuccess { finishWithMotion() }
                .onFailure { showMotionSnackbar(getString(R.string.action_failed)) }
        }
    }

    private fun blockUser() {
        val otherId = conversation?.otherParticipantId(currentUid)?.takeIf { it.isNotBlank() } ?: return
        lifecycleScope.launch {
            runCatching { MessagingRepository.block(otherId) }
                .onSuccess {
                    showMotionSnackbar(getString(R.string.messaging_user_blocked))
                    finishWithMotion()
                }
                .onFailure { showMotionSnackbar(getString(R.string.action_failed)) }
        }
    }

    private fun reportConversation() {
        val lastMsg = messages.lastOrNull() ?: return
        lifecycleScope.launch {
            runCatching { MessagingRepository.report(conversationId, lastMsg.id, "inappropriate") }
                .onSuccess { showMotionSnackbar(getString(R.string.messaging_reported)) }
                .onFailure { showMotionSnackbar(getString(R.string.action_failed)) }
        }
    }

    // ── Participant profile sheet ──────────────────────────────────────────────

    private fun showParticipantProfile() {
        val conv = conversation ?: run {
            // Conversation not loaded yet — do nothing
            return
        }
        val name = conv.otherParticipantName(currentUid)
        val avatarUrl = conv.otherParticipantAvatar(currentUid)
        val otherId = conv.otherParticipantId(currentUid)

        val textPrimary = ContextCompat.getColor(this, R.color.ms_text_primary)
        val textSecondary = ContextCompat.getColor(this, R.color.ms_text_secondary)
        val sunken = ContextCompat.getColor(this, R.color.ms_surface_sunken)

        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheetView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(ContextCompat.getColor(this@ConversationActivity, R.color.ms_surface_card))
            setPadding(24.dp, 24.dp, 24.dp, 40.dp)
        }
        // Drag handle
        sheetView.addView(View(this).apply {
            background = roundedRect(ContextCompat.getColor(this@ConversationActivity, R.color.ms_border_default), 3)
        }, LinearLayout.LayoutParams(40.dp, 4.dp).also {
            it.gravity = Gravity.CENTER_HORIZONTAL
            it.bottomMargin = 20.dp
        })
        val sheetAvatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ovalShape(sunken)
            clipToOutline = true
            loadAvatarImage(avatarUrl, 240)
        }
        sheetView.addView(sheetAvatar, LinearLayout.LayoutParams(80.dp, 80.dp).also {
            it.gravity = Gravity.CENTER_HORIZONTAL
        })
        sheetView.addView(TextView(this).apply {
            text = name
            textSize = 20f
            gravity = Gravity.CENTER
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_bold)
            setTextColor(textPrimary)
            setPadding(0, 16.dp, 0, 0)
        })
        // Product context
        conv.product.title.takeIf { it.isNotBlank() }?.let { productName ->
            sheetView.addView(TextView(this).apply {
                text = getString(R.string.messaging_profile_product_context, productName)
                textSize = 13f
                gravity = Gravity.CENTER
                includeFontPadding = false
                typeface = resources.getFont(R.font.manrope_regular)
                setTextColor(textSecondary)
                setPadding(0, 6.dp, 0, 0)
            })
        }
        // Block action
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 28.dp, 0, 0)
        }
        actions.addView(com.google.android.material.button.MaterialButton(this).apply {
            text = getString(R.string.messaging_menu_block)
            textSize = 13f
            setOnClickListener {
                sheet.dismiss()
                blockUser()
            }
        })
        sheetView.addView(actions)
        sheet.setContentView(sheetView)
        sheet.show()
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private fun loadConversation() {
        lifecycleScope.launch {
            runCatching { MessagingRepository.getConversation(conversationId) }
                .onSuccess {
                    conversation = it
                    renderHeader(it)
                    markRead()
                }
                .onFailure {
                    CrashlyticsHelper.recordNonFatal("Conversation", "Conversation load failed", it)
                    showMotionSnackbar(getString(R.string.messaging_load_failed))
                }
        }
    }

    private fun renderHeader(item: Conversation) {
        title.text = item.otherParticipantName(currentUid)
        val otherAvatar = item.otherParticipantAvatar(currentUid)
        adapter.otherAvatarUrl = otherAvatar
        avatar.loadAvatarImage(otherAvatar, 160)
        productImage.loadCatalogImage(item.product.thumbnailUrl, R.drawable.placeholder, 180)
        productTitle.text = item.product.title.ifBlank { getString(R.string.messaging_product_chat) }
        productPrice.text = if (item.product.priceMinor > 0) formatMinorDt(item.product.priceMinor) else ""
    }

    private fun listenMessages() {
        listener?.remove()
        listener = MessagingRepository.listenMessages(
            conversationId = conversationId,
            onChange = { remote, cursor ->
                olderCursor = cursor
                messages = remote
                offlineBanner.visibility = View.GONE
                renderMessages(scroll = true)
                markRead()
                // Persist to disk so the next open shows history instantly
                val ctx = applicationContext
                lifecycleScope.launch(Dispatchers.IO) {
                    ConversationCache.save(ctx, conversationId, remote)
                }
            },
            onError = {
                offlineBanner.visibility = View.VISIBLE
                CrashlyticsHelper.recordNonFatal("Conversation", "Message listener failed", it)
            }
        )
    }

    private fun renderMessages(scroll: Boolean) {
        val merged = messages + pending.values.filter { local ->
            messages.none { it.clientMessageId == local.clientMessageId }
        }
        adapter.submitMessages(merged)
        if (scroll && merged.isNotEmpty()) {
            recycler.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun sendText() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        if (text.length > 2000) {
            showMotionSnackbar(getString(R.string.messaging_message_too_long))
            return
        }
        input.setText("")
        val id = MessagingRepository.newClientMessageId()
        pending[id] = ConversationMessage(
            id = id,
            conversationId = conversationId,
            senderId = currentUid,
            receiverId = conversation?.otherParticipantId(currentUid).orEmpty(),
            type = "text",
            text = text,
            imageUrl = "",
            thumbnailUrl = "",
            storagePath = "",
            createdAt = System.currentTimeMillis(),
            readBy = mapOf(currentUid to System.currentTimeMillis()),
            reactions = emptyMap(),
            deliveryStatus = "sending",
            clientMessageId = id,
            isLocalPending = true
        )
        renderMessages(scroll = true)
        lifecycleScope.launch {
            runCatching { MessagingRepository.sendText(conversationId, text) }
                .onSuccess {
                    pending.remove(id)
                    renderMessages(scroll = false)
                }
                .onFailure {
                    pending[id] = pending.getValue(id).copy(localError = "failed", deliveryStatus = "failed")
                    CrashlyticsHelper.recordNonFatal("Conversation", "Text message send failed", it)
                    renderMessages(scroll = false)
                }
        }
    }

    private fun sendImage(uri: Uri, existingId: String = MessagingRepository.newClientMessageId()) {
        pendingImages[existingId] = uri
        pending[existingId] = ConversationMessage(
            id = existingId,
            conversationId = conversationId,
            senderId = currentUid,
            receiverId = conversation?.otherParticipantId(currentUid).orEmpty(),
            type = "image",
            text = "",
            imageUrl = "",
            thumbnailUrl = "",
            storagePath = "",
            createdAt = System.currentTimeMillis(),
            readBy = mapOf(currentUid to System.currentTimeMillis()),
            reactions = emptyMap(),
            deliveryStatus = "sending",
            clientMessageId = existingId,
            isLocalPending = true,
            localProgress = 1
        )
        renderMessages(scroll = true)
        lifecycleScope.launch {
            runCatching {
                val upload = ChatMediaStorage.uploadImage(
                    context = this@ConversationActivity,
                    conversationId = conversationId,
                    uid = currentUid,
                    clientMessageId = existingId,
                    uri = uri
                ) { progress ->
                    pending[existingId] = pending.getValue(existingId).copy(localProgress = progress)
                    renderMessages(scroll = false)
                }
                MessagingRepository.sendImage(
                    conversationId = conversationId,
                    clientMessageId = existingId,
                    imageUrl = upload.imageUrl,
                    thumbnailUrl = upload.thumbnailUrl,
                    storagePath = upload.storagePath
                )
            }.onSuccess {
                pending.remove(existingId)
                pendingImages.remove(existingId)
                renderMessages(scroll = false)
            }.onFailure {
                pending[existingId] = pending.getValue(existingId).copy(localError = "failed", deliveryStatus = "failed")
                CrashlyticsHelper.recordNonFatal("Conversation", "Image message send failed", it)
                renderMessages(scroll = false)
            }
        }
    }

    private fun retryMessage(message: ConversationMessage) {
        if (message.type == "image") {
            pendingImages[message.clientMessageId]?.let { sendImage(it, message.clientMessageId) }
            return
        }
        input.setText(message.text)
        pending.remove(message.clientMessageId)
        renderMessages(scroll = false)
        sendText()
    }

    private fun toggleHeart(message: ConversationMessage) {
        lifecycleScope.launch {
            runCatching { MessagingRepository.toggleHeart(conversationId, message.id) }
                .onFailure {
                    CrashlyticsHelper.recordNonFatal("Conversation", "Message reaction failed", it)
                    showMotionSnackbar(getString(R.string.action_failed))
                }
        }
    }

    private fun markRead() {
        if (conversationId.isBlank()) return
        lifecycleScope.launch {
            runCatching { MessagingRepository.markRead(conversationId) }
        }
    }

    companion object {
        private const val EXTRA_CONVERSATION_ID = "extra_conversation_id"
        private const val MENU_HIDE = 1
        private const val MENU_BLOCK = 2
        private const val MENU_REPORT = 3

        fun createIntent(context: Context, conversationId: String): Intent =
            Intent(context, ConversationActivity::class.java)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId)
    }
}
