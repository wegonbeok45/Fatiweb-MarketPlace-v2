package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class AdminNotificationsActivity : AppCompatActivity() {

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
        val sendButton =
            findViewById<com.google.android.material.button.MaterialButton?>(R.id.adminNotifBtnSend)
                ?: return

        titleInput.isEnabled = true
        messageInput.isEnabled = true
        sendButton.isEnabled = true
        sendButton.alpha = 1f
        sendButton.text = getString(R.string.admin_announcements_publish)

        sendButton.setOnClickListener {
            val uid = FirebaseAuthManager.currentUser?.uid ?: return@setOnClickListener
            val title = titleInput.text?.toString().orEmpty().trim()
            val message = messageInput.text?.toString().orEmpty().trim()
            if (title.length < 3 || message.length < 5) {
                showMotionSnackbar(getString(R.string.admin_announcements_validation))
                return@setOnClickListener
            }

            sendButton.isEnabled = false
            sendButton.text = getString(R.string.admin_announcements_publishing)
            lifecycleScope.launch {
                runCatching {
                    FirestoreService.createInAppNotification(title, message, uid)
                }.onSuccess {
                    titleInput.text?.clear()
                    messageInput.text?.clear()
                    NotificationStore.refreshFromCloud(this@AdminNotificationsActivity)
                    loadHistory()
                    showToast(getString(R.string.admin_announcements_publish_success))
                }.onFailure {
                    showMotionSnackbar(getString(R.string.admin_announcements_publish_error))
                }
                sendButton.isEnabled = true
                sendButton.text = getString(R.string.admin_announcements_publish)
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
                adapter.submitList(state.data.map {
                    AppNotification(
                        id = it.id,
                        title = it.title,
                        message = it.message,
                        timestamp = it.createdAt,
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
            navigateBackToMain()
        }
        findViewById<View?>(R.id.adminNotifIvSettings)?.setOnClickListener {
            navigateNoShift(AdminParametresActivity::class.java)
        }
        applyPressFeedback(R.id.adminNotifIvBack, R.id.adminNotifIvSettings)
    }
}
