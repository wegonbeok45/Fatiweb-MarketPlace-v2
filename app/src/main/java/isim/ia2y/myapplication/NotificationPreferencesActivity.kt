package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

class NotificationPreferencesActivity : AppCompatActivity() {

    private lateinit var switchPush: MaterialSwitch
    private lateinit var switchOrders: MaterialSwitch
    private lateinit var switchPromotions: MaterialSwitch
    private lateinit var switchAnnouncements: MaterialSwitch
    private lateinit var summaryText: TextView
    private var isRendering = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_notification_preferences)
        AppNotificationChannels.ensureCreated(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bindViews()
        bindActions()
        render(NotificationPreferencesStore.load(this))
        lifecycleScope.launch {
            render(NotificationPreferencesStore.refreshFromCloud(this@NotificationPreferencesActivity))
        }
    }

    private fun bindViews() {
        findViewById<View>(R.id.ivBack)?.setOnClickListener { finishWithMotion() }
        findViewById<View>(R.id.cardNotificationInbox)?.setOnClickListener {
            navigateNoShift(NotificationsActivity::class.java)
        }
        switchPush = findViewById(R.id.switchNotificationPush)
        switchOrders = findViewById(R.id.switchNotificationOrders)
        switchPromotions = findViewById(R.id.switchNotificationPromotions)
        switchAnnouncements = findViewById(R.id.switchNotificationAnnouncements)
        summaryText = findViewById(R.id.tvNotificationSummary)
        applyPressFeedback(R.id.ivBack, R.id.cardNotificationInbox)
    }

    private fun bindActions() {
        val listener = listener@ { _: View ->
            if (isRendering) return@listener
            val preferences = NotificationPreferences(
                pushEnabled = switchPush.isChecked,
                orderUpdates = switchOrders.isChecked,
                promotions = switchPromotions.isChecked,
                announcements = switchAnnouncements.isChecked
            )
            NotificationPreferencesStore.save(this, preferences)
            if (preferences.pushEnabled) {
                maybeRequestNotificationPermissionForPush(force = true)
            }
            render(preferences)
        }
        listOf(switchPush, switchOrders, switchPromotions, switchAnnouncements).forEach {
            it.setOnCheckedChangeListener { button, _ -> listener(button) }
        }
    }

    private fun render(preferences: NotificationPreferences) {
        isRendering = true
        switchPush.isChecked = preferences.pushEnabled
        switchOrders.isChecked = preferences.orderUpdates
        switchPromotions.isChecked = preferences.promotions
        switchAnnouncements.isChecked = preferences.announcements
        isRendering = false

        val activeCount = listOf(
            preferences.orderUpdates,
            preferences.promotions,
            preferences.announcements
        ).count { it }

        summaryText.text = if (!preferences.pushEnabled || !hasNotificationPostPermission()) {
            getString(R.string.notification_preferences_summary_muted)
        } else {
            getString(R.string.notification_preferences_summary_active, activeCount)
        }

        val childEnabled = preferences.pushEnabled
        listOf(switchOrders, switchPromotions, switchAnnouncements).forEach {
            it.isEnabled = childEnabled
            it.alpha = if (childEnabled) 1f else 0.5f
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (handleNotificationPermissionResult(requestCode, grantResults)) {
            render(NotificationPreferencesStore.load(this))
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
