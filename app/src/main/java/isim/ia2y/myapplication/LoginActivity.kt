package isim.ia2y.myapplication

import android.os.Bundle
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
import kotlinx.coroutines.launch

// Cette classe organise cette partie de l'app.
class LoginActivity : AppCompatActivity() {
    private var passwordVisible = false
    private lateinit var callbackManager: CallbackManager
    private lateinit var googleSignInClient: GoogleSignInClient

    private val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)!!
            handleGoogleIdToken(account.idToken!!)
        } catch (e: ApiException) {
            showMotionSnackbar("Google error ${e.statusCode}: ${e.message}")
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_login)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val hPadding = resources.getDimensionPixelSize(R.dimen.auth_screen_padding)
            val vPadding = resources.getDimensionPixelSize(R.dimen.auth_screen_padding)
            v.setPadding(hPadding, systemBars.top + vPadding, hPadding, systemBars.bottom + vPadding)
            insets
        }
        callbackManager = CallbackManager.Factory.create()
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.google_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)
        setupLoginActions()
        revealViewsInOrder(
            R.id.ivBack,
            R.id.ivAppLogo,
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

    // Cette fonction fait une action de cette partie de l'app.
    private fun setupLoginActions() {
        findViewById<View>(R.id.ivBack)?.setOnClickListener {
            navigateToMainTab(MainActivity.Tab.PROFILE)
        }
        findViewById<View>(R.id.ivAppLogo)?.setOnClickListener {
            navigateToMainTab(MainActivity.Tab.HOME)
        }
        findViewById<View>(R.id.tvSignUp)?.setOnClickListener {
            navigateNoShift(RegisterActivity::class.java)
        }
        setupFacebookLogin()
        setupGoogleLogin()

        bindComingSoon(R.id.tvForgotPassword)

        val etEmail = findViewById<EditText>(R.id.etEmail)
        val etPassword = findViewById<EditText>(R.id.etPassword)
        val btnLogin = findViewById<TextView>(R.id.btnLogin)

        bindInputFieldMotion(R.id.cardEmailField, R.id.etEmail) { value ->
            value.contains("@") && value.contains(".")
        }
        bindInputFieldMotion(R.id.cardPasswordField, R.id.etPassword) { value ->
            value.length >= 6
        }

        btnLogin?.setOnClickListener {
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

            // Disable button and show loading state
            btnLogin.isEnabled = false
            btnLogin.text = "Connexion…"

            lifecycleScope.launch {
                val result = FirebaseAuthManager.signIn(email, password)
                result.fold(
                    onSuccess = {
                        markInputState(R.id.cardEmailField, InputFieldState.SUCCESS)
                        markInputState(R.id.cardPasswordField, InputFieldState.SUCCESS)
                        navigateToMainTab(MainActivity.Tab.HOME)
                    },
                    onFailure = { e ->
                        btnLogin.isEnabled = true
                        btnLogin.text = getString(R.string.login_btn_label)
                        markInputState(R.id.cardEmailField, InputFieldState.ERROR)
                        markInputState(R.id.cardPasswordField, InputFieldState.ERROR)
                        showMotionSnackbar(FirebaseAuthManager.friendlyError(e))
                    }
                )
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
                btnLogin?.performClick()
                true
            } else {
                false
            }
        }
        applyPressFeedback(
            R.id.ivBack,
            R.id.ivAppLogo,
            R.id.tvSignUp,
            R.id.tvForgotPassword,
            R.id.btnLogin,
            R.id.btnGoogle,
            R.id.btnFacebook,
            R.id.ivPasswordToggle
        )
    }

    // Cette fonction fait une action de cette partie de l'app.
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
            // Cette fonction fait une action de cette partie de l'app.
            override fun onSuccess(result: LoginResult) {
                handleFacebookAccessToken(result.accessToken.token)
            }

            // Cette fonction fait une action de cette partie de l'app.
            override fun onCancel() {
                showMotionSnackbar("Connexion annulée")
            }

            // Cette fonction fait une action de cette partie de l'app.
            override fun onError(error: FacebookException) {
                showMotionSnackbar("Erreur Facebook: ${error.message}")
            }
        })
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun handleFacebookAccessToken(token: String) {
        lifecycleScope.launch {
            val result = FirebaseAuthManager.signInWithFacebook(token)
            result.fold(
                onSuccess = {
                    navigateToMainTab(MainActivity.Tab.HOME)
                },
                onFailure = { e ->
                    showMotionSnackbar(FirebaseAuthManager.friendlyError(e))
                }
            )
        }
    }

    // Forward Facebook activity results to its callback manager
    @Suppress("DEPRECATION")
    // Cette fonction fait une action de cette partie de l'app.
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        callbackManager.onActivityResult(requestCode, resultCode, data)
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun setupGoogleLogin() {
        findViewById<View>(R.id.btnGoogle)?.setOnClickListener {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }
    }

    // Cette fonction fait une action de cette partie de l'app.
    private fun handleGoogleIdToken(idToken: String) {
        lifecycleScope.launch {
            val result = FirebaseAuthManager.signInWithGoogle(idToken)
            result.fold(
                onSuccess = { navigateToMainTab(MainActivity.Tab.HOME) },
                onFailure = { e -> showMotionSnackbar(FirebaseAuthManager.friendlyError(e)) }
            )
        }
    }
}
