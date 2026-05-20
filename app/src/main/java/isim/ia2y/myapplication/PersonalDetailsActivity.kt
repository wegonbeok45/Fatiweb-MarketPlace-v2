package isim.ia2y.myapplication

import android.app.DatePickerDialog
import android.net.Uri
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

        lifecycleScope.launch {
            runCatching { UserAvatarService.uploadAndSaveAvatar(this@PersonalDetailsActivity, uid, uri) }
                .onSuccess { remoteUrl ->
                    if (isFinishing || isDestroyed) return@onSuccess
                    loadAvatarUrl(remoteUrl)
                    showSuccess(getString(R.string.profile_avatar_updated))
                }
                .onFailure { error ->
                    android.util.Log.e(TAG, "Avatar upload failed", error)
                    showMotionSnackbar(getString(R.string.profile_avatar_update_failed))
                }
        }
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
            showToast(getString(R.string.personal_details_saved))
            finishWithMotion()
            return
        }

        lifecycleScope.launch {
            val newDisplayName = details.fullName.ifBlank { currentUser.displayName.orEmpty() }
            val result = if (newDisplayName != currentUser.displayName.orEmpty()) {
                FirebaseAuthManager.updateDisplayName(newDisplayName)
            } else {
                Result.success(currentUser)
            }

            result.fold(
                onSuccess = {
                    showToast(getString(R.string.personal_details_saved))
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

    private companion object {
        const val TAG = "PersonalDetailsActivity"
    }
}
