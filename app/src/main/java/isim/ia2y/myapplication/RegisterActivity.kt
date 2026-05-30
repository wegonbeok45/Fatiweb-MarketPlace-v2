package isim.ia2y.myapplication

import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {
    private var passwordVisible = false
    private var isRegisterSubmitting = false

    companion object {
        private const val KEY_NAME = "name"
        private const val KEY_EMAIL = "email"
        private const val KEY_PASSWORD_VISIBLE = "password_visible"

        fun createIntent(
            context: android.content.Context,
            returnToTab: MainActivity.Tab? = null,
            returnToRoute: String? = null
        ): android.content.Intent {
            return android.content.Intent(context, RegisterActivity::class.java)
                .withAuthReturn(returnToTab, returnToRoute)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_register)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val hPadding = resources.getDimensionPixelSize(R.dimen.auth_screen_padding)
            val vPadding = resources.getDimensionPixelSize(R.dimen.auth_screen_vertical_inset_padding)
            v.setPadding(hPadding, systemBars.top + vPadding, hPadding, systemBars.bottom + vPadding)
            insets
        }
        setupRegisterActions()

        if (savedInstanceState != null) {
            savedInstanceState.getString(KEY_NAME)?.let { name ->
                findViewById<EditText>(R.id.etFullName)?.setText(name)
            }
            savedInstanceState.getString(KEY_EMAIL)?.let { email ->
                findViewById<EditText>(R.id.etEmail)?.setText(email)
            }
            passwordVisible = savedInstanceState.getBoolean(KEY_PASSWORD_VISIBLE, false)
            val etPassword = findViewById<EditText>(R.id.etPassword)
            etPassword?.transformationMethod =
                if (passwordVisible) null else PasswordTransformationMethod.getInstance()
            etPassword?.setSelection(etPassword.text?.length ?: 0)
        }

        revealViewsInOrder(
            R.id.ivBack,
            R.id.cardRegisterPanel,
            R.id.tvHeadline,
            R.id.tvSubtitle,
            R.id.cardFullNameField,
            R.id.cardEmailField,
            R.id.cardPasswordField,
            R.id.btnRegister,
            R.id.layoutSocialButtons,
            R.id.layoutLoginRow
        )
        emphasizeCta(R.id.btnRegister)
    }

    private fun setupRegisterActions() {
        findViewById<View>(R.id.ivBack)?.setOnClickListener {
            finishAuthEntry()
        }
        findViewById<View>(R.id.tvGoToLogin)?.setOnClickListener {
            startActivity(LoginActivity.createIntent(this).copyAuthReturnFrom(intent))
        }
        setupGoogleLogin()



        val etFullName = findViewById<EditText>(R.id.etFullName)
        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnRegister = findViewById<TextView>(R.id.btnRegister)

        bindInputFieldMotion(R.id.cardFullNameField, R.id.etFullName) { value -> value.length >= 3 }
        bindInputFieldMotion(R.id.cardEmailField, R.id.etEmail) { value ->
            value.contains("@") && value.contains(".")
        }
        bindInputFieldMotion(R.id.cardPasswordField, R.id.etPassword) { value ->
            value.length >= 6
        }

        btnRegister?.setOnClickListener {
            if (isRegisterSubmitting) return@setOnClickListener

            val name = etFullName.text?.toString().orEmpty().trim()
            val email = etEmail.text?.toString().orEmpty().trim()
            val password = etPassword.text?.toString().orEmpty()
            clearRegisterInlineErrors()

            val hasName = name.length >= 3
            val hasValidEmail = email.contains("@") && email.contains(".")
            val hasValidPassword = password.length >= 6

            if (!hasName || !hasValidEmail || !hasValidPassword) {
                if (!hasName) {
                    markInputState(R.id.cardFullNameField, InputFieldState.ERROR)
                    findViewById<TextView>(R.id.tvFullNameError)?.visibility = View.VISIBLE
                }
                if (!hasValidEmail) {
                    markInputState(R.id.cardEmailField, InputFieldState.ERROR)
                    findViewById<TextView>(R.id.tvEmailError)?.visibility = View.VISIBLE
                }
                if (!hasValidPassword) {
                    markInputState(R.id.cardPasswordField, InputFieldState.ERROR)
                    findViewById<TextView>(R.id.tvPasswordError)?.apply {
                        text = getString(R.string.auth_error_password_inline)
                        setTextColor(getColor(R.color.colorError))
                    }
                }
                showMotionSnackbar(getString(R.string.register_validation_error))
                return@setOnClickListener
            }

            lifecycleScope.launch {
                setRegisterLoading(btnRegister, true)
                try {
                    val result = FirebaseAuthManager.register(email, password, name)
                    result.fold(
                        onSuccess = {
                            AnalyticsTracker.signUp("email")
                            GuestSessionMerger.mergeIntoCurrentUserInBackground(this@RegisterActivity)
                            clearRegisterInlineErrors()
                            markInputState(R.id.cardFullNameField, InputFieldState.SUCCESS)
                            markInputState(R.id.cardEmailField, InputFieldState.SUCCESS)
                            markInputState(R.id.cardPasswordField, InputFieldState.SUCCESS)
                            showPhoneVerificationPrompt()
                        },
                        onFailure = { e ->
                            findViewById<TextView>(R.id.tvPasswordError)?.apply {
                                text = FirebaseAuthManager.friendlyError(this@RegisterActivity, e)
                                setTextColor(getColor(R.color.colorError))
                            }
                        }
                    )
                } finally {
                    if (!isFinishing && !isDestroyed) {
                        setRegisterLoading(btnRegister, false)
                    }
                }
            }
        }

        findViewById<View>(R.id.ivPasswordToggle)?.setOnClickListener {
            passwordVisible = !passwordVisible
            etPassword.transformationMethod =
                if (passwordVisible) null else PasswordTransformationMethod.getInstance()
            etPassword.setSelection(etPassword.text?.length ?: 0)
        }
        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                btnRegister?.performClick()
                true
            } else {
                false
            }
        }
        applyPressFeedback(
            R.id.ivBack,
            R.id.tvGoToLogin,
            R.id.btnRegister,
            R.id.btnGoogle,
            R.id.ivPasswordToggle
        )
    }

    private fun setupGoogleLogin() {
        findViewById<View>(R.id.btnGoogle)?.setOnClickListener { launchGoogleSignIn() }
    }

    private fun launchGoogleSignIn() {
        lifecycleScope.launch {
            try {
                val idToken = GoogleCredentialHelper.fetchIdToken(
                    this@RegisterActivity,
                    getString(R.string.google_web_client_id)
                )
                if (idToken != null) {
                    handleGoogleIdToken(idToken)
                } else {
                    showMotionSnackbar(getString(R.string.auth_google_sign_in_failed))
                }
            } catch (e: GetCredentialCancellationException) {
                // User dismissed — no-op.
            } catch (e: Exception) {
                Log.w("RegisterActivity", "Google sign-in failed", e)
                showMotionSnackbar(getString(R.string.auth_google_sign_in_failed))
            }
        }
    }

    private fun handleGoogleIdToken(idToken: String) {
        lifecycleScope.launch {
            val result = FirebaseAuthManager.signInWithGoogle(idToken)
            result.fold(
                onSuccess = {
                    AnalyticsTracker.signUp("google")
                    GuestSessionMerger.mergeIntoCurrentUserInBackground(this@RegisterActivity)
                    completeAuthFlow()
                },
                onFailure = { e -> showMotionSnackbar(FirebaseAuthManager.friendlyError(this@RegisterActivity, e)) }
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_NAME, findViewById<EditText>(R.id.etFullName)?.text?.toString().orEmpty())
        outState.putString(KEY_EMAIL, findViewById<EditText>(R.id.etEmail)?.text?.toString().orEmpty())
        outState.putBoolean(KEY_PASSWORD_VISIBLE, passwordVisible)
    }

    private fun finishAuthEntry() {
        intent.authReturnTab()?.let {
            navigateToMainTab(it)
            return
        }
        finishWithMotion(isForward = false)
    }

    private fun setRegisterLoading(button: TextView, isLoading: Boolean) {
        isRegisterSubmitting = isLoading
        button.isEnabled = !isLoading
        button.text =
            getString(if (isLoading) R.string.register_submitting else R.string.register_btn_label)
    }

    private fun clearRegisterInlineErrors() {
        findViewById<TextView>(R.id.tvFullNameError)?.visibility = View.GONE
        findViewById<TextView>(R.id.tvEmailError)?.visibility = View.GONE
        findViewById<TextView>(R.id.tvPasswordError)?.apply {
            text = getString(R.string.auth_password_helper)
            setTextColor(getColor(R.color.ms_text_secondary))
        }
    }

    private fun showPhoneVerificationPrompt() {
        val input = EditText(this).apply {
            hint = getString(R.string.signup_phone_hint)
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.space_20)
            setPadding(pad, resources.getDimensionPixelSize(R.dimen.space_8), pad, 0)
            addView(input)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.signup_phone_verify_title)
            .setMessage(R.string.signup_phone_verify_message)
            .setView(content)
            .setCancelable(false)
            .setPositiveButton(R.string.signup_phone_send_code, null)
            .setNegativeButton(R.string.signup_phone_skip, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val phone = normalizePhoneForSms(input.text?.toString().orEmpty())
                if (phone == null) {
                    input.error = getString(R.string.signup_phone_invalid)
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    val button = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    button.isEnabled = false
                    button.text = getString(R.string.phone_login_sending)
                    val result = FirebaseAuthManager.startPhoneLinkVerification(
                        activity = this@RegisterActivity,
                        e164PhoneNumber = phone
                    )
                    result.fold(
                        onSuccess = { start ->
                            val user = start.autoLinkedUser
                            if (user != null) {
                                runCatching { UserService.markPhoneVerified(user.uid, phone) }
                                dialog.dismiss()
                                showMotionSnackbar(getString(R.string.signup_phone_verified))
                                completeAuthFlow()
                            } else if (start.verificationId != null) {
                                dialog.dismiss()
                                showSmsCodeDialog(start.verificationId, phone)
                            }
                        },
                        onFailure = { e ->
                            button.isEnabled = true
                            button.text = getString(R.string.signup_phone_send_code)
                            showMotionSnackbar(FirebaseAuthManager.friendlyError(this@RegisterActivity, e))
                        }
                    )
                }
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                dialog.dismiss()
                completeAuthFlow()
            }
        }
        dialog.show()
    }

    private fun showSmsCodeDialog(verificationId: String, phone: String) {
        val input = EditText(this).apply {
            hint = getString(R.string.phone_verify_field_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            gravity = android.view.Gravity.CENTER
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = resources.getDimensionPixelSize(R.dimen.space_20)
            setPadding(pad, resources.getDimensionPixelSize(R.dimen.space_8), pad, 0)
            addView(input)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.phone_verify_title)
            .setMessage(getString(R.string.phone_verify_subtitle, phone))
            .setView(content)
            .setCancelable(false)
            .setPositiveButton(R.string.phone_verify_confirm, null)
            .setNegativeButton(R.string.signup_phone_skip, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val code = input.text?.toString().orEmpty().filter { it.isDigit() }
                if (code.length != 6) {
                    input.error = getString(R.string.phone_verify_invalid)
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    val button = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                    button.isEnabled = false
                    button.text = getString(R.string.phone_verify_verifying)
                    val result = FirebaseAuthManager.linkCurrentUserWithSmsCode(verificationId, code)
                    result.fold(
                        onSuccess = { user ->
                            runCatching { UserService.markPhoneVerified(user.uid, phone) }
                            dialog.dismiss()
                            showMotionSnackbar(getString(R.string.signup_phone_verified))
                            completeAuthFlow()
                        },
                        onFailure = { e ->
                            button.isEnabled = true
                            button.text = getString(R.string.phone_verify_confirm)
                            showMotionSnackbar(FirebaseAuthManager.friendlyError(this@RegisterActivity, e))
                        }
                    )
                }
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                dialog.dismiss()
                completeAuthFlow()
            }
        }
        dialog.show()
    }

    private fun normalizePhoneForSms(raw: String): String? {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        return when {
            trimmed.startsWith("+") && digits.length in 8..15 -> "+$digits"
            digits.startsWith("216") && digits.length == 11 -> "+$digits"
            digits.trimStart('0').length == 8 -> "+216${digits.trimStart('0')}"
            else -> null
        }
    }

    private fun completeAuthFlow() {
        when (intent.authReturnRoute()) {
            AUTH_RETURN_ROUTE_CHECKOUT -> {
                startActivity(android.content.Intent(this, CheckoutDetailsActivity::class.java))
                finishWithMotion(isForward = true)
            }
            AUTH_RETURN_ROUTE_ORDERS -> {
                startActivity(android.content.Intent(this, OrdersHistoryActivity::class.java))
                finishWithMotion(isForward = true)
            }
            else -> navigateToMainTab(intent.authReturnTab() ?: MainActivity.Tab.HOME)
        }
    }
}
