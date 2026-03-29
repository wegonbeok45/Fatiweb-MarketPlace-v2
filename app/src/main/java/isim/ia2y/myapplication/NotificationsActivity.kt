package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class NotificationsActivity : AppCompatActivity() {
    private val notificationsAdapter = NotificationsAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_notifications)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<View>(R.id.ivBack)?.setOnClickListener { finishWithMotion() }
        findViewById<View>(R.id.tvClearAll)?.setOnClickListener {
            NotificationStore.clearAll(this)
            loadNotifications()
        }
        applyPressFeedback(R.id.ivBack, R.id.tvClearAll)

        loadNotifications()
        revealViewsInOrder(R.id.layoutTopBar, R.id.viewTopDivider)
    }

    private fun loadNotifications() {
        lifecycleScope.launch {
            val result = runCatching { NotificationStore.refreshFromCloud(this@NotificationsActivity) }
            val state: ScreenState<List<AppNotification>> = result.fold(
                onSuccess = { notifications ->
                    val filtered = notifications.filter { !it.title.startsWith("{") && !it.message.startsWith("{") }
                    if (filtered.isEmpty()) ScreenState.Empty() else ScreenState.Content(filtered)
                },
                onFailure = {
                    val cached = NotificationStore.getAll(this@NotificationsActivity).filter { !it.title.startsWith("{") && !it.message.startsWith("{") }
                    if (cached.isEmpty()) {
                        ScreenState.Error("Impossible de charger vos notifications.")
                    } else {
                        ScreenState.Content(cached)
                    }
                }
            )
            renderNotifications(state)
        }
    }

    private fun renderNotifications(state: ScreenState<List<AppNotification>>) {
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)
        val emptyState = findViewById<View>(R.id.layoutEmptyState)
        val emptyAnimation = findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.ivNotificationsEmptyAnimation)
        val loading = findViewById<ProgressBar>(R.id.loadingIndicator)
        when (state) {
            is ScreenState.Content -> {
                loading?.visibility = View.GONE
                rv.visibility = View.VISIBLE
                emptyState.visibility = View.GONE
                emptyAnimation?.pauseAnimation()
                if (rv.layoutManager == null) {
                    rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
                }
                if (rv.adapter == null) {
                    rv.adapter = notificationsAdapter
                }
                notificationsAdapter.submitList(state.data)
                NotificationStore.markAllAsRead(this)
            }
            is ScreenState.Empty -> {
                loading?.visibility = View.GONE
                rv.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
                emptyAnimation?.playAnimation()
            }
            is ScreenState.Error -> {
                loading?.visibility = View.GONE
                rv.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
                emptyAnimation?.playAnimation()
                showMotionSnackbar(state.message)
            }
            ScreenState.Loading -> {
                loading?.visibility = View.VISIBLE
                rv.visibility = View.GONE
                emptyState.visibility = View.GONE
            }
        }
    }
}
