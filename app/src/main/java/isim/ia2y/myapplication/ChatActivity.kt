package isim.ia2y.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class ChatActivity : AppCompatActivity() {

    private val viewModel: ChatViewModel by viewModels()
    private lateinit var adapter: ChatAdapter

    private lateinit var recyclerView: RecyclerView
    private lateinit var inputField: EditText
    private lateinit var sendBtn: CardView
    private lateinit var backBtn: ImageView
    private lateinit var statusText: TextView
    private lateinit var emptyState: View
    private lateinit var errorCard: View
    private lateinit var errorText: TextView
    private lateinit var retryButton: MaterialButton

    private var lastSubmittedMessage: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.chatRoot)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
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
        setupRecycler()
        setupInput()
        observeViewModel()

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        viewModel.addWelcomeMessage()
    }

    private fun bindViews() {
        recyclerView = findViewById(R.id.chatRecyclerView)
        inputField = findViewById(R.id.chatInput)
        sendBtn = findViewById(R.id.chatBtnSend)
        backBtn = findViewById(R.id.chatBtnBack)
        statusText = findViewById(R.id.chatStatusText)
        emptyState = findViewById(R.id.chatEmptyState)
        errorCard = findViewById(R.id.chatErrorCard)
        errorText = findViewById(R.id.chatErrorText)
        retryButton = findViewById(R.id.chatRetryButton)

        backBtn.setOnClickListener { finish() }
        sendBtn.setOnClickListener { sendCurrentMessage() }
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
                        if (adapter.itemCount > 0) {
                            recyclerView.smoothScrollToPosition(adapter.itemCount - 1)
                        }
                    }
                }
                emptyState.visibility = if (messages.size <= 1) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.isLoading.collect { loading ->
                updateStatus(loading)
                sendBtn.isEnabled = !loading
                retryButton.isEnabled = !loading
                sendBtn.alpha = if (loading) 0.45f else if (inputField.text.isNullOrBlank()) 0.45f else 1f
            }
        }

        lifecycleScope.launch {
            viewModel.error.collect { error ->
                setError(error)
            }
        }
    }

    private fun updateStatus(isTyping: Boolean) {
        statusText.text = if (isTyping) getString(R.string.chat_status_typing)
        else getString(R.string.chat_status_online)
    }

    private fun setError(message: String?) {
        val hasError = !message.isNullOrBlank()
        errorCard.visibility = if (hasError) View.VISIBLE else View.GONE
        errorText.text = message.orEmpty()
        retryButton.visibility = if (hasError && !lastSubmittedMessage.isNullOrBlank()) View.VISIBLE else View.GONE
        if (!hasError) {
            viewModel.clearError()
        }
    }

    private fun bindSuggestion(buttonId: Int, prompt: String) {
        findViewById<View>(buttonId)?.setOnClickListener {
            inputField.setText(prompt)
            inputField.setSelection(prompt.length)
            sendCurrentMessage(prompt)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(inputField.windowToken, 0)
    }

    companion object {
        fun createIntent(from: android.content.Context): Intent =
            Intent(from, ChatActivity::class.java)
    }
}
