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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {
    private var passwordVisible = false
    private var isRegisterSubmitting = false
    private lateinit var googleSignInClient: GoogleSignInClient

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
            Log.w("RegisterActivity", "Google sign-in failed: ${e.statusCode} ${e.message}", e)
            showMotionSnackbar(getString(R.string.auth_google_sign_in_failed))
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
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.google_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
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

            val hasName = name.length >= 3
            val hasValidEmail = email.contains("@") && email.contains(".")
            val hasValidPassword = password.length >= 6

            if (!hasName || !hasValidEmail || !hasValidPassword) {
                if (!hasName) markInputState(R.id.cardFullNameField, InputFieldState.ERROR)
                if (!hasValidEmail) markInputState(R.id.cardEmailField, InputFieldState.ERROR)
                if (!hasValidPassword) markInputState(R.id.cardPasswordField, InputFieldState.ERROR)
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
                            runCatching { FirebaseAuthManager.sendEmailVerification() }
                            markInputState(R.id.cardFullNameField, InputFieldState.SUCCESS)
                            markInputState(R.id.cardEmailField, InputFieldState.SUCCESS)
                            markInputState(R.id.cardPasswordField, InputFieldState.SUCCESS)
                            showEmailVerificationDialog(email)
                        },
                        onFailure = { e ->
                            showMotionSnackbar(FirebaseAuthManager.friendlyError(this@RegisterActivity, e))
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
        findViewById<View>(R.id.btnGoogle)?.setOnClickListener {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
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

    private fun showEmailVerificationDialog(email: String) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.auth_verify_dialog_title))
            .setMessage(getString(R.string.auth_verify_dialog_message, email))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.auth_verify_dialog_confirmed), null)
            .setNeutralButton(getString(R.string.auth_verify_dialog_resend), null)
            .setNegativeButton(getString(R.string.auth_verify_dialog_cancel), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                lifecycleScope.launch {
                    val result = FirebaseAuthManager.reloadAndCheckEmailVerified()
                    result.fold(
                        onSuccess = { verified ->
                            if (verified) {
                                dialog.dismiss()
                                completeAuthFlow()
                            } else {
                                showMotionSnackbar(getString(R.string.auth_verify_not_yet))
                            }
                        },
                        onFailure = { e ->
                            showMotionSnackbar(FirebaseAuthManager.friendlyError(this@RegisterActivity, e))
                        }
                    )
                }
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                lifecycleScope.launch {
                    runCatching { FirebaseAuthManager.sendEmailVerification() }
                    showMotionSnackbar(getString(R.string.auth_verify_resent))
                }
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                FirebaseAuthManager.signOut()
                dialog.dismiss()
            }
        }
        dialog.show()
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
