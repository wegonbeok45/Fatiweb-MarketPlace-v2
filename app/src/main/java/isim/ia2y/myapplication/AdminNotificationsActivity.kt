package isim.ia2y.myapplication

import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch

class AdminNotificationsActivity : AppCompatActivity() {
    private data class AudienceOption(val key: String, val label: String)

    companion object {
        private const val TAG = "AdminNotifications"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_notifications)
        setupAdminWindowInsets(R.id.adminNotifAppBar)
        setupTopBar()
        setupAdminBottomNav(AdminNavTab.NOTIFICATIONS)

        lifecycleScope.launch {
            if (!requireAdminRole()) return@launch

            if (savedInstanceState == null) {
                revealViewsInOrder(
                    R.id.adminNotifTopBar,
                    R.id.adminNotifComposeCard,
                    R.id.adminNotifHistoryCard,
                    startDelayMs = 60L,
                    staggerMs = 48L
                )
            }
            setupComposer()
            loadHistory()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.NOTIFICATIONS)
    }

    private fun setupComposer() {
        val titleInput = findViewById<EditText?>(R.id.adminNotifEtTitle) ?: return
        val messageInput = findViewById<EditText?>(R.id.adminNotifEtMessage) ?: return
        val audienceInput = findViewById<AutoCompleteTextView?>(R.id.adminNotifAudienceInput) ?: return
        val sendButton =
            findViewById<com.google.android.material.button.MaterialButton?>(R.id.adminNotifBtnSend)
                ?: return
        val statusText = findViewById<android.widget.TextView?>(R.id.adminNotifComposerStatus)
        val audienceOptions = listOf(
            AudienceOption("all", getString(R.string.admin_announcements_audience_all)),
            AudienceOption("clients", getString(R.string.admin_announcements_audience_clients)),
            AudienceOption("vendeurs", getString(R.string.admin_announcements_audience_vendeurs)),
            AudienceOption("admins", getString(R.string.admin_announcements_audience_admins))
        )
        audienceInput.setAdapter(
            android.widget.ArrayAdapter(
                this,
                android.R.layout.simple_list_item_1,
                audienceOptions.map { it.label }
            )
        )
        audienceInput.threshold = 0
        audienceInput.setText(audienceOptions.first().label, false)
        audienceInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) audienceInput.post { audienceInput.showDropDown() }
        }
        audienceInput.setOnClickListener {
            audienceInput.post { audienceInput.showDropDown() }
        }
        audienceInput.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                audienceInput.requestFocus()
                audienceInput.post { audienceInput.showDropDown() }
            }
            false
        }

        titleInput.isEnabled = true
        messageInput.isEnabled = true
        sendButton.isEnabled = true
        sendButton.alpha = 1f
        sendButton.text = getString(R.string.admin_announcements_publish)
        statusText?.text = getString(R.string.admin_announcements_status_idle)

        sendButton.setOnClickListener {
            val title = titleInput.text?.toString().orEmpty().trim()
            val message = messageInput.text?.toString().orEmpty().trim()
            val audience = audienceOptions.firstOrNull {
                it.label == audienceInput.text?.toString().orEmpty().trim()
            }?.key ?: "all"
            if (title.length < 3 || message.length < 5) {
                showMotionSnackbar(getString(R.string.admin_announcements_validation))
                return@setOnClickListener
            }

            sendButton.isEnabled = false
            sendButton.text = getString(R.string.admin_announcements_publishing)
            titleInput.isEnabled = false
            messageInput.isEnabled = false
            audienceInput.isEnabled = false
            statusText?.text = getString(R.string.admin_announcements_status_progress)
            lifecycleScope.launch {
                runCatching {
                    BackendFunctionsService.sendAnnouncement(title, message, audience)
                }.onSuccess {
                    titleInput.text?.clear()
                    messageInput.text?.clear()
                    audienceInput.setText(audienceOptions.first().label, false)
                    NotificationStore.refreshFromCloud(this@AdminNotificationsActivity)
                    loadHistory()
                    statusText?.text = getString(R.string.admin_announcements_status_success)
                    showMotionSnackbar(getString(R.string.admin_announcements_publish_success))
                }.onFailure { error ->
                    Log.e(TAG, "Failed to send announcement", error)
                    statusText?.text = getString(R.string.admin_announcements_status_error)
                    showMotionSnackbar(
                        error.message?.takeIf { it.isNotBlank() }
                            ?: getString(R.string.admin_announcements_publish_error)
                    )
                }
                sendButton.isEnabled = true
                sendButton.text = getString(R.string.admin_announcements_publish)
                titleInput.isEnabled = true
                messageInput.isEnabled = true
                audienceInput.isEnabled = true
            }
        }
    }

    private fun loadHistory() {
        renderHistory(ScreenState.Loading)
        lifecycleScope.launch {
            val result = runCatching { FirestoreService.fetchInAppNotifications() }
            val state: ScreenState<List<FirestoreService.InAppNotification>> = result.fold(
                onSuccess = { notifications ->
                    if (notifications.isEmpty()) ScreenState.Empty(getString(R.string.admin_announcements_empty))
                    else ScreenState.Content(notifications)
                },
                onFailure = { ScreenState.Error(getString(R.string.admin_announcements_history_error)) }
            )
            renderHistory(state)
        }
    }

    private fun renderHistory(state: ScreenState<List<FirestoreService.InAppNotification>>) {
        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.adminNotifHistoryList)
        val empty = findViewById<View>(R.id.adminNotifHistoryEmpty)
        val progress = findViewById<View>(R.id.adminNotifHistoryProgress)
        recycler?.layoutManager = recycler?.layoutManager ?: LinearLayoutManager(this)

        when (state) {
            is ScreenState.Content -> {
                progress?.visibility = View.GONE
                recycler?.visibility = View.VISIBLE
                empty?.visibility = View.GONE
                val adapter = (recycler?.adapter as? NotificationsAdapter) ?: NotificationsAdapter().also {
                    recycler?.adapter = it
                }
                adapter.submitList(state.data.map { notification ->
                    AppNotification(
                        id = notification.id,
                        title = notification.title,
                        message = formatHistoryMessage(notification),
                        timestamp = notification.createdAt,
                        isRead = true
                    )
                })
            }
            is ScreenState.Empty -> {
                progress?.visibility = View.GONE
                recycler?.visibility = View.GONE
                empty?.visibility = View.VISIBLE
            }
            is ScreenState.Error -> {
                progress?.visibility = View.GONE
                recycler?.visibility = View.GONE
                empty?.visibility = View.VISIBLE
                showMotionSnackbar(state.message)
            }
            ScreenState.Loading -> {
                progress?.visibility = View.VISIBLE
                recycler?.visibility = View.GONE
                empty?.visibility = View.GONE
            }
        }
    }

    private fun setupTopBar() {
        findViewById<View?>(R.id.adminNotifIvBack)?.setOnClickListener {
            navigateAdminBack(AdminNavTab.NOTIFICATIONS)
        }
        findViewById<View?>(R.id.adminNotifIvSettings)?.setOnClickListener {
            navigateNoShift(AdminParametresActivity::class.java)
        }
        applyPressFeedback(R.id.adminNotifIvBack, R.id.adminNotifIvSettings)
    }

    private fun audienceLabel(audience: String): String = when (audience) {
        "clients" -> getString(R.string.admin_announcements_audience_clients)
        "vendeurs" -> getString(R.string.admin_announcements_audience_vendeurs)
        "admins" -> getString(R.string.admin_announcements_audience_admins)
        else -> getString(R.string.admin_announcements_audience_all)
    }

    private fun formatHistoryMessage(notification: FirestoreService.InAppNotification): String {
        return buildString {
            append(audienceLabel(notification.audience))
            append(" \u2022 ")
            append(notification.message)
        }
    }
}
