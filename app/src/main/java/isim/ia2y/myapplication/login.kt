package isim.ia2y.myapplication

import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class login : AppCompatActivity() {
    private var passwordVisible = false

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

    private fun setupLoginActions() {
        findViewById<View>(R.id.ivBack)?.setOnClickListener {
            navigateToMainTab(MainActivity.Tab.PROFILE)
        }
        findViewById<View>(R.id.ivAppLogo)?.setOnClickListener {
            navigateToMainTab(MainActivity.Tab.HOME)
        }
        findViewById<View>(R.id.tvSignUp)?.setOnClickListener {
            navigateNoShift(register::class.java)
        }

        bindComingSoon(R.id.tvForgotPassword, R.id.btnGoogle, R.id.btnFacebook)

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
}
