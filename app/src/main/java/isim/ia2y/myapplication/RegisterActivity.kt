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
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class RegisterActivity : AppCompatActivity() {
    private var passwordVisible = false
    private var isRegisterSubmitting = false
    private lateinit var callbackManager: CallbackManager
    private lateinit var googleSignInClient: GoogleSignInClient

    companion object {
        private const val KEY_NAME = "name"
        private const val KEY_EMAIL = "email"
        private const val KEY_PASSWORD = "password"
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
        callbackManager = CallbackManager.Factory.create()
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
            savedInstanceState.getString(KEY_PASSWORD)?.let { password ->
                findViewById<EditText>(R.id.etPassword)?.setText(password)
            }
            passwordVisible = savedInstanceState.getBoolean(KEY_PASSWORD_VISIBLE, false)
            val etPassword = findViewById<EditText>(R.id.etPassword)
            etPassword?.transformationMethod =
                if (passwordVisible) null else PasswordTransformationMethod.getInstance()
            etPassword?.setSelection(etPassword.text?.length ?: 0)
        }

        revealViewsInOrder(
            R.id.ivBack,
            R.id.ivAppLogo,
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
        findViewById<View>(R.id.ivAppLogo)?.setOnClickListener {
            navigateToMainTab(MainActivity.Tab.HOME)
        }
        findViewById<View>(R.id.tvGoToLogin)?.setOnClickListener {
            startActivity(LoginActivity.createIntent(this).copyAuthReturnFrom(intent))
        }
        configureFacebookAvailability()
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
                            lifecycleScope.launch(Dispatchers.IO) {
                                GuestSessionMerger.mergeIntoCurrentUser(this@RegisterActivity)
                            }
                            lifecycleScope.launch { runCatching { FirebaseAuthManager.sendEmailVerification() } }
                            markInputState(R.id.cardFullNameField, InputFieldState.SUCCESS)
                            markInputState(R.id.cardEmailField, InputFieldState.SUCCESS)
                            markInputState(R.id.cardPasswordField, InputFieldState.SUCCESS)
                            showMotionSnackbar(getString(R.string.auth_verification_sent))
                            completeAuthFlow()
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
            R.id.ivAppLogo,
            R.id.tvGoToLogin,
            R.id.btnRegister,
            R.id.btnGoogle,
            R.id.btnFacebook,
            R.id.ivPasswordToggle
        )
    }

    private fun configureFacebookAvailability() {
        val btnFacebook = findViewById<View>(R.id.btnFacebook) ?: return
        if (!isFacebookConfigured()) {
            btnFacebook.visibility = View.GONE
            val btnGoogle = findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGoogle)
            (btnGoogle?.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)?.apply {
                endToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                endToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                startToStart = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.PARENT_ID
                startToEnd = androidx.constraintlayout.widget.ConstraintLayout.LayoutParams.UNSET
                width = 0
                btnGoogle.layoutParams = this
            }
            return
        }
        setupFacebookLogin()
    }

    private fun setupFacebookLogin() {
        val btnFacebook = findViewById<View>(R.id.btnFacebook) ?: return
        
        btnFacebook.setOnClickListener {
            LoginManager.getInstance().logInWithReadPermissions(
                this,
                callbackManager,
                listOf("email", "public_profile")
            )
        }

        LoginManager.getInstance().registerCallback(callbackManager, object : FacebookCallback<LoginResult> {
            override fun onSuccess(result: LoginResult) {
                handleFacebookAccessToken(result.accessToken.token)
            }

            override fun onCancel() {
                showMotionSnackbar(getString(R.string.auth_cancelled))
            }

            override fun onError(error: FacebookException) {
                showMotionSnackbar(getString(R.string.auth_facebook_error, error.message ?: ""))
            }
        })
    }

    private fun handleFacebookAccessToken(token: String) {
        lifecycleScope.launch {
            val result = FirebaseAuthManager.signInWithFacebook(token)
            result.fold(
                onSuccess = {
                    lifecycleScope.launch(Dispatchers.IO) {
                        GuestSessionMerger.mergeIntoCurrentUser(this@RegisterActivity)
                    }
                    completeAuthFlow()
                },
                onFailure = { e ->
                    showMotionSnackbar(FirebaseAuthManager.friendlyError(this@RegisterActivity, e))
                }
            )
        }
    }


    // Forward Facebook activity results to its callback manager
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        callbackManager.onActivityResult(requestCode, resultCode, data)
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
                    lifecycleScope.launch(Dispatchers.IO) {
                        GuestSessionMerger.mergeIntoCurrentUser(this@RegisterActivity)
                    }
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
        outState.putString(KEY_PASSWORD, findViewById<EditText>(R.id.etPassword)?.text?.toString().orEmpty())
        outState.putBoolean(KEY_PASSWORD_VISIBLE, passwordVisible)
    }

    private fun isFacebookConfigured(): Boolean {
        val appId = getString(R.string.facebook_app_id)
        val clientToken = getString(R.string.facebook_client_token)
        return appId.isNotBlank() &&
            clientToken.isNotBlank() &&
            !appId.startsWith("YOUR_") &&
            !clientToken.startsWith("YOUR_")
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
