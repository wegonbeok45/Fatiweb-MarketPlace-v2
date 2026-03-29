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
import kotlinx.coroutines.launch
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

    private val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        
        val uid = FirebaseAuthManager.currentUser?.uid ?: return@registerForActivityResult
        
        lifecycleScope.launch {
            val progress = (activity as? AppCompatActivity)?.showMotionSnackbar(getString(R.string.profile_avatar_uploading))
            runCatching { UserAvatarStorage.uploadAvatar(requireContext(), uid, uri) }
                .onSuccess { remoteUrl ->
                    FirestoreService.updateUserAvatarUrl(uid, remoteUrl)
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
                R.id.cardSettings,
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
        binding.cardOrders.setOnClickListener {
            if (FirebaseAuthManager.isLoggedIn) {
                (activity as? AppCompatActivity)?.navigateNoShift(OrdersHistoryActivity::class.java)
            } else {
                showAuthGate()
            }
        }
        binding.cardAddresses.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateNoShift(AddressesActivity::class.java)
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
            R.id.cardSettings,
            R.id.cardHelp,
            R.id.cardLogout
        )
    }

    private fun openAvatarPicker() {
        pickAvatarLauncher.launch("image/*")
    }

    /**
     * Copies the picked image into internal app storage and returns the absolute file path.
     * Storing a raw file path (not a file:// URI) avoids FileUriExposedException on Android 7+.
     */
    private fun copyUriToInternalStorage(uri: Uri): String? {
        return runCatching {
            val context = requireContext()
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val file = java.io.File(context.filesDir, "user_avatar.jpg")
            java.io.FileOutputStream(file).use { output ->
                inputStream.use { input -> input.copyTo(output) }
            }
            file.absolutePath
        }.getOrNull()
    }

    private fun saveAvatarPath(path: String) {
        requireContext().getSharedPreferences(avatarPrefsName, Context.MODE_PRIVATE)
            .edit()
            .putString(avatarUriKey, path)
            .apply()
    }

    private fun loadAvatarFromPath(path: String) {
        val imageView = _binding?.ivAvatar ?: return
        
        if (path.startsWith("http")) {
            imageView.loadCatalogImage(path, null)
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
                val profile = FirestoreService.fetchUserProfile(uid)
                if (profile?.avatarUrl != null) {
                    loadAvatarFromPath(profile.avatarUrl)
                    return@launch
                }
                
                // Local fallback
                val context = context ?: return@launch
                val saved = context.getSharedPreferences(avatarPrefsName, Context.MODE_PRIVATE)
                    .getString(avatarUriKey, null)
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
            updatePrimaryAccountAction(isLoggedIn = true)
            lifecycleScope.launch {
                runCatching {
                    val profile = FirestoreService.fetchUserProfile(firebaseUser.uid)
                    val role = profile?.role ?: FirestoreService.fetchUserRole(firebaseUser.uid) ?: "client"
                    profile to role
                }.onSuccess { (profile, role) ->
                    if (!profile?.name.isNullOrBlank()) {
                        _binding?.tvUserName?.text = profile?.name
                    }
                    if (role == "admin") {
                        _binding?.cardAdmin?.visibility = View.VISIBLE
                    }
                    _binding?.tvRole?.text = role.uppercase(Locale.getDefault())
                }.onFailure { error ->
                    Log.w(logTag, "Failed to refresh profile info", error)
                    (activity as? AppCompatActivity)?.showMotionSnackbar(getString(R.string.profile_load_failed))
                }
            }
        } else {
            bind.tvUserName.text = getString(R.string.user_guest_name)
            bind.tvRole.text = getString(R.string.profile_signup_chip)
            bind.cardAdmin.visibility = View.GONE
            updatePrimaryAccountAction(isLoggedIn = false)
        }
    }

    private fun updatePrimaryAccountAction(isLoggedIn: Boolean) {
        val button = _binding?.cardLogout ?: return
        val tint = ContextCompat.getColor(
            requireContext(),
            if (isLoggedIn) R.color.colorError else R.color.colorPrimary
        )
        button.text = getString(if (isLoggedIn) R.string.auto_text_121 else R.string.auto_text_031)
        button.icon = ContextCompat.getDrawable(
            requireContext(),
            if (isLoggedIn) R.drawable.ic_profile_logout else R.drawable.ic_profile_nav_user
        )
        button.setTextColor(tint)
        button.iconTint = ColorStateList.valueOf(tint)
    }

    private fun showAuthGate() {
        (activity as? AppCompatActivity)?.showAuthChoiceDialog(
            onCreateAccount = { (activity as? AppCompatActivity)?.navigateNoShift(RegisterActivity::class.java) },
            onExistingClient = { (activity as? AppCompatActivity)?.navigateNoShift(LoginActivity::class.java) }
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
                            host.showMotionSnackbar(FirebaseAuthManager.friendlyError(error))
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
            val view = view ?: return
            val locationText = _binding?.tvLocation ?: return
            val savedAddress = AddressBookStore.getCurrent(context)?.summaryLine.orEmpty()
            if (savedAddress.isNotBlank()) {
                locationText.text = savedAddress
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
}
