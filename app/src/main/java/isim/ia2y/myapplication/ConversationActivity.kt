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
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(messagingColor(R.color.home_screen_bg))
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 12.dp, 14.dp, 10.dp)
            setBackgroundColor(messagingColor(R.color.home_screen_bg))
        }
        avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ovalShape(messagingColor(R.color.colorSurface))
            clipToOutline = true
            setOnClickListener { finishWithMotion() }
        }
        top.addView(avatar, LinearLayout.LayoutParams(42.dp, 42.dp))
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12.dp
            }
        }
        title = TextView(this).apply {
            textSize = 17f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_bold)
            setTextColor(messagingColor(R.color.home_text_primary))
            maxLines = 1
        }
        subtitle = TextView(this).apply {
            text = "seller usually replies soon"
            textSize = 12f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_regular)
            setTextColor(messagingColor(R.color.home_text_secondary))
            setPadding(0, 2.dp, 0, 0)
            maxLines = 1
        }
        names.addView(title)
        names.addView(subtitle)
        top.addView(names)
        top.addView(TextView(this).apply {
            text = "\u22EE"
            gravity = Gravity.CENTER
            textSize = 22f
            includeFontPadding = false
            setTextColor(messagingColor(R.color.home_ref_gold))
            typeface = resources.getFont(R.font.manrope_bold)
        }, LinearLayout.LayoutParams(32.dp, 40.dp))
        root.addView(top)

        val product = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14.dp, 8.dp, 14.dp, 8.dp)
            setBackgroundColor(messagingColor(R.color.colorSurface))
            elevation = 1.dp.toFloat()
            setOnClickListener {
                conversation?.productId?.takeIf { id -> id.isNotBlank() }?.let { productId ->
                    startActivity(ProductDetailsScreen.createIntent(this@ConversationActivity, productId))
                }
            }
        }
        productImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = roundedRect(messagingColor(R.color.home_screen_bg), 6)
            clipToOutline = true
        }
        product.addView(productImage, LinearLayout.LayoutParams(40.dp, 40.dp))
        val productCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = 12.dp
            }
        }
        productTitle = TextView(this).apply {
            textSize = 13f
            includeFontPadding = false
            setTextColor(messagingColor(R.color.home_text_primary))
            typeface = resources.getFont(R.font.manrope_semibold)
            maxLines = 1
        }
        productPrice = TextView(this).apply {
            textSize = 13f
            includeFontPadding = false
            setTextColor(messagingColor(R.color.home_ref_gold))
            typeface = resources.getFont(R.font.manrope_bold)
            setPadding(0, 2.dp, 0, 0)
        }
        productCopy.addView(productTitle)
        productCopy.addView(productPrice)
        product.addView(productCopy)
        product.addView(TextView(this).apply {
            text = ">"
            gravity = Gravity.CENTER
            textSize = 18f
            includeFontPadding = false
            setTextColor(messagingColor(R.color.home_text_secondary))
        }, LinearLayout.LayoutParams(20.dp, 40.dp))
        root.addView(product)

        offlineBanner = TextView(this).apply {
            text = "Offline. New messages will sync when you reconnect."
            gravity = Gravity.CENTER
            visibility = View.GONE
            setTextColor(messagingColor(R.color.home_text_secondary))
            setPadding(10.dp, 5.dp, 10.dp, 5.dp)
        }
        root.addView(offlineBanner)

        recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@ConversationActivity).apply { stackFromEnd = true }
            adapter = ConversationMessagesAdapter(
                currentUid = currentUid,
                onRetry = { retryMessage(it) },
                onToggleHeart = { toggleHeart(it) }
            ).also { this@ConversationActivity.adapter = it }
            clipToPadding = false
            setPadding(0, 6.dp, 0, 8.dp)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        root.addView(recycler)

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 6.dp, 12.dp, 10.dp)
            setBackgroundColor(messagingColor(R.color.home_screen_bg))
        }
        composer.addView(ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_gallery)
            imageTintList = ColorStateList.valueOf(messagingColor(R.color.home_ref_gold))
            background = roundedRect(messagingColor(R.color.home_screen_bg), 8)
            setPadding(6.dp, 6.dp, 6.dp, 6.dp)
            setOnClickListener {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
        }, LinearLayout.LayoutParams(36.dp, 36.dp))
        input = EditText(this).apply {
            hint = "Message"
            maxLines = 4
            minHeight = 40.dp
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(false)
            textSize = 14f
            includeFontPadding = false
            typeface = resources.getFont(R.font.manrope_regular)
            setTextColor(messagingColor(R.color.home_text_primary))
            setHintTextColor(messagingColor(R.color.text_tertiary))
            setPadding(14.dp, 6.dp, 14.dp, 6.dp)
            background = roundedRect(messagingColor(R.color.colorSurface), 20)
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
            imageTintList = ColorStateList.valueOf(messagingColor(R.color.home_ref_gold))
            background = ovalShape(messagingColor(R.color.home_ref_gold_soft))
            setPadding(9.dp, 9.dp, 9.dp, 9.dp)
            setOnClickListener { sendText() }
        }, LinearLayout.LayoutParams(40.dp, 40.dp))
        root.addView(composer)
        return root
    }

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
                    showMotionSnackbar("Could not open conversation")
                }
        }
    }

    private fun renderHeader(item: Conversation) {
        title.text = item.otherParticipantName(currentUid)
        val otherAvatar = item.otherParticipantAvatar(currentUid)
        adapter.otherAvatarUrl = otherAvatar
        avatar.loadAvatarImage(otherAvatar, 160)
        productImage.loadCatalogImage(item.product.thumbnailUrl, R.drawable.placeholder, 180)
        productTitle.text = item.product.title.ifBlank { "Product" }
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
                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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
        adapter.submitList(merged)
        if (scroll && merged.isNotEmpty()) {
            recycler.scrollToPosition(adapter.itemCount - 1)
        }
    }

    private fun sendText() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isBlank()) return
        if (text.length > 2000) {
            showMotionSnackbar("Message is too long")
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
                    showMotionSnackbar("Could not react to message")
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

        fun createIntent(context: Context, conversationId: String): Intent =
            Intent(context, ConversationActivity::class.java)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId)
    }
}
