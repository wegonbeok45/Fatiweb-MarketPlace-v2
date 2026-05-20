package isim.ia2y.myapplication

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import isim.ia2y.myapplication.databinding.ActivityPhoneVerifyBinding
import kotlinx.coroutines.launch

class PhoneVerifyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhoneVerifyBinding
    private var verificationId: String = ""
    private var phoneNumber: String = ""
    private var isSubmitting = false
    private var resendTimer: CountDownTimer? = null

    companion object {
        private const val EXTRA_VERIFICATION_ID = "extra_verification_id"
        private const val EXTRA_PHONE = "extra_phone"
        private const val RESEND_COOLDOWN_MS = 30_000L

        fun createIntent(
            context: Context,
            verificationId: String,
            phoneNumber: String
        ): Intent {
            return Intent(context, PhoneVerifyActivity::class.java)
                .putExtra(EXTRA_VERIFICATION_ID, verificationId)
                .putExtra(EXTRA_PHONE, phoneNumber)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityPhoneVerifyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val hPadding = resources.getDimensionPixelSize(R.dimen.auth_screen_padding_compact)
            val vPadding = resources.getDimensionPixelSize(R.dimen.auth_screen_vertical_inset_padding)
            v.setPadding(hPadding, systemBars.top + vPadding, hPadding, systemBars.bottom + vPadding)
            insets
        }

        verificationId = intent.getStringExtra(EXTRA_VERIFICATION_ID).orEmpty()
        phoneNumber = intent.getStringExtra(EXTRA_PHONE).orEmpty()

        binding.tvSubtitle.text = getString(R.string.phone_verify_subtitle, phoneNumber)
        binding.ivBack.setOnClickListener { finishWithMotion(isForward = false) }

        bindInputFieldMotion(R.id.cardCodeField, R.id.etCode) { value -> value.length == 6 }

        binding.btnVerify.setOnClickListener { verify() }
        binding.tvResend.setOnClickListener { resend() }

        applyPressFeedback(R.id.ivBack, R.id.btnVerify, R.id.tvResend)
        revealViewsInOrder(
            R.id.ivBack,
            R.id.tvTitle,
            R.id.tvSubtitle,
            R.id.tvFieldLabel,
            R.id.cardCodeField,
            R.id.btnVerify,
            R.id.tvResend
        )
        emphasizeCta(R.id.btnVerify)
        startResendCooldown()
    }

    private fun verify() {
        if (isSubmitting) return
        val code = binding.etCode.text?.toString().orEmpty().filter { it.isDigit() }
        if (code.length != 6) {
            markInputState(R.id.cardCodeField, InputFieldState.ERROR)
            showMotionSnackbar(getString(R.string.phone_verify_invalid))
            return
        }
        lifecycleScope.launch {
            setLoading(true)
            try {
                val result = FirebaseAuthManager.signInWithSmsCode(verificationId, code)
                result.fold(
                    onSuccess = {
                        GuestSessionMerger.mergeIntoCurrentUserInBackground(this@PhoneVerifyActivity)
                        AnalyticsTracker.login("phone")
                        markInputState(R.id.cardCodeField, InputFieldState.SUCCESS)
                        completeAuthFlow()
                    },
                    onFailure = { e ->
                        markInputState(R.id.cardCodeField, InputFieldState.ERROR)
                        showMotionSnackbar(FirebaseAuthManager.friendlyError(this@PhoneVerifyActivity, e))
                    }
                )
            } finally {
                if (!isFinishing && !isDestroyed) setLoading(false)
            }
        }
    }

    private fun resend() {
        if (isSubmitting) return
        lifecycleScope.launch {
            setLoading(true)
            try {
                val result = FirebaseAuthManager.startPhoneVerification(
                    activity = this@PhoneVerifyActivity,
                    e164PhoneNumber = phoneNumber
                )
                result.fold(
                    onSuccess = { start ->
                        if (start.verificationId != null) {
                            verificationId = start.verificationId
                        }
                        if (start.autoSignedInUser != null) {
                            GuestSessionMerger.mergeIntoCurrentUserInBackground(this@PhoneVerifyActivity)
                            AnalyticsTracker.login("phone")
                            completeAuthFlow()
                            return@fold
                        }
                        showMotionSnackbar(getString(R.string.phone_verify_resent))
                        startResendCooldown()
                    },
                    onFailure = { e ->
                        showMotionSnackbar(FirebaseAuthManager.friendlyError(this@PhoneVerifyActivity, e))
                    }
                )
            } finally {
                if (!isFinishing && !isDestroyed) setLoading(false)
            }
        }
    }

    private fun startResendCooldown() {
        resendTimer?.cancel()
        binding.tvResend.isEnabled = false
        binding.tvResend.alpha = 0.5f
        resendTimer = object : CountDownTimer(RESEND_COOLDOWN_MS, 1000) {
            override fun onTick(remaining: Long) {
                val seconds = (remaining / 1000).toInt() + 1
                binding.tvResend.text = getString(R.string.phone_verify_resend_in, seconds)
            }

            override fun onFinish() {
                binding.tvResend.isEnabled = true
                binding.tvResend.alpha = 1f
                binding.tvResend.text = getString(R.string.phone_verify_resend)
            }
        }.start()
    }

    private fun setLoading(loading: Boolean) {
        isSubmitting = loading
        binding.btnVerify.isEnabled = !loading
        binding.btnVerify.text = getString(
            if (loading) R.string.phone_verify_verifying else R.string.phone_verify_confirm
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

    override fun onDestroy() {
        resendTimer?.cancel()
        super.onDestroy()
    }
}
