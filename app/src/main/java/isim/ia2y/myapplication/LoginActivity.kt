package isim.ia2y.myapplication

import android.os.Bundle
import android.util.Log
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch
import isim.ia2y.myapplication.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {
    private var passwordVisible = false
    private var isEmailLoginSubmitting = false
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var binding: ActivityLoginBinding

    companion object {
        private const val KEY_EMAIL = "email"
        private const val KEY_PASSWORD_VISIBLE = "password_visible"

        fun createIntent(
            context: android.content.Context,
            returnToTab: MainActivity.Tab? = null,
            returnToRoute: String? = null
        ): android.content.Intent {
            return android.content.Intent(context, LoginActivity::class.java)
                .withAuthReturn(returnToTab, returnToRoute)
        }

        fun start(context: android.content.Context) {
            context.startActivity(createIntent(context))
        }
    }

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (data == null) {
            showMotionSnackbar(getString(R.string.auth_google_sign_in_failed))
            return@registerForActivityResult
        }
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data)
                .getResult(ApiException::class.java)
            val idToken = account?.idToken
            if (idToken == null) {
                showMotionSnackbar(getString(R.string.auth_google_sign_in_failed))
                return@registerForActivityResult
            }
            handleGoogleIdToken(idToken)
        } catch (e: ApiException) {
            Log.w("LoginActivity", "Google sign-in failed: ${e.statusCode} ${e.message}", e)
            showMotionSnackbar(getString(R.string.auth_google_sign_in_failed))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val hPadding = resources.getDimensionPixelSize(R.dimen.auth_screen_padding_compact)
            val vPadding = resources.getDimensionPixelSize(R.dimen.auth_screen_vertical_inset_padding)
            v.setPadding(hPadding, systemBars.top + vPadding, hPadding, systemBars.bottom + vPadding)
            insets
        }
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.google_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        setupLoginActions()

        if (savedInstanceState != null) {
            savedInstanceState.getString(KEY_EMAIL)?.let { email ->
                binding.etEmail.setText(email)
            }
            passwordVisible = savedInstanceState.getBoolean(KEY_PASSWORD_VISIBLE, false)
            val etPassword = binding.etPassword
            etPassword.transformationMethod =
                if (passwordVisible) null else PasswordTransformationMethod.getInstance()
            etPassword.setSelection(etPassword.text?.length ?: 0)
        }
        revealViewsInOrder(
            R.id.ivBack,
            R.id.cardWelcomePanel,
            R.id.tvWelcomeTitle,
            R.id.tvWelcomeSubtitle,
            R.id.cardEmailField,
            R.id.cardPasswordField,
            R.id.btnLogin,
            R.id.layoutSocialButtons,
            R.id.layoutSignUpRow
        )
        emphasizeCta(R.id.btnLogin)
    }

    private fun setupLoginActions() {
        binding.ivBack.setOnClickListener {
            finishAuthEntry()
        }
        binding.tvSignUp.setOnClickListener {
            startActivity(RegisterActivity.createIntent(this).copyAuthReturnFrom(intent))
        }
        setupGoogleLogin()

        binding.tvForgotPassword.setOnClickListener {
            showResetPasswordDialog()
        }

        binding.btnContinueAsGuest?.setOnClickListener {
            setResult(RESULT_OK)
            val returnTab = intent.authReturnTab()
            val returnRoute = intent.authReturnRoute()

            if (returnRoute == AUTH_RETURN_ROUTE_CHECKOUT || returnRoute == AUTH_RETURN_ROUTE_CART) {
                startActivity(android.content.Intent(this, CheckoutDetailsActivity::class.java).apply {
                    putExtra(EXTRA_GUEST_CHECKOUT, true)
                })
                finishWithMotion(isForward = true)
            } else {
                navigateToMainTab(returnTab ?: MainActivity.Tab.HOME)
                finishWithMotion(isForward = false)
            }
        }

        val etEmail = binding.etEmail
        val etPassword = binding.etPassword
        val btnLogin = binding.btnLogin

        bindInputFieldMotion(R.id.cardEmailField, R.id.etEmail) { value ->
            value.contains("@") && value.contains(".")
        }
        bindInputFieldMotion(R.id.cardPasswordField, R.id.etPassword) { value ->
            value.length >= 6
        }

        btnLogin?.setOnClickListener {
            if (isEmailLoginSubmitting) return@setOnClickListener

            val email = etEmail.text?.toString().orEmpty().trim()
            val password = etPassword.text?.toString().orEmpty()

            val hasValidEmail = email.contains("@") && email.contains(".")
            val hasValidPassword = password.length >= 6

            if (!hasValidEmail || !hasValidPassword) {
                if (!hasValidEmail) markInputState(R.id.cardEmailField, InputFieldState.ERROR)
                if (!hasValidPassword) markInputState(R.id.cardPasswordField, InputFieldState.ERROR)
                showMotionSnackbar(getString(R.string.login_validation_error))
                return@setOnClickListener
            }

            lifecycleScope.launch {
                setEmailLoginLoading(true)
                try {
                    val result = FirebaseAuthManager.signIn(email, password)
                    result.fold(
                        onSuccess = {
                            GuestSessionMerger.mergeIntoCurrentUserInBackground(this@LoginActivity)
                            AnalyticsTracker.login("email")
                            markInputState(R.id.cardEmailField, InputFieldState.SUCCESS)
                            markInputState(R.id.cardPasswordField, InputFieldState.SUCCESS)
                            completeAuthFlow()
                        },
                        onFailure = { e ->
                            markInputState(R.id.cardEmailField, InputFieldState.ERROR)
                            markInputState(R.id.cardPasswordField, InputFieldState.ERROR)
                            showMotionSnackbar(FirebaseAuthManager.friendlyError(this@LoginActivity, e))
                        }
                    )
                } finally {
                    if (!isFinishing && !isDestroyed) {
                        setEmailLoginLoading(false)
                    }
                }
            }
        }

        binding.ivPasswordToggle.setOnClickListener {
            passwordVisible = !passwordVisible
            etPassword.transformationMethod =
                if (passwordVisible) null else PasswordTransformationMethod.getInstance()
            etPassword.setSelection(etPassword.text?.length ?: 0)
        }
        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                btnLogin?.performClick()
                true
            } else {
                false
            }
        }
        applyPressFeedback(
            R.id.ivBack,
            R.id.tvSignUp,
            R.id.tvForgotPassword,
            R.id.btnLogin,
            R.id.btnGoogle,
            R.id.ivPasswordToggle
        )
    }

    private fun setupGoogleLogin() {
        binding.btnGoogle.setOnClickListener {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }
    }

    private fun handleGoogleIdToken(idToken: String) {
        lifecycleScope.launch {
            val result = FirebaseAuthManager.signInWithGoogle(idToken)
            result.fold(
                onSuccess = {
                    GuestSessionMerger.mergeIntoCurrentUserInBackground(this@LoginActivity)
                    AnalyticsTracker.login("google")
                    completeAuthFlow()
                },
                onFailure = { e -> showMotionSnackbar(FirebaseAuthManager.friendlyError(this@LoginActivity, e)) }
            )
        }
    }

    private fun showResetPasswordDialog() {
        val prefill = binding.etEmail.text?.toString().orEmpty()
        val view = layoutInflater.inflate(R.layout.dialog_single_input, null)
        val input = view.findViewById<EditText>(R.id.etDialogInput).apply {
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            hint = getString(R.string.auto_text_035)
            if (prefill.isNotBlank()) {
                setText(prefill)
                setSelection(prefill.length)
            }
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.auth_reset_title))
            .setMessage(getString(R.string.auth_reset_message))
            .setView(view)
            .setNegativeButton(getString(R.string.profile_edit_cancel), null)
            .setPositiveButton(getString(R.string.auth_reset_send), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val email = input.text?.toString().orEmpty().trim()
                if (!email.contains("@") || !email.contains(".")) {
                    showMotionSnackbar(getString(R.string.auth_invalid_email))
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    val result = FirebaseAuthManager.sendPasswordReset(email)
                    result.fold(
                        onSuccess = {
                            dialog.dismiss()
                            showMotionSnackbar(getString(R.string.auth_reset_email_sent))
                        },
                        onFailure = { error ->
                            showMotionSnackbar(FirebaseAuthManager.friendlyError(this@LoginActivity, error))
                        }
                    )
                }
            }
        }
        dialog.show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_EMAIL, binding.etEmail.text?.toString().orEmpty())
        outState.putBoolean(KEY_PASSWORD_VISIBLE, passwordVisible)
    }

    private fun finishAuthEntry() {
        intent.authReturnTab()?.let {
            navigateToMainTab(it)
            return
        }
        finishWithMotion(isForward = false)
    }

    private fun setEmailLoginLoading(isLoading: Boolean) {
        isEmailLoginSubmitting = isLoading
        binding.btnLogin.isEnabled = !isLoading
        binding.btnLogin.text =
            getString(if (isLoading) R.string.login_connecting else R.string.login_btn_label)
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
