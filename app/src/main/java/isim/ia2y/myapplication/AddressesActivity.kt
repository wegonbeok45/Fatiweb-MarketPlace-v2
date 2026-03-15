package isim.ia2y.myapplication

import android.os.Bundle
import android.app.Dialog
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton

// Cette classe organise cette partie de l'app.
class AddressesActivity : AppCompatActivity() {
    // Cette fonction fait une action de cette partie de l'app.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_addresses)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<View>(R.id.ivBack)?.setOnClickListener { finishWithMotion(isForward = false) }
        findViewById<MaterialButton>(R.id.btnAddAddress)?.setOnClickListener { showAddAddressDialog() }
        applyPressFeedback(R.id.ivBack, R.id.btnAddAddress)
        renderAddresses()
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun renderAddresses() {
        val addresses = AddressBookStore.getAddresses(this)
        val current = addresses.firstOrNull().orEmpty()
        findViewById<TextView>(R.id.tvCurrentAddressValue)?.text = current

        val container = findViewById<LinearLayout>(R.id.layoutAddressesContainer) ?: return
        container.removeAllViews()
        addresses.forEachIndexed { index, address ->
            val row = layoutInflater.inflate(R.layout.item_address_entry, container, false)
            row.findViewById<TextView>(R.id.tvAddressLine)?.text = address
            row.findViewById<TextView>(R.id.tvAddressState)?.text =
                if (index == 0) getString(R.string.address_current_badge) else getString(R.string.address_set_current)

            row.findViewById<TextView>(R.id.tvAddressState)?.setOnClickListener {
                AddressBookStore.setCurrent(this, address)
                renderAddresses()
            }
            row.findViewById<ImageView>(R.id.ivAddressRemove)?.setOnClickListener {
                val mutable = AddressBookStore.getAddresses(this).toMutableList()
                mutable.removeAll { it.equals(address, ignoreCase = true) }
                if (mutable.isEmpty()) mutable.add("Tunis, Tunisie")
                AddressBookStore.saveAddresses(this, mutable)
                renderAddresses()
            }
            row.setOnClickListener {
                AddressBookStore.setCurrent(this, address)
                renderAddresses()
            }
            container.addView(row)
            animateListItemEntry(row, index, startDelayMs = 35L)
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun showAddAddressDialog() {
        val dialog = Dialog(this, R.style.ThemeOverlay_MyApp_Dialog)
        dialog.setContentView(R.layout.dialog_add_address)
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(true)

        val input = dialog.findViewById<EditText>(R.id.etAddAddress)
        dialog.findViewById<View>(R.id.btnAddAddressCancel)?.setOnClickListener { dialog.dismiss() }
        dialog.findViewById<View>(R.id.btnAddAddressConfirm)?.setOnClickListener {
            val value = input?.text?.toString()?.trim().orEmpty()
            if (value.isBlank()) {
                showToast(getString(R.string.address_add_validation))
                return@setOnClickListener
            }
            AddressBookStore.addAddress(this, value)
            dialog.dismiss()
            renderAddresses()
        }
        dialog.show()
    }
}
