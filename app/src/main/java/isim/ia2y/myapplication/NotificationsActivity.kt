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
    private val notificationsAdapter = NotificationsAdapter { notification ->
        handleNotificationClick(notification)
    }
    private var allNotifications: List<AppNotification> = emptyList()
    private var selectedFilter: NotificationFilter = NotificationFilter.ALL

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
        findViewById<View>(R.id.ivNotificationSettings)?.setOnClickListener {
            navigateNoShift(NotificationPreferencesActivity::class.java)
        }
        findViewById<View>(R.id.tvClearAll)?.setOnClickListener {
            lifecycleScope.launch {
                NotificationStore.markAllAsRead(this@NotificationsActivity)
                loadNotifications()
            }
        }
        bindFilterControls()
        applyPressFeedback(R.id.ivBack, R.id.ivNotificationSettings, R.id.tvClearAll)

        loadNotifications()
        revealViewsInOrder(R.id.layoutTopBar, R.id.scrollNotificationFilters)
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
                        ScreenState.Error(getString(R.string.notifications_load_error))
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
        val clearAll = findViewById<View>(R.id.tvClearAll)
        when (state) {
            is ScreenState.Content -> {
                allNotifications = state.data
                loading?.visibility = View.GONE
                emptyAnimation?.pauseAnimation()
                clearAll?.visibility = if (state.data.any { !it.isRead }) View.VISIBLE else View.GONE
                if (rv.layoutManager == null) {
                    rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
                }
                if (rv.adapter == null) {
                    rv.adapter = notificationsAdapter
                }
                renderFilteredNotifications()
            }
            is ScreenState.Empty -> {
                allNotifications = emptyList()
                loading?.visibility = View.GONE
                rv.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
                emptyAnimation?.playAnimation()
                clearAll?.visibility = View.GONE
            }
            is ScreenState.Error -> {
                allNotifications = emptyList()
                loading?.visibility = View.GONE
                rv.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
                emptyAnimation?.playAnimation()
                clearAll?.visibility = View.GONE
                showMotionSnackbar(state.message)
            }
            ScreenState.Loading -> {
                allNotifications = emptyList()
                loading?.visibility = View.VISIBLE
                rv.visibility = View.GONE
                emptyState.visibility = View.GONE
                clearAll?.visibility = View.GONE
            }
        }
    }

    private fun bindFilterControls() {
        findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupNotifications)
            ?.setOnCheckedStateChangeListener { _, checkedIds ->
                selectedFilter = when (checkedIds.firstOrNull()) {
                    R.id.chipFilterOrders -> NotificationFilter.ORDERS
                    R.id.chipFilterOffers -> NotificationFilter.OFFERS
                    R.id.chipFilterSystem -> NotificationFilter.SYSTEM
                    else -> NotificationFilter.ALL
                }
                renderFilteredNotifications()
            }
    }

    private fun renderFilteredNotifications() {
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications) ?: return
        val emptyState = findViewById<View>(R.id.layoutEmptyState) ?: return
        val filtered = allNotifications.filter { notification ->
            when (selectedFilter) {
                NotificationFilter.ALL -> true
                NotificationFilter.ORDERS -> notification.category() == NotificationFilter.ORDERS
                NotificationFilter.OFFERS -> notification.category() == NotificationFilter.OFFERS
                NotificationFilter.SYSTEM -> notification.category() == NotificationFilter.SYSTEM
            }
        }
        rv.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        notificationsAdapter.submitList(filtered)
    }

    private fun handleNotificationClick(notification: AppNotification) {
        notification.isRead = true
        NotificationStore.markRead(this, notification.id)
        lifecycleScope.launch {
            val uid = FirebaseAuthManager.currentUser?.uid
            if (uid != null) {
                runCatching { NotificationService.markNotificationRead(uid, notification.id) }
            }
        }
        val orderId = notification.orderId.ifBlank {
            notification.entityRef.takeIf { notification.route == "order_details" }.orEmpty()
        }
        if (notification.route == "order_details" && orderId.isNotBlank()) {
            startActivity(OrderDetailsActivity.createIntent(this, orderId))
            return
        }
        renderFilteredNotifications()
    }

    private fun AppNotification.category(): NotificationFilter {
        val sample = (title + " " + message).lowercase()
        return when {
            listOf("order", "commande", "shipping", "delivery", "livraison").any(sample::contains) ->
                NotificationFilter.ORDERS
            listOf("offer", "promo", "sale", "discount", "wishlist", "editor").any(sample::contains) ->
                NotificationFilter.OFFERS
            else -> NotificationFilter.SYSTEM
        }
    }

    private enum class NotificationFilter {
        ALL,
        ORDERS,
        OFFERS,
        SYSTEM
    }
}
