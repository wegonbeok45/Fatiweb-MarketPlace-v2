package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NotificationsActivity : AppCompatActivity() {
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
        applyPressFeedback(R.id.ivBack)

        setupNotifications()
        revealViewsInOrder(R.id.layoutTopBar, R.id.viewTopDivider)
    }

    private fun setupNotifications() {
        val rv = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvNotifications)
        val emptyState = findViewById<View>(R.id.layoutEmptyState)
        val notifications = NotificationStore.getAll(this)

        if (notifications.isEmpty()) {
            rv.visibility = View.GONE
            emptyState.visibility = View.VISIBLE
        } else {
            rv.visibility = View.VISIBLE
            emptyState.visibility = View.GONE
            rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
            rv.adapter = NotificationsAdapter(notifications)
            
            // Mark as read after viewing
            lifecycleScope.launch {
                delay(1200)
                NotificationStore.markAllAsRead(this@NotificationsActivity)
            }
        }
    }
}
