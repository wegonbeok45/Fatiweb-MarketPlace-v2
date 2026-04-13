package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch

class NotificationPreferencesActivity : AppCompatActivity() {

    private lateinit var switchPush: MaterialSwitch
    private lateinit var switchOrders: MaterialSwitch
    private lateinit var switchPromotions: MaterialSwitch
    private lateinit var switchAnnouncements: MaterialSwitch
    private lateinit var summaryText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_notification_preferences)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bindViews()
        bindActions()
        render(NotificationPreferencesStore.load(this))
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
        val listener = { _: View ->
            val preferences = NotificationPreferences(
                pushEnabled = switchPush.isChecked,
                orderUpdates = switchOrders.isChecked,
                promotions = switchPromotions.isChecked,
                announcements = switchAnnouncements.isChecked
            )
            NotificationPreferencesStore.save(this, preferences)
            render(preferences)
        }
        listOf(switchPush, switchOrders, switchPromotions, switchAnnouncements).forEach {
            it.setOnCheckedChangeListener { button, _ -> listener(button) }
        }
    }

    private fun render(preferences: NotificationPreferences) {
        switchPush.isChecked = preferences.pushEnabled
        switchOrders.isChecked = preferences.orderUpdates
        switchPromotions.isChecked = preferences.promotions
        switchAnnouncements.isChecked = preferences.announcements

        val activeCount = listOf(
            preferences.orderUpdates,
            preferences.promotions,
            preferences.announcements
        ).count { it }

        summaryText.text = if (!preferences.pushEnabled) {
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
}
