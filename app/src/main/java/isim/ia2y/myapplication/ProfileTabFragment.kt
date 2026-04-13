package isim.ia2y.myapplication

import android.Manifest
import android.content.res.ColorStateList
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import isim.ia2y.myapplication.databinding.FragmentProfileTabBinding
import android.view.LayoutInflater
import android.view.ViewGroup

class ProfileTabFragment : Fragment() {

    private var _binding: FragmentProfileTabBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private val avatarPrefsName = "profile_prefs"
    private val avatarUriKey = "avatar_uri"
    private val logTag = "ProfileTabFragment"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var latestProfile: FirestoreService.UserProfile? = null
    private var latestRole: String = "client"

    private val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        
        val uid = FirebaseAuthManager.currentUser?.uid ?: return@registerForActivityResult
        
        lifecycleScope.launch {
            val progress = (activity as? AppCompatActivity)?.showMotionSnackbar(getString(R.string.profile_avatar_uploading))
            runCatching { UserAvatarStorage.uploadAvatar(requireContext(), uid, uri) }
                .onSuccess { remoteUrl ->
                    UserService.updateUserAvatarUrl(uid, remoteUrl)
                    loadAvatarFromPath(remoteUrl)
                    (activity as? AppCompatActivity)?.showMotionSnackbar(getString(R.string.profile_avatar_updated))
                }
                .onFailure { error ->
                    Log.e(logTag, "Avatar upload failed", error)
                    (activity as? AppCompatActivity)?.showMotionSnackbar(getString(R.string.profile_avatar_update_failed))
                    
                    // Fallback to local
                    val savedPath = copyUriToInternalStorage(uri)
                    if (savedPath != null) {
                        saveAvatarPath(savedPath)
                        loadAvatarFromPath(savedPath)
                    }
                }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        runCatching {
            view.applyStatusBarInset()
            binding.layoutBottomNav.isGone = true
            binding.viewBottomDivider.isGone = true
            setupProfileActions()
            binding.tvUserName.text = getString(R.string.user_guest_name)
            updatePrimaryAccountAction(FirebaseAuthManager.isLoggedIn)
            restoreAvatar()
            refreshProfileLocation()
            (activity as? AppCompatActivity)?.revealViewsInOrder(
                R.id.layoutTopBar,
                R.id.layoutHeader,
                R.id.cardAdmin,
                R.id.cardOrders,
                R.id.cardAddresses,
                R.id.cardAccountCenter,
                R.id.cardSettings,
                R.id.cardPrivacy,
                R.id.cardHelp,
                R.id.cardLogout
            )
        }.onFailure { error ->
            Log.e(logTag, "Failed to initialize profile tab", error)
            (activity as? AppCompatActivity)?.showToast(getString(R.string.coming_soon))
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.HOME, animate = false)
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.updateHostCartBadge()
        refreshProfileLocation()
        refreshUserInfo()
    }



