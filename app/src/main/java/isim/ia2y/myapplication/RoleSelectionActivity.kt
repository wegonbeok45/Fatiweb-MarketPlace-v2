package isim.ia2y.myapplication

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class RoleSelectionActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val root = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24.dp, 24.dp, 24.dp, 24.dp)
            setBackgroundColor(getColor(R.color.details_background))
        }
        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(24.dp + bars.left, 24.dp + bars.top, 24.dp + bars.right, 24.dp + bars.bottom)
            insets
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.role_selection_title)
            setTextAppearance(R.style.AppText_Title)
            setTextColor(getColor(R.color.details_text_primary))
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        root.addView(
            roleButton(getString(R.string.role_selection_buyer), primary = true) { continueAsBuyer() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 28.dp }
        )

        root.addView(
            roleButton(getString(R.string.role_selection_seller), primary = false) { showSellerAccessDialog() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 12.dp }
        )
    }

    private fun roleButton(label: String, primary: Boolean, onClick: () -> Unit): MaterialButton {
        return MaterialButton(this).apply {
            text = label
            isAllCaps = false
            minHeight = 56.dp
            cornerRadius = 8.dp
            if (!primary) {
                setBackgroundColor(getColor(R.color.colorSurface))
                setTextColor(getColor(R.color.colorPrimary))
                strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.colorBorderLight))
                strokeWidth = 1.dp
            }
            setOnClickListener { onClick() }
        }
    }

    private fun showSellerAccessDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4.dp, 8.dp, 4.dp, 0)
        }
        content.addView(TextView(this).apply {
            text = getString(R.string.seller_access_body)
            setTextAppearance(R.style.AppText_Body)
        })
        content.addView(contactLine(getString(R.string.seller_access_phone)))
        content.addView(contactLine(getString(R.string.seller_access_email)))
        content.addView(contactLine(getString(R.string.seller_access_whatsapp)))

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.seller_access_title)
            .setView(content)
            .setNegativeButton(R.string.profile_edit_cancel, null)
            .setPositiveButton(R.string.seller_access_back_to_buyer) { _, _ ->
                continueAsBuyer()
            }
            .show()
    }

    private fun contactLine(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextAppearance(R.style.AppText_Body)
            setTextColor(getColor(R.color.details_text_primary))
            setPadding(0, 12.dp, 0, 0)
        }
    }

    private fun continueAsBuyer() {
        setOnboardingCompleted()
        navigateToMainTab(MainActivity.Tab.HOME)
        finish()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
