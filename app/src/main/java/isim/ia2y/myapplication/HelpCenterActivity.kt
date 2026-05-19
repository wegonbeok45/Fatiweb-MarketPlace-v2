package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

class HelpCenterActivity : AppCompatActivity() {
    private lateinit var searchInput: EditText

    private val topicIds by lazy {
        listOf(
            R.id.cardTopicOrders to getString(R.string.help_center_topic_orders_title),
            R.id.cardTopicShipping to getString(R.string.help_center_topic_shipping_title),
            R.id.cardTopicReturns to getString(R.string.help_center_topic_returns_title)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_help_center)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bindViews()
        bindActions()
        revealViewsInOrder(
            R.id.layoutTopBar,
            R.id.cardHelpSearch,
            R.id.layoutHelpTopics,
            R.id.layoutHelpContact
        )
    }

    private fun bindViews() {
        searchInput = findViewById(R.id.etHelpSearch)
    }

    private fun bindActions() {
        findViewById<View>(R.id.ivBack)?.setOnClickListener { finishWithMotion() }
        bindNotificationEntry(R.id.ivNotifications)
        findViewById<View>(R.id.cardTopicPayments)?.visibility = View.GONE

        searchInput.addTextChangedListener { filterTopics(it?.toString().orEmpty()) }

        findViewById<View>(R.id.cardTopicOrders)?.setOnClickListener {
            openSupportChat(getString(R.string.help_center_prefill_orders))
        }
        findViewById<View>(R.id.cardTopicShipping)?.setOnClickListener {
            openSupportChat(getString(R.string.help_center_prefill_shipping))
        }
        findViewById<View>(R.id.cardTopicReturns)?.setOnClickListener {
            openSupportChat(getString(R.string.help_center_prefill_returns))
        }
        findViewById<View>(R.id.cardContactChat)?.setOnClickListener {
            openSupportChat(getString(R.string.help_center_prefill_chat))
        }
        findViewById<View>(R.id.cardContactEmail)?.setOnClickListener {
            openEmail(getString(R.string.support_email), getString(R.string.help_center_email_subject))
        }
        findViewById<View>(R.id.btnBrowseFaqs)?.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.help_center_faq_title))
                .setMessage(getString(R.string.help_center_faq_body))
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }

        applyPressFeedback(
            R.id.ivBack,
            R.id.ivNotifications,
            R.id.cardTopicOrders,
            R.id.cardTopicShipping,
            R.id.cardTopicReturns,
            R.id.cardContactChat,
            R.id.cardContactEmail,
            R.id.btnBrowseFaqs
        )
    }

    private fun filterTopics(query: String) {
        val normalized = query.trim().lowercase(Locale.getDefault())
        topicIds.forEach { (viewId, title) ->
            val visible = normalized.isBlank() || title.lowercase(Locale.getDefault()).contains(normalized)
            findViewById<View>(viewId)?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    private fun openSupportChat(prompt: String) {
        startActivity(ChatActivity.createIntent(this, prompt))
        if (isReducedMotionEnabled()) {
            overridePendingTransition(0, 0)
        } else {
            overridePendingTransition(R.anim.motion_activity_enter_forward, R.anim.motion_activity_exit_forward)
        }
    }
}
