package isim.ia2y.myapplication

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class PersonalDetailsActivity : AppCompatActivity() {
    private lateinit var inputFirstName: EditText
    private lateinit var inputLastName: EditText
    private lateinit var inputEmail: EditText
    private lateinit var inputPhone: EditText
    private lateinit var inputBirthday: EditText
    private lateinit var inputGender: AutoCompleteTextView
    private lateinit var inputBio: EditText
    private lateinit var imageAvatar: ImageView

    private val avatarPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult

        val uid = FirebaseAuthManager.currentUser?.uid
        if (uid == null) {
            showMotionSnackbar(getString(R.string.profile_avatar_login_required))
            return@registerForActivityResult
        }

        setAvatarUploadOverlayVisible(true)
        lifecycleScope.launch {
            runCatching { UserAvatarService.uploadAndSaveAvatar(this@PersonalDetailsActivity, uid, uri) }
                .onSuccess { remoteUrl ->
                    if (isFinishing || isDestroyed) return@onSuccess
                    setAvatarUploadOverlayVisible(false)
                    loadAvatarUrl(remoteUrl)
                    showSuccess(getString(R.string.profile_avatar_updated))
                }
                .onFailure { error ->
                    android.util.Log.e(TAG, "Avatar upload failed", error)
                    if (isFinishing || isDestroyed) return@onFailure
                    setAvatarUploadOverlayVisible(false)
                    showMotionSnackbar(avatarErrorMessage(error))
                }
        }
    }

    private fun setAvatarUploadOverlayVisible(visible: Boolean) {
        findViewById<View>(R.id.layoutAvatarUploadOverlay)?.visibility =
            if (visible) View.VISIBLE else View.GONE
    }

    private fun avatarErrorMessage(error: Throwable): String {
        val reason = (error as? UserAvatarService.AvatarUploadException)?.reason
            ?: UserAvatarService.FailureReason.UNKNOWN
        val resId = when (reason) {
            UserAvatarService.FailureReason.NETWORK -> R.string.profile_avatar_error_network
            UserAvatarService.FailureReason.PERMISSION -> R.string.profile_avatar_error_permission
            UserAvatarService.FailureReason.TOO_LARGE -> R.string.profile_avatar_error_too_large
            UserAvatarService.FailureReason.UNKNOWN -> R.string.profile_avatar_update_failed
        }
        return getString(resId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_personal_details)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        bindViews()
        bindActions()
        renderDetails()
        revealViewsInOrder(
            R.id.layoutTopBar,
            R.id.layoutProfileHero,
            R.id.cardDetailsForm,
            R.id.layoutActionButtons
        )
    }

    private fun bindViews() {
        imageAvatar = findViewById(R.id.ivAvatar)
        inputFirstName = findViewById(R.id.etPersonalFirstName)
        inputLastName = findViewById(R.id.etPersonalLastName)
        inputEmail = findViewById(R.id.etPersonalEmail)
        inputPhone = findViewById(R.id.etPersonalPhone)
        inputBirthday = findViewById(R.id.etPersonalBirthday)
        inputGender = findViewById(R.id.etPersonalGender)
        inputBio = findViewById(R.id.etPersonalBio)

        val genderValues = listOf(
            getString(R.string.personal_details_gender_female),
            getString(R.string.personal_details_gender_male),
            getString(R.string.personal_details_gender_other),
            getString(R.string.personal_details_gender_private)
        )
        inputGender.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, genderValues)
        )
        inputGender.inputType = InputType.TYPE_NULL
        inputBirthday.inputType = InputType.TYPE_NULL
        inputBirthday.keyListener = null
    }

    private fun bindActions() {
        findViewById<View>(R.id.ivBack)?.setOnClickListener { finishWithMotion() }
        findViewById<View>(R.id.cardAvatar)?.setOnClickListener { avatarPicker.launch("image/*") }
        findViewById<View>(R.id.tvChangePhoto)?.setOnClickListener { avatarPicker.launch("image/*") }
        findViewById<View>(R.id.btnSaveChanges)?.setOnClickListener { saveDetails() }
        findViewById<View>(R.id.btnCancelChanges)?.setOnClickListener { finishWithMotion() }
        findViewById<View>(R.id.btnDeleteAccount)?.setOnClickListener { confirmDeleteAccount() }
        inputBirthday.setOnClickListener { openDatePicker() }
        inputBirthday.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) openDatePicker() }

        applyPressFeedback(
            R.id.ivBack,
            R.id.cardAvatar,
            R.id.tvChangePhoto,
            R.id.btnSaveChanges,
            R.id.btnCancelChanges
        )
    }

    private fun renderDetails() {
        val details = PersonalDetailsStore.load(this)
        inputFirstName.setText(details.firstName)
        inputLastName.setText(details.lastName)
        inputEmail.setText(details.email)
        inputPhone.setText(details.phone)
        inputBirthday.setText(details.birthday)
        inputGender.setText(details.gender, false)
        inputBio.setText(details.bio)
        inputEmail.isEnabled = FirebaseAuthManager.currentUser == null
        inputEmail.alpha = if (inputEmail.isEnabled) 1f else 0.72f
        loadCurrentAvatar()
    }

    private fun saveDetails() {
        val firstName = inputFirstName.text?.toString().orEmpty().trim()
        val lastName = inputLastName.text?.toString().orEmpty().trim()
        val email = inputEmail.text?.toString().orEmpty().trim()
        val phone = inputPhone.text?.toString().orEmpty().trim()
        val birthday = inputBirthday.text?.toString().orEmpty().trim()
        val gender = inputGender.text?.toString().orEmpty().trim()
        val bio = inputBio.text?.toString().orEmpty().trim()

        if (firstName.length < 2 || lastName.length < 2) {
            showMotionSnackbar(getString(R.string.personal_details_validation_name))
            return
        }
        if (email.isNotBlank() && (!email.contains("@") || !email.contains("."))) {
            showMotionSnackbar(getString(R.string.personal_details_validation_email))
            return
        }

        val details = UserPersonalDetails(
            firstName = firstName,
            lastName = lastName,
            email = email,
            phone = phone,
            birthday = birthday,
            gender = gender,
            bio = bio
        )
        PersonalDetailsStore.save(this, details)

        val currentUser = FirebaseAuthManager.currentUser
        if (currentUser == null) {
            showMotionSnackbar(getString(R.string.personal_details_saved))
            finishWithMotion()
            return
        }

        lifecycleScope.launch {
            val newDisplayName = details.fullName.ifBlank { currentUser.displayName.orEmpty() }
            val nameResult = if (newDisplayName.isNotBlank() && newDisplayName != currentUser.displayName.orEmpty()) {
                FirebaseAuthManager.updateDisplayName(newDisplayName)
            } else {
                Result.success(currentUser)
            }

            nameResult.fold(
                onSuccess = {
                    // Mirror the change into the Firestore user doc so it's not only on this device.
                    runCatching {
                        withContext(Dispatchers.IO) {
                            if (newDisplayName.isNotBlank()) {
                                UserService.updateUserProfileName(currentUser.uid, newDisplayName)
                            }
                        }
                    }.onFailure { error ->
                        android.util.Log.w(TAG, "Firestore profile name sync failed", error)
                    }
                    showSuccess(getString(R.string.personal_details_saved))
                    finishWithMotion()
                },
                onFailure = { error ->
                    showMotionSnackbar(FirebaseAuthManager.friendlyError(this@PersonalDetailsActivity, error))
                }
            )
        }
    }

    private fun openDatePicker() {
        val calendar = Calendar.getInstance()
        parseBirthday(inputBirthday.text?.toString().orEmpty())?.let {
            calendar.timeInMillis = it
        }
        val dialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val selected = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth)
                }
                inputBirthday.setText(
                    SimpleDateFormat("MM/dd/yyyy", Locale.US).format(selected.time)
                )
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        dialog.show()
    }

    private fun parseBirthday(value: String): Long? {
        if (value.isBlank()) return null
        return runCatching {
            SimpleDateFormat("MM/dd/yyyy", Locale.US).parse(value)?.time
        }.getOrNull()
    }

    private fun loadCurrentAvatar() {
        val user = FirebaseAuthManager.currentUser
        if (user == null) {
            imageAvatar.setImageResource(R.drawable.profile_avatar_art)
            return
        }

        lifecycleScope.launch {
            val remoteAvatar = runCatching {
                withContext(Dispatchers.IO) { FirestoreService.fetchUserProfile(user.uid)?.avatarUrl }
            }.getOrNull()

            when {
                !remoteAvatar.isNullOrBlank() -> loadAvatarUrl(remoteAvatar)
                else -> imageAvatar.setImageResource(R.drawable.profile_avatar_art)
            }
        }
    }

    private fun loadAvatarUrl(url: String?) {
        if (!url.isNullOrBlank()) {
            imageAvatar.loadAvatarImage(url)
            return
        }
        imageAvatar.setImageResource(R.drawable.profile_avatar_art)
    }

    private fun confirmDeleteAccount() {
        if (FirebaseAuthManager.currentUser == null) {
            showMotionSnackbar(getString(R.string.profile_avatar_login_required))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.account_delete_title)
            .setMessage(R.string.account_delete_message)
            .setNegativeButton(R.string.account_delete_cancel, null)
            .setPositiveButton(R.string.account_delete_confirm) { _, _ -> performDeleteAccount() }
            .show()
    }

    private fun performDeleteAccount() {
        val progress = AlertDialog.Builder(this)
            .setMessage(R.string.account_delete_in_progress)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val result = runCatching { BackendFunctionsService.deleteUserAccount() }
            runCatching { progress.dismiss() }
            result
                .onSuccess {
                    runCatching { FirebaseAuthManager.signOut() }
                    showSuccess(getString(R.string.account_delete_success))
                    val intent = Intent(this@PersonalDetailsActivity, LoginActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    startActivity(intent)
                    finish()
                }
                .onFailure { error ->
                    android.util.Log.e(TAG, "Account deletion failed", error)
                    val msg = if (error.message?.contains("Admin", ignoreCase = true) == true) {
                        getString(R.string.account_delete_admin_blocked)
                    } else {
                        getString(R.string.account_delete_failed)
                    }
                    showMotionSnackbar(msg)
                }
        }
    }

    private companion object {
        const val TAG = "PersonalDetailsActivity"
    }
}
