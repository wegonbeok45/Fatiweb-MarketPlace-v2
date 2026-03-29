package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.launch

class AddressesActivity : AppCompatActivity() {
    private var state: ScreenState<List<DeliveryAddress>> = ScreenState.Loading

    private val addressesAdapter = AddressesAdapter(
        onDefault = { address ->
            AddressBookStore.setCurrent(this, address.id)
            loadAddresses()
        },
        onEdit = { address -> showAddressDialog(address) },
        onDelete = { address -> confirmDelete(address) },
        onSelect = { address ->
            AddressBookStore.setCurrent(this, address.id)
            loadAddresses()
        }
    )

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
        findViewById<MaterialButton>(R.id.btnAddAddress)?.setOnClickListener { showAddressDialog() }
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvAddresses)?.layoutManager =
            LinearLayoutManager(this)
        applyPressFeedback(R.id.ivBack, R.id.btnAddAddress)
        loadAddresses()
    }

    override fun onResume() {
        super.onResume()
        loadAddresses()
    }

    private fun loadAddresses() {
        lifecycleScope.launch {
            val addresses = AddressBookStore.getAll(this@AddressesActivity)
            state = if (addresses.isEmpty()) {
                ScreenState.Empty(getString(R.string.address_none))
            } else {
                ScreenState.Content(addresses)
            }
            renderState()
        }
    }

    private fun renderState() {
        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvAddresses) ?: return
        val emptyLayout = findViewById<View>(R.id.layoutAddressesEmpty)
        val emptyAnimation = findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.ivAddressesEmptyAnimation)
        val currentValue = findViewById<TextView>(R.id.tvCurrentAddressValue)
        val loading = findViewById<ProgressBar>(R.id.loadingIndicator)

        when (val currentState = state) {
            is ScreenState.Content -> {
                loading?.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                emptyLayout?.visibility = View.GONE
                emptyAnimation?.pauseAnimation()
                val current = currentState.data.firstOrNull { it.isDefault } ?: currentState.data.first()
                currentValue?.text = listOf(current.titleLine, current.summaryLine, current.detailsLine)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                if (recycler.adapter == null) {
                    recycler.adapter = addressesAdapter
                }
                addressesAdapter.submitList(currentState.data)
            }
            is ScreenState.Empty -> {
                loading?.visibility = View.GONE
                recycler.visibility = View.GONE
                emptyLayout?.visibility = View.VISIBLE
                emptyAnimation?.playAnimation()
                currentValue?.text = getString(R.string.address_none_registered)
            }
            is ScreenState.Error -> {
                loading?.visibility = View.GONE
                recycler.visibility = View.GONE
                emptyLayout?.visibility = View.VISIBLE
                emptyAnimation?.playAnimation()
                currentValue?.text = currentState.message
            }
            ScreenState.Loading -> {
                loading?.visibility = View.VISIBLE
                recycler.visibility = View.GONE
                emptyLayout?.visibility = View.GONE
                emptyAnimation?.pauseAnimation()
            }
        }
    }

    private fun confirmDelete(address: DeliveryAddress) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.address_delete_title))
            .setMessage(address.summaryLine)
            .setNegativeButton(getString(R.string.address_delete_cancel), null)
            .setPositiveButton(getString(R.string.address_delete_confirm)) { _, _ ->
                AddressBookStore.delete(this, address.id)
                loadAddresses()
            }
            .show()
    }

    private fun showAddressDialog(existing: DeliveryAddress? = null) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_edit_address, null)
        dialogView.findViewById<TextView>(R.id.tvAddressDialogTitle)?.text =
            if (existing == null) getString(R.string.address_dialog_add_title) else getString(R.string.address_dialog_edit_title)

        val etLabel = dialogView.findViewById<EditText>(R.id.etAddressLabel)
        val etRecipient = dialogView.findViewById<EditText>(R.id.etAddressRecipient)
        val etPhone = dialogView.findViewById<EditText>(R.id.etAddressPhone)
        val etGovernorate = dialogView.findViewById<EditText>(R.id.etAddressGovernorate)
        val etCity = dialogView.findViewById<EditText>(R.id.etAddressCity)
        val etLine1 = dialogView.findViewById<EditText>(R.id.etAddressLine1)
        val etLine2 = dialogView.findViewById<EditText>(R.id.etAddressLine2)
        val etPostal = dialogView.findViewById<EditText>(R.id.etAddressPostalCode)
        val etNotes = dialogView.findViewById<EditText>(R.id.etAddressNotes)
        val switchDefault = dialogView.findViewById<SwitchMaterial>(R.id.switchAddressDefault)

        val fallbackName = FirebaseAuthManager.currentUser?.displayName.orEmpty()
        existing?.let { address ->
            etLabel.setText(address.label)
            etRecipient.setText(address.recipientName)
            etPhone.setText(address.phone)
            etGovernorate.setText(address.governorate)
            etCity.setText(address.city)
            etLine1.setText(address.addressLine1)
            etLine2.setText(address.addressLine2.orEmpty())
            etPostal.setText(address.postalCode.orEmpty())
            etNotes.setText(address.deliveryNotes.orEmpty())
            switchDefault.isChecked = address.isDefault
        } ?: run {
            etRecipient.setText(fallbackName)
            switchDefault.isChecked = AddressBookStore.getAll(this).isEmpty()
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setNegativeButton(getString(R.string.address_dialog_cancel), null)
            .setPositiveButton(if (existing == null) getString(R.string.address_dialog_add_action) else getString(R.string.address_dialog_save_action), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val input = DeliveryAddressInput(
                    label = etLabel.text?.toString().orEmpty(),
                    recipientName = etRecipient.text?.toString().orEmpty(),
                    phone = etPhone.text?.toString().orEmpty(),
                    governorate = etGovernorate.text?.toString().orEmpty(),
                    city = etCity.text?.toString().orEmpty(),
                    addressLine1 = etLine1.text?.toString().orEmpty(),
                    addressLine2 = etLine2.text?.toString().orEmpty(),
                    postalCode = etPostal.text?.toString().orEmpty(),
                    deliveryNotes = etNotes.text?.toString().orEmpty(),
                    isDefault = switchDefault.isChecked
                )
                val validation = DeliveryAddressValidator.validate(input)
                if (validation != null) {
                    showMotionSnackbar(validation)
                    return@setOnClickListener
                }

                val address = DeliveryAddress(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    label = input.label.trim(),
                    recipientName = input.recipientName.trim(),
                    phone = DeliveryAddressValidator.normalizedPhone(input.phone),
                    governorate = input.governorate.trim(),
                    city = input.city.trim(),
                    addressLine1 = input.addressLine1.trim(),
                    addressLine2 = input.addressLine2.trim().ifBlank { null },
                    postalCode = input.postalCode.trim().ifBlank { null },
                    deliveryNotes = input.deliveryNotes.trim().ifBlank { null },
                    isDefault = input.isDefault
                )
                AddressBookStore.upsert(this, address)
                if (address.isDefault) {
                    AddressBookStore.setCurrent(this, address.id)
                }
                dialog.dismiss()
                loadAddresses()
            }
        }
        dialog.show()
    }
}
