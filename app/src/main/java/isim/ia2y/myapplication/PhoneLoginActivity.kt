package isim.ia2y.myapplication

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import isim.ia2y.myapplication.databinding.ActivityPhoneLoginBinding
import kotlinx.coroutines.launch

class PhoneLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhoneLoginBinding
    private var isSubmitting = false

    companion object {
        private const val COUNTRY_CODE = "+216"

        fun createIntent(
            context: Context,
            returnToTab: MainActivity.Tab? = null,
            returnToRoute: String? = null
        ): Intent {
            return Intent(context, PhoneLoginActivity::class.java)
                .withAuthReturn(returnToTab, returnToRoute)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPhoneLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val hPadding = resources.getDimensionPixelSize(R.dimen.auth_screen_padding_compact)
            val vPadding = resources.getDimensionPixelSize(R.dimen.auth_screen_vertical_inset_padding)
            v.setPadding(hPadding, systemBars.top + vPadding, hPadding, systemBars.bottom + vPadding)
            insets
        }

        binding.ivBack.setOnClickListener { finishWithMotion(isForward = false) }

        bindInputFieldMotion(R.id.cardPhoneField, R.id.etPhone) { value ->
            value.length == 8
        }

        binding.btnSendCode.setOnClickListener { sendCode() }

        applyPressFeedback(R.id.ivBack, R.id.btnSendCode)
        revealViewsInOrder(
            R.id.ivBack,
            R.id.tvTitle,
            R.id.tvSubtitle,
            R.id.tvFieldLabel,
            R.id.cardPhoneField,
            R.id.tvHelper,
            R.id.btnSendCode
        )
        emphasizeCta(R.id.btnSendCode)
    }

    private fun sendCode() {
        if (isSubmitting) return
        val local = binding.etPhone.text?.toString().orEmpty().filter { it.isDigit() }
        if (local.length != 8) {
            markInputState(R.id.cardPhoneField, InputFieldState.ERROR)
            showMotionSnackbar(getString(R.string.phone_login_invalid))
            return
        }
        val normalized = local.trimStart('0')
        val e164 = "$COUNTRY_CODE$normalized"

        lifecycleScope.launch {
            setLoading(true)
            try {
                val result = FirebaseAuthManager.startPhoneVerification(
                    activity = this@PhoneLoginActivity,
                    e164PhoneNumber = e164
                )
                result.fold(
                    onSuccess = { start ->
                        markInputState(R.id.cardPhoneField, InputFieldState.SUCCESS)
                        if (start.autoSignedInUser != null) {
                            GuestSessionMerger.mergeIntoCurrentUserInBackground(this@PhoneLoginActivity)
                            AnalyticsTracker.login("phone")
                            completeAuthFlow()
                        } else if (start.verificationId != null) {
                            startActivity(
                                PhoneVerifyActivity.createIntent(
                                    context = this@PhoneLoginActivity,
                                    verificationId = start.verificationId,
                                    phoneNumber = e164
                                ).copyAuthReturnFrom(intent)
                            )
                            overridePendingTransitionForward()
                        }
                    },
                    onFailure = { e ->
                        markInputState(R.id.cardPhoneField, InputFieldState.ERROR)
                        showMotionSnackbar(FirebaseAuthManager.friendlyError(this@PhoneLoginActivity, e))
                    }
                )
            } finally {
                if (!isFinishing && !isDestroyed) setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        isSubmitting = loading
        binding.btnSendCode.isEnabled = !loading
        binding.btnSendCode.text = getString(
            if (loading) R.string.phone_login_sending else R.string.phone_login_send_code
        )
    }

    private fun completeAuthFlow() {
        when (intent.authReturnRoute()) {
            AUTH_RETURN_ROUTE_CHECKOUT -> {
                startActivity(Intent(this, CheckoutDetailsActivity::class.java))
                finishWithMotion(isForward = true)
            }
            AUTH_RETURN_ROUTE_ORDERS -> {
                startActivity(Intent(this, OrdersHistoryActivity::class.java))
                finishWithMotion(isForward = true)
            }
            else -> navigateToMainTab(intent.authReturnTab() ?: MainActivity.Tab.HOME)
        }
    }

    private fun overridePendingTransitionForward() {
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
