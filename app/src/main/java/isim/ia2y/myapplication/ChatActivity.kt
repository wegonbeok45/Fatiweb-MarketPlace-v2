@file:Suppress("DEPRECATION")

package isim.ia2y.myapplication

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.util.Locale

class ChatActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_PREFILL = "extra_prefill"
        private const val PREFS_CHAT_VOICE = "chat_voice_prefs"
        private const val KEY_CHAT_VOICE_DISABLED = "chat_voice_disabled"

        fun createIntent(from: android.content.Context, prefilledMessage: String? = null): Intent =
            Intent(from, ChatActivity::class.java).apply {
                putExtra(EXTRA_PREFILL, prefilledMessage)
            }
    }

    private val viewModel: ChatViewModel by viewModels()
    private lateinit var adapter: ChatAdapter

    private lateinit var recyclerView: RecyclerView
    private lateinit var inputField: EditText
    private lateinit var sendBtn: CardView
    private lateinit var voiceBtn: CardView
    private lateinit var voiceIcon: ImageView
    private lateinit var backBtn: ImageView
    private lateinit var statusText: TextView
    private lateinit var emptyState: View
    private lateinit var errorCard: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: MaterialButton

    private var assistantIsTyping = false
    private var lastSubmittedMessage: String? = null
    private var chatVoiceListening = false

    private val voicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted == true) {
            launchChatSpeechRecognizer()
        } else {
            setChatVoiceDisabled(true)
            updateChatVoiceAvailability()
            setChatVoiceListening(false)
            showMotionSnackbar(getString(R.string.chat_voice_permission_denied))
        }
    }

    override fun onResume() {
        super.onResume()
        if (this::voiceBtn.isInitialized) {
            updateChatVoiceAvailability()
        }
    }

    private val speechInputLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        setChatVoiceListening(false)
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult

        val spokenText = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()

        if (spokenText.isBlank()) {
            showMotionSnackbar(getString(R.string.chat_voice_no_match))
            return@registerForActivityResult
        }

        inputField.setText(spokenText)
        inputField.setSelection(spokenText.length)
        sendCurrentMessage(spokenText)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.chatRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            findViewById<View>(R.id.chatTrustCard).visibility = if (keyboardVisible) View.GONE else View.VISIBLE
            findViewById<View>(R.id.chatSuggestionRow).visibility = if (keyboardVisible) View.GONE else View.VISIBLE
            val inputBar = findViewById<View>(R.id.chatInputBar)
            inputBar.setPadding(
                inputBar.paddingLeft,
                inputBar.paddingTop,
                inputBar.paddingRight,
                systemBars.bottom + inputBar.paddingTop
            )
            insets
        }

        bindViews()
        updateChatVoiceAvailability()
        setupRecycler()
        setupInput()
        observeViewModel()

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        viewModel.addWelcomeMessage()
        intent.getStringExtra(EXTRA_PREFILL)
            ?.takeIf { it.isNotBlank() }
            ?.let { prompt ->
                recyclerView.post { sendCurrentMessage(prompt) }
            }
    }

    private fun bindViews() {
        recyclerView = findViewById(R.id.chatRecyclerView)
        inputField = findViewById(R.id.chatInput)
        sendBtn = findViewById(R.id.chatBtnSend)
        voiceBtn = findViewById(R.id.chatBtnVoice)
        voiceIcon = findViewById(R.id.chatVoiceIcon)
        backBtn = findViewById(R.id.chatBtnBack)
        statusText = findViewById(R.id.chatStatusText)
        emptyState = findViewById(R.id.chatEmptyState)
        errorCard = findViewById(R.id.chatErrorCard)
        errorText = findViewById(R.id.chatErrorText)
        retryButton = findViewById(R.id.chatRetryButton)

        backBtn.setOnClickListener { finish() }
        sendBtn.setOnClickListener { sendCurrentMessage() }
        voiceBtn.setOnClickListener { startChatVoiceInput() }
        retryButton.setOnClickListener {
            lastSubmittedMessage?.let(::sendCurrentMessage)
        }

        bindSuggestion(R.id.chatSuggestionProducts, getString(R.string.chat_prompt_products))
        bindSuggestion(R.id.chatSuggestionOrders, getString(R.string.chat_prompt_orders))
        bindSuggestion(R.id.chatSuggestionDelivery, getString(R.string.chat_prompt_delivery))
    }

    private fun setupRecycler() {
        adapter = ChatAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        recyclerView.adapter = adapter
    }

    private fun setupInput() {
        inputField.addTextChangedListener {
            val hasText = !it.isNullOrBlank()
            sendBtn.alpha = if (hasText) 1f else 0.45f
        }
        inputField.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                recyclerView.postDelayed({ scrollToLatestMessage(animated = false) }, 160)
            }
        }
        sendBtn.alpha = 0.45f
    }


    private fun sendCurrentMessage(prefilledText: String? = null) {
        val text = (prefilledText ?: inputField.text.toString()).trim()
        if (text.isBlank()) return
        inputField.setText("")
        hideKeyboard()
        lastSubmittedMessage = text
        setError(null)
        viewModel.sendMessage(text, FirebaseAuthManager.currentUser?.uid)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.messages.collect { messages ->
                adapter.submitList(messages.toList()) {
                    recyclerView.post {
                        scrollToLatestMessage()
                    }
                }
                emptyState.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                assistantIsTyping = loading
                updateStatus()
                sendBtn.isEnabled = !loading
                voiceBtn.isEnabled = !loading
                retryButton.isEnabled = !loading
                sendBtn.alpha = if (loading) 0.45f else if (inputField.text.isNullOrBlank()) 0.45f else 1f
                voiceBtn.alpha = if (loading) 0.45f else 1f
            }
        }

        lifecycleScope.launch {
            viewModel.error.collect { error ->
                setError(error)
            }
        }
    }


    private fun updateStatus() {
        statusText.text = when {
            chatVoiceListening -> getString(R.string.chat_voice_listening)
            assistantIsTyping -> getString(R.string.chat_status_typing)
            else -> getString(R.string.chat_status_online)
        }
    }

    private fun setError(message: String?, allowRetry: Boolean = true) {
        val hasError = !message.isNullOrBlank()
        errorCard.visibility = if (hasError) View.VISIBLE else View.GONE
        errorText.text = message.orEmpty()
        retryButton.visibility = if (hasError && allowRetry && !lastSubmittedMessage.isNullOrBlank()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        if (!hasError) {
            viewModel.clearError()
        }
    }

    private fun scrollToLatestMessage(animated: Boolean = true) {
        if (adapter.itemCount <= 0) return
        val lastPosition = adapter.itemCount - 1
        if (animated) {
            recyclerView.smoothScrollToPosition(lastPosition)
        } else {
            recyclerView.scrollToPosition(lastPosition)
        }
    }

    private fun bindSuggestion(buttonId: Int, prompt: String) {
        findViewById<View>(buttonId)?.setOnClickListener {
            inputField.setText(prompt)
            inputField.setSelection(prompt.length)
            sendCurrentMessage(prompt)
        }
    }

    private fun startChatVoiceInput() {
        if (assistantIsTyping) return
        if (isChatVoiceDisabled()) return

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showMotionSnackbar(getString(R.string.chat_voice_unavailable))
            return
        }

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasMicPermission) {
            launchChatSpeechRecognizer()
        } else {
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun launchChatSpeechRecognizer() {
        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.chat_voice_listening))
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }

        runCatching {
            setChatVoiceListening(true)
            hideKeyboard()
            speechInputLauncher.launch(recognizerIntent)
        }.onFailure {
            setChatVoiceListening(false)
            showMotionSnackbar(getString(R.string.chat_voice_unavailable))
        }
    }

    private fun setChatVoiceListening(listening: Boolean) {
        chatVoiceListening = listening
        voiceBtn.contentDescription = getString(
            if (listening) R.string.chat_voice_stop else R.string.chat_voice_start
        )
        statusText.text = when {
            listening -> getString(R.string.chat_voice_listening)
            assistantIsTyping -> getString(R.string.chat_status_typing)
            else -> getString(R.string.chat_status_online)
        }
        voiceBtn.setCardBackgroundColor(
            ContextCompat.getColor(
                this,
                if (listening) R.color.ms_surface_inverse else R.color.ms_surface_sunken
            )
        )
        voiceIcon.setColorFilter(
            ContextCompat.getColor(
                this,
                if (listening) R.color.ms_text_inverse else R.color.ms_surface_inverse
            )
        )
    }

    private fun updateChatVoiceAvailability() {
        val micGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (micGranted && isChatVoiceDisabled()) {
            getSharedPreferences(PREFS_CHAT_VOICE, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_CHAT_VOICE_DISABLED, false)
                .apply()
        }

        val voiceAvailable = SpeechRecognizer.isRecognitionAvailable(this) && !isChatVoiceDisabled()
        voiceBtn.visibility = if (voiceAvailable) View.VISIBLE else View.GONE
        voiceBtn.isEnabled = voiceAvailable

        findViewById<View>(R.id.chatInputContainer)
            .updateLayoutParams<ConstraintLayout.LayoutParams> {
                endToStart = if (voiceAvailable) R.id.chatBtnVoice else R.id.chatBtnSend
            }

        if (!voiceAvailable) {
            setChatVoiceListening(false)
        }
    }

    private fun isChatVoiceDisabled(): Boolean {
        return getSharedPreferences(PREFS_CHAT_VOICE, MODE_PRIVATE)
            .getBoolean(KEY_CHAT_VOICE_DISABLED, false)
    }

    private fun setChatVoiceDisabled(disabled: Boolean) {
        getSharedPreferences(PREFS_CHAT_VOICE, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CHAT_VOICE_DISABLED, disabled)
            .apply()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(inputField.windowToken, 0)
    }
}
