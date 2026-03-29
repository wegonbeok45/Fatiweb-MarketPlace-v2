package isim.ia2y.myapplication

import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

fun AppCompatActivity.showAlertPopupDialog() {
    val dialog = Dialog(this, R.style.ThemeOverlay_MyApp_Dialog)
    dialog.setContentView(R.layout.dialog_alert)
    dialog.setCancelable(true)
    dialog.setCanceledOnTouchOutside(true)
    dialog.window?.setBackgroundDrawable(
        ColorDrawable(ContextCompat.getColor(this, android.R.color.transparent))
    )

    dialog.findViewById<MaterialButton?>(R.id.btnAlertCancel)?.setOnClickListener {
        dialog.dismiss()
    }
    dialog.findViewById<MaterialButton?>(R.id.btnAlertConfirm)?.setOnClickListener {
        dialog.dismiss()
        showMotionSnackbar(getString(R.string.alert_dialog_confirmed))
    }

    dialog.show()
}

fun AppCompatActivity.showAuthChoiceDialog(
    onCreateAccount: () -> Unit,
    onExistingClient: () -> Unit
) {
    val dialog = Dialog(this, R.style.ThemeOverlay_MyApp_Dialog)
    dialog.setContentView(R.layout.dialog_auth_choice)
    dialog.setCancelable(true)
    dialog.setCanceledOnTouchOutside(true)
    dialog.window?.setBackgroundDrawable(
        ColorDrawable(ContextCompat.getColor(this, android.R.color.transparent))
    )

    dialog.findViewById<TextView?>(R.id.tvAuthDialogTitle)?.text =
        getString(R.string.auth_dialog_title)
    dialog.findViewById<TextView?>(R.id.tvAuthDialogMessage)?.text =
        getString(R.string.auth_dialog_message)
    dialog.findViewById<MaterialButton?>(R.id.btnAuthCreateAccount)?.text =
        getString(R.string.auth_dialog_create_account)
    dialog.findViewById<MaterialButton?>(R.id.btnAuthExistingClient)?.text =
        getString(R.string.auth_dialog_existing_client)

    dialog.findViewById<MaterialButton?>(R.id.btnAuthCreateAccount)?.setOnClickListener {
        dialog.dismiss()
        onCreateAccount()
    }
    dialog.findViewById<MaterialButton?>(R.id.btnAuthExistingClient)?.setOnClickListener {
        dialog.dismiss()
        onExistingClient()
    }
    dialog.show()
}

@Deprecated("Use showMotionSnackbar for consistent feedback", ReplaceWith("showMotionSnackbar(message)"))
fun AppCompatActivity.showToast(message: String) {
    showMotionSnackbar(message)
}

fun AppCompatActivity.bindComingSoon(vararg ids: Int) {
    val message = getString(R.string.coming_soon)
    ids.forEach { viewId ->
        findViewById<View?>(viewId)?.setOnClickListener {
            showToast(message)
        }
    }
}

fun AppCompatActivity.bindAlertPopup(vararg ids: Int) {
    ids.forEach { viewId ->
        findViewById<View?>(viewId)?.setOnClickListener {
            showAlertPopupDialog()
        }
    }
}

fun AppCompatActivity.bindSearchComingSoon(vararg ids: Int) {
    val message = getString(R.string.search_coming_soon)
    ids.forEach { viewId ->
        findViewById<View?>(viewId)?.setOnClickListener {
            showToast(message)
        }
    }
}
