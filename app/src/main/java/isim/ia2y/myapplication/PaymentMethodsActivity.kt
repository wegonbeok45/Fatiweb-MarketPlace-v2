package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.util.Locale

class PaymentMethodsActivity : AppCompatActivity() {
    private lateinit var paymentContainer: LinearLayout
    private lateinit var emptyCard: View
    private lateinit var billingTitle: TextView
    private lateinit var billingSubtitle: TextView
    private lateinit var billingEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_payment_methods)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bindViews()
        bindActions()
        renderBillingAddress()
        renderPaymentMethods()
        revealViewsInOrder(
            R.id.layoutTopBar,
            R.id.layoutPaymentContent
        )
    }

    override fun onResume() {
        super.onResume()
        renderBillingAddress()
        renderPaymentMethods()
    }

    private fun bindViews() {
        paymentContainer = findViewById(R.id.layoutSavedCardsContainer)
        emptyCard = findViewById(R.id.cardPaymentEmpty)
        billingTitle = findViewById(R.id.tvBillingAddressTitle)
        billingSubtitle = findViewById(R.id.tvBillingAddressSubtitle)
        billingEmpty = findViewById(R.id.tvBillingAddressEmpty)
    }

    private fun bindActions() {
        findViewById<View>(R.id.ivBack)?.setOnClickListener { finishWithMotion() }
        findViewById<View>(R.id.btnAddPaymentMethod)?.setOnClickListener { showPaymentMethodDialog() }
        findViewById<View>(R.id.btnEditBillingAddress)?.setOnClickListener {
            navigateNoShift(AddressesActivity::class.java)
        }
        findViewById<View>(R.id.cardBillingAddress)?.setOnClickListener {
            navigateNoShift(AddressesActivity::class.java)
        }
        applyPressFeedback(
            R.id.ivBack,
            R.id.btnAddPaymentMethod,
            R.id.cardBillingAddress
        )
    }

    private fun renderBillingAddress() {
        val address = AddressBookStore.getCurrent(this)
        if (address == null) {
            billingTitle.visibility = View.GONE
            billingSubtitle.visibility = View.GONE
            billingEmpty.visibility = View.VISIBLE
            return
        }

        billingTitle.visibility = View.VISIBLE
        billingSubtitle.visibility = View.VISIBLE
        billingEmpty.visibility = View.GONE
        billingTitle.text = address.addressLine1
        billingSubtitle.text = listOfNotNull(
            listOf(address.city, address.governorate).filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() },
            address.postalCode?.takeIf { it.isNotBlank() },
            address.countryLine()
        ).joinToString("\n")
    }

    private fun renderPaymentMethods() {
        val methods = PaymentMethodsStore.getAll(this)
        paymentContainer.removeAllViews()
        emptyCard.visibility = if (methods.isEmpty()) View.VISIBLE else View.GONE

        methods.forEachIndexed { index, method ->
            val card = layoutInflater.inflate(R.layout.item_payment_method, paymentContainer, false)
            card.findViewById<TextView>(R.id.tvPaymentBrandBadge).text = method.brand.uppercase(Locale.getDefault()).take(4)
            card.findViewById<TextView>(R.id.tvPaymentLast4).text = method.maskedLabel
            card.findViewById<TextView>(R.id.tvPaymentExpiry).text = method.expiryLabel
            card.findViewById<TextView>(R.id.tvPaymentDefaultChip).visibility =
                if (method.isDefault) View.VISIBLE else View.GONE

            val setDefaultButton = card.findViewById<TextView>(R.id.btnPaymentSetDefault)
            setDefaultButton.visibility = if (method.isDefault) View.GONE else View.VISIBLE
            setDefaultButton.setOnClickListener {
                PaymentMethodsStore.setDefault(this, method.id)
                renderPaymentMethods()
            }
            card.findViewById<TextView>(R.id.btnPaymentEdit).setOnClickListener {
                showPaymentMethodDialog(method)
            }
            card.findViewById<TextView>(R.id.btnPaymentRemove).setOnClickListener {
                PaymentMethodsStore.remove(this, method.id)
                renderPaymentMethods()
            }

            paymentContainer.addView(card)
            animateListItemEntry(card, index, startDelayMs = 28L)
        }
    }

    private fun showPaymentMethodDialog(existing: PaymentMethod? = null) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_payment_method, null)
        val brandInput = dialogView.findViewById<AutoCompleteTextView>(R.id.etPaymentBrand)
        val last4Input = dialogView.findViewById<EditText>(R.id.etPaymentLast4)
        val monthInput = dialogView.findViewById<EditText>(R.id.etPaymentExpiryMonth)
        val yearInput = dialogView.findViewById<EditText>(R.id.etPaymentExpiryYear)
        val defaultSwitch = dialogView.findViewById<MaterialSwitch>(R.id.switchPaymentDefault)

        val brands = listOf("Visa", "Mastercard", "Amex", "Other")
        brandInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, brands))
        brandInput.setText(existing?.brand.orEmpty(), false)
        last4Input.setText(existing?.last4.orEmpty())
        monthInput.setText(existing?.expiryMonth?.takeIf { it > 0 }?.toString().orEmpty())
        yearInput.setText(existing?.expiryYear?.takeIf { it > 0 }?.toString().orEmpty())
        defaultSwitch.isChecked = existing?.isDefault ?: PaymentMethodsStore.getAll(this).isEmpty()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(
                getString(
                    if (existing == null) R.string.payment_methods_dialog_add_title
                    else R.string.payment_methods_dialog_edit_title
                )
            )
            .setView(dialogView)
            .setNegativeButton(getString(R.string.profile_edit_cancel), null)
            .setPositiveButton(getString(R.string.profile_edit_save), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val brand = brandInput.text?.toString().orEmpty().trim()
                val last4 = last4Input.text?.toString().orEmpty().filter(Char::isDigit).takeLast(4)
                val expiryMonth = monthInput.text?.toString().orEmpty().toIntOrNull() ?: 0
                val expiryYear = yearInput.text?.toString().orEmpty().toIntOrNull() ?: 0

                if (brand.isBlank() || last4.length != 4 || expiryMonth !in 1..12 || expiryYear < 2024) {
                    showMotionSnackbar(getString(R.string.payment_methods_validation_error))
                    return@setOnClickListener
                }

                val next = PaymentMethod(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    brand = brand,
                    last4 = last4,
                    expiryMonth = expiryMonth,
                    expiryYear = expiryYear,
                    isDefault = defaultSwitch.isChecked
                )
                if (next.isDefault) {
                    val normalized = PaymentMethodsStore.getAll(this)
                        .filterNot { it.id == next.id }
                        .map { it.copy(isDefault = false) } + next.copy(isDefault = true)
                    PaymentMethodsStore.saveAll(
                        this,
                        normalized
                    )
                } else {
                    PaymentMethodsStore.upsert(this, next)
                }
                renderPaymentMethods()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun DeliveryAddress.countryLine(): String {
        val localeCountry = Locale.getDefault().displayCountry.takeIf { it.isNotBlank() }
        return localeCountry ?: "Tunisia"
    }
}