    private fun setupProfileActions() {
        binding.ivBack.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.HOME, animate = false)
        }
        val cardAdmin = binding.cardAdmin
        cardAdmin.visibility = View.GONE
        cardAdmin.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateNoShift(AdminDashboardActivity::class.java)
        }
        (activity as? AppCompatActivity)?.bindNotificationEntry(R.id.ivNotifications)
        binding.cardEdit.visibility = if (FirebaseAuthManager.isLoggedIn) View.VISIBLE else View.GONE
        binding.tvRole.text = getString(R.string.profile_signup_chip)
        binding.cardAvatar.setOnClickListener { openAvatarPicker() }
        binding.cardEdit.setOnClickListener { openAvatarPicker() }
        binding.tvUserName.setOnClickListener {
            if (FirebaseAuthManager.isLoggedIn) {
                showEditProfileDialog()
            } else {
                showAuthGate()
            }
        }
        binding.tvRole.setOnClickListener {
            if (FirebaseAuthManager.isLoggedIn) {
                showEditProfileDialog()
            } else {
                showAuthGate()
            }
        }
        binding.cardSettings.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateNoShift(SettingsActivity::class.java)
        }
        binding.cardAccountCenter.setOnClickListener { showAccountCenterDialog() }
        binding.cardOrders.setOnClickListener {
            if (FirebaseAuthManager.isLoggedIn) {
                (activity as? AppCompatActivity)?.navigateNoShift(OrdersHistoryActivity::class.java)
            } else {
                showAuthGate(returnRoute = AUTH_RETURN_ROUTE_ORDERS)
            }
        }
        binding.cardAddresses.setOnClickListener {
            if (FirebaseAuthManager.isLoggedIn) {
                (activity as? AppCompatActivity)?.navigateNoShift(AddressesActivity::class.java)
            } else {
                showAuthGate(returnToTab = MainActivity.Tab.PROFILE)
            }
        }
        binding.tvLocation.setOnClickListener {
            val context = context ?: return@setOnClickListener
            if (LocationHelper.hasPermission(context)) {
                refreshProfileLocation(forceResolve = true)
            } else {
                requestLocationLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        }
        binding.cardPrivacy.setOnClickListener { showPrivacyDialog() }
        binding.cardHelp.setOnClickListener { showSupportDialog() }
        binding.cardLogout.setOnClickListener {
            if (FirebaseAuthManager.isLoggedIn) {
                FirebaseAuthManager.signOut()
                (activity as? AppCompatActivity)?.navigateNoShift(LoginActivity::class.java)
            } else {
                (activity as? AppCompatActivity)?.navigateNoShift(LoginActivity::class.java)
            }
        }
        updatePrimaryAccountAction(FirebaseAuthManager.isLoggedIn)
        (activity as? AppCompatActivity)?.applyPressFeedback(
            R.id.ivBack,
            R.id.ivNotifications,
            R.id.tvRole,
            R.id.tvLocation,
            R.id.cardAvatar,
            R.id.cardEdit,
            R.id.tvUserName,
            R.id.cardOrders,
            R.id.cardAddresses,
            R.id.cardAccountCenter,
            R.id.cardSettings,
            R.id.cardPrivacy,
            R.id.cardHelp,
            R.id.cardLogout
        )
    }

    private fun openAvatarPicker() {
        if (!FirebaseAuthManager.isLoggedIn) {
            showAuthGate(returnToTab = MainActivity.Tab.PROFILE)
            return
        }
        pickAvatarLauncher.launch("image/*")
    }

    /**
     * Copies the picked image into internal app storage and returns the absolute file path.
     * Storing a raw file path (not a file:// URI) avoids FileUriExposedException on Android 7+.
     */
    private fun copyUriToInternalStorage(uri: Uri): String? {
        val uid = FirebaseAuthManager.currentUser?.uid ?: return null
        return runCatching {
            val context = requireContext()
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val file = java.io.File(context.filesDir, "avatar_${uid}.jpg")
            java.io.FileOutputStream(file).use { output ->
                inputStream.use { input -> input.copyTo(output) }
            }
            file.absolutePath
        }.getOrNull()
    }

    private fun saveAvatarPath(path: String) {
        val uid = FirebaseAuthManager.currentUser?.uid ?: return
        requireContext().getSharedPreferences(avatarPrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString("${avatarUriKey}_$uid", path)
            .apply()
    }

    private fun loadAvatarFromPath(path: String) {
        val imageView = _binding?.ivAvatar ?: return
        
        if (path.startsWith("http")) {
            imageView.loadCatalogImage(path, R.drawable.placeholder)
            return
        }

        runCatching {
            val options = android.graphics.BitmapFactory.Options()
            options.inJustDecodeBounds = true
            android.graphics.BitmapFactory.decodeFile(path, options)

            val reqSize = 256
            var inSampleSize = 1
            if (options.outHeight > reqSize || options.outWidth > reqSize) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while (halfHeight / inSampleSize >= reqSize && halfWidth / inSampleSize >= reqSize) {
                    inSampleSize *= 2
                }
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            
            val bitmap = android.graphics.BitmapFactory.decodeFile(path, options)
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            } else {
                Log.w(logTag, "Avatar bitmap was null for path: $path")
            }
        }.onFailure { e ->
            Log.e(logTag, "Failed to load avatar from path", e)
        }
    }

    private fun restoreAvatar() {
        val uid = FirebaseAuthManager.currentUser?.uid
        if (uid != null) {
            lifecycleScope.launch {
                val profile = runCatching {
                    withContext(Dispatchers.IO) { FirestoreService.fetchUserProfile(uid) }
                }
                    .onFailure { Log.w(logTag, "Failed to load avatar from Firestore", it) }
                    .getOrNull()
                
                if (profile?.avatarUrl != null) {
                    loadAvatarFromPath(profile.avatarUrl)
                    return@launch
                }
                
                // Local fallback
                val context = context ?: return@launch
                val saved = context.getSharedPreferences(avatarPrefsName, Context.MODE_PRIVATE)
                    .getString("${avatarUriKey}_$uid", null)
                if (saved != null) {
                    val filePath = if (saved.startsWith("file://")) {
                        Uri.parse(saved).path ?: return@launch
                    } else {
                        saved
                    }
                    val file = java.io.File(filePath)
                    if (file.exists()) {
                        loadAvatarFromPath(filePath)
                    }
                }
            }
        }
    }

    /** Refresh the displayed user name/email from Firebase Auth. */
    private fun refreshUserInfo() {
        val bind = _binding ?: return
        val firebaseUser = FirebaseAuthManager.currentUser
        if (firebaseUser != null) {
            bind.cardAdmin.visibility = View.GONE
            bind.tvUserName.text =
                firebaseUser.displayName?.ifEmpty { null } ?: getString(R.string.user_guest_name)
            bind.tvAccountHint.text = firebaseUser.email.orEmpty()
            bind.tvAccountHint.visibility = if (firebaseUser.email.isNullOrBlank()) View.GONE else View.VISIBLE
            bind.cardEdit.visibility = View.VISIBLE
            updatePrimaryAccountAction(isLoggedIn = true)
            lifecycleScope.launch {
                runCatching {
                    val profileDeferred = async(Dispatchers.IO) {
                        runCatching { FirestoreService.fetchUserProfile(firebaseUser.uid) }.getOrNull()
                    }
                    val roleDeferred = async(Dispatchers.IO) {
                        runCatching { FirestoreService.fetchUserRole(firebaseUser.uid) }.getOrNull()
                    }
                    val profile = profileDeferred.await()
                    val role = roleDeferred.await()?.ifBlank { profile?.role ?: "client" } ?: profile?.role ?: "client"
                    profile to role
                }.onSuccess { (profile, role) ->
                    latestProfile = profile
                    latestRole = role
                    if (!profile?.name.isNullOrBlank()) {
                        _binding?.tvUserName?.text = profile?.name
                    }
                    _binding?.cardAdmin?.visibility = if (role == "admin") View.VISIBLE else View.GONE
                    _binding?.tvRole?.text = role.uppercase(Locale.getDefault())
                    _binding?.tvAccountHint?.text = buildString {
                        append(firebaseUser.email.orEmpty())
                        if (firebaseUser.email.isNullOrBlank()) return@buildString
                        append(" • ")
                        append(
                            getString(
                                if (firebaseUser.isEmailVerified) R.string.profile_email_verified
                                else R.string.profile_email_unverified
                            )
                        )
                    }.trim()
                    _binding?.tvAccountHint?.visibility = View.VISIBLE
                    val emailStatus = getString(
                        if (firebaseUser.isEmailVerified) R.string.profile_email_verified
                        else R.string.profile_email_unverified
                    )
                    val email = firebaseUser.email.orEmpty()
                    if (email.isBlank()) {
                        _binding?.tvAccountHint?.visibility = View.GONE
                    } else {
                        _binding?.tvAccountHint?.text =
                            getString(R.string.profile_account_hint_format, email, emailStatus)
                    }
                }.onFailure { error ->
                    Log.w(logTag, "Failed to refresh profile info", error)
                    (activity as? AppCompatActivity)?.showMotionSnackbar(getString(R.string.profile_load_failed))
                }
            }
        } else {
            latestProfile = null
            latestRole = "guest"
            bind.tvUserName.text = getString(R.string.user_guest_name)
            bind.tvRole.text = getString(R.string.profile_signup_chip)
            bind.tvAccountHint.text = getString(R.string.profile_guest_hint)
            bind.tvAccountHint.visibility = View.VISIBLE
            bind.cardAdmin.visibility = View.GONE
            bind.cardEdit.visibility = View.GONE
            updatePrimaryAccountAction(isLoggedIn = false)
        }
    }

    private fun updatePrimaryAccountAction(isLoggedIn: Boolean) {
        val button = _binding?.cardLogout ?: return
        val tint = ContextCompat.getColor(
            requireContext(),
            if (isLoggedIn) R.color.profile_logout_text else R.color.white
        )
        button.text = getString(if (isLoggedIn) R.string.auto_text_121 else R.string.auto_text_031)
        button.icon = ContextCompat.getDrawable(
            requireContext(),
            if (isLoggedIn) R.drawable.ic_profile_logout else R.drawable.ic_profile_nav_user
        )
        button.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(
                requireContext(),
                if (isLoggedIn) R.color.profile_logout_bg else R.color.profile_edit_bg
            )
        )
        button.setTextColor(tint)
        button.iconTint = ColorStateList.valueOf(tint)
    }

    private fun showAuthGate(
        returnToTab: MainActivity.Tab = MainActivity.Tab.PROFILE,
        returnRoute: String? = null
    ) {
        (activity as? AppCompatActivity)?.showAuthChoiceDialog(
            onCreateAccount = {
                startActivity(
                    RegisterActivity.createIntent(
                        requireContext(),
                        returnToTab = returnToTab,
                        returnToRoute = returnRoute
                    )
                )
            },
            onExistingClient = {
                startActivity(
                    LoginActivity.createIntent(
                        requireContext(),
                        returnToTab = returnToTab,
                        returnToRoute = returnRoute
                    )
                )
            }
        )
    }

    private fun showEditProfileDialog() {
        val host = activity as? AppCompatActivity ?: return
        val currentUser = FirebaseAuthManager.currentUser ?: run {
            showAuthGate()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_single_input, null)
        val input = view.findViewById<EditText>(R.id.etDialogInput).apply {
            id = R.id.etEditProfileName
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = getString(R.string.profile_edit_hint_name)
            val currentName = _binding?.tvUserName?.text?.toString().orEmpty()
            if (currentName.isNotBlank()) {
                setText(currentName)
                setSelection(currentName.length)
            }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.profile_edit_title))
            .setMessage(currentUser.email ?: "")
            .setView(view)
            .setNegativeButton(getString(R.string.profile_edit_cancel), null)
            .setPositiveButton(getString(R.string.profile_edit_save), null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                val nextName = input.text?.toString().orEmpty().trim()
                if (nextName.length < 3) {
                    host.showMotionSnackbar(getString(R.string.profile_name_invalid))
                    return@setOnClickListener
                }
                lifecycleScope.launch {
                    val result = FirebaseAuthManager.updateDisplayName(nextName)
                    result.fold(
                        onSuccess = {
                            _binding?.tvUserName?.text = nextName
                            lifecycleScope.launch {
                                runCatching { FirestoreService.updateUserProfileName(currentUser.uid, nextName) }
                            }
                            dialog.dismiss()
                            host.showToast(getString(R.string.profile_updated))
                        },
                        onFailure = { error ->
                            host.showMotionSnackbar(FirebaseAuthManager.friendlyError(requireContext(), error))
                        }
                    )
                }
            }
        }
        dialog.show()
    }

    private fun showSupportDialog() {
        val options = arrayOf(
            getString(R.string.support_whatsapp_label),
            getString(R.string.support_email_label)
        )
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.profile_support_title))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> activity?.openWhatsApp(getString(R.string.support_whatsapp_number))
                    1 -> activity?.openEmail(getString(R.string.support_email), "Question FatiWeb")
                }
            }
            .setNegativeButton(getString(R.string.profile_edit_cancel), null)
            .show()
    }

    private fun showAccountCenterDialog() {
        val host = activity as? AppCompatActivity ?: return
        val user = FirebaseAuthManager.currentUser
        if (user == null) {
            showAuthGate()
            return
        }

        val phone = AddressBookStore.getCurrent(requireContext())?.phone
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.profile_account_phone_missing)
        val memberSince = latestProfile?.createdAt?.let(::formatProfileTimestamp)
            ?: getString(R.string.profile_account_member_unknown)
        val message = getString(
            R.string.profile_account_center_message,
            user.email.orEmpty().ifBlank { getString(R.string.profile_account_email_missing) },
            getString(if (user.isEmailVerified) R.string.profile_email_verified else R.string.profile_email_unverified),
            latestRole.uppercase(Locale.getDefault()),
            phone,
            memberSince
        )

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.profile_account_center_title))
            .setMessage(message)
            .setNegativeButton(getString(R.string.profile_edit_cancel), null)
            .setPositiveButton(getString(R.string.profile_account_addresses_action)) { _, _ ->
                host.navigateNoShift(AddressesActivity::class.java)
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener(null)
        }

        dialog.setButton(
            androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL,
            getString(R.string.profile_account_verify_action)
        ) { _, _ -> }
        dialog.show()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
            lifecycleScope.launch {
                val result = FirebaseAuthManager.sendEmailVerification()
                result.fold(
                    onSuccess = {
                        host.showMotionSnackbar(getString(R.string.profile_account_verify_sent))
                        dialog.dismiss()
                    },
                    onFailure = { error ->
                        host.showMotionSnackbar(FirebaseAuthManager.friendlyError(requireContext(), error))
                    }
                )
            }
        }
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)?.visibility =
            if (user.isEmailVerified) View.GONE else View.VISIBLE
    }

    private fun showPrivacyDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.profile_privacy_title))
            .setMessage(getString(R.string.profile_privacy_message))
            .setPositiveButton(getString(R.string.profile_privacy_settings_action)) { _, _ ->
                (activity as? AppCompatActivity)?.navigateNoShift(SettingsActivity::class.java)
            }
            .setNegativeButton(getString(R.string.profile_edit_cancel), null)
            .show()
    }

    private fun formatProfileTimestamp(value: Any): String = when (value) {
        is com.google.firebase.Timestamp -> {
            java.text.SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(value.toDate())
        }
        is Long -> java.text.SimpleDateFormat("MMM yyyy", Locale.getDefault()).format(java.util.Date(value))
        else -> getString(R.string.profile_account_member_unknown)
    }



    private val requestLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.any { it }
        if (granted) {
            refreshProfileLocation(forceResolve = true)
        } else {
            Log.w(logTag, "Location permissions denied")
        }
    }

    private fun refreshProfileLocation(forceResolve: Boolean = false) {
        runCatching {
            val context = context ?: return
            val locationText = _binding?.tvLocation ?: return
            val savedAddress = AddressBookStore.getCurrent(context)?.summaryLine.orEmpty()
            if (savedAddress.isNotBlank()) {
                locationText.text = savedAddress
            }
            preferredHeaderLocation(AddressBookStore.getCurrent(context))?.let { compactLocation ->
                locationText.text = compactLocation
            } ?: run {
                if (savedAddress.isBlank()) {
                    locationText.text = getString(R.string.profile_location_fallback)
                }
            }
            if (!LocationHelper.hasPermission(context)) {
                return
            }

            if (!forceResolve) {
                return
            }

            LocationHelper.resolveCurrentLocation(context) { resolved ->
                activity?.runOnUiThread {
                    locationText.text = resolved
                }
            }
        }.onFailure { error ->
            Log.w(logTag, "Failed to refresh profile location", error)
        }
    }

    private fun preferredHeaderLocation(address: DeliveryAddress?): String? {
        if (address == null) return null
        return listOf(address.city, address.governorate)
            .mapNotNull(::sanitizeHeaderLocationPart)
            .filter { it.isNotBlank() }
            .distinct()
            .take(2)
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
    }

    private fun sanitizeHeaderLocationPart(raw: String?): String? {
        val value = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (value.length < 2) return null

        val letters = value.filter { it.isLetter() }
        if (letters.length < 2) return null

        val lower = letters.lowercase(Locale.getDefault())
        val hasVowel = lower.any { it in "aeiouy" }
        val repeatedSingleChar = lower.toSet().size == 1
        val consonantHeavy = lower.length >= 4 && !hasVowel
        if (repeatedSingleChar || consonantHeavy) return null

        return value
    }
}
