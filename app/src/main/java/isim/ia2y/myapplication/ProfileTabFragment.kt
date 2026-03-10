package isim.ia2y.myapplication

import android.Manifest
import android.content.Context
import android.graphics.BitmapFactory
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale

class ProfileTabFragment : Fragment(R.layout.fragment_profile_tab) {
    private var pendingLocationListener: LocationListener? = null
    private val avatarPrefsName = "profile_prefs"
    private val avatarUriKey = "avatar_uri"
    private val logTag = "ProfileTabFragment"
    private val mainHandler = Handler(Looper.getMainLooper())

    private val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val savedPath = copyUriToInternalStorage(uri)
        if (savedPath != null) {
            saveAvatarPath(savedPath)
            loadAvatarFromPath(savedPath)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        runCatching {
            view.findViewById<View?>(R.id.layoutBottomNav)?.isGone = true
            view.findViewById<View?>(R.id.viewBottomDivider)?.isGone = true
            view.findViewById<View?>(R.id.layoutTopBar)?.isGone = true
            view.findViewById<View?>(R.id.viewTopDivider)?.isGone = true
            setupProfileActions(view)
            view.findViewById<TextView>(R.id.tvUserName)?.text = getString(R.string.user_guest_name)
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

    override fun onPause() {
        val context = context
        pendingLocationListener?.let { listener ->
            val manager = context?.getSystemService(LocationManager::class.java)
            runCatching { manager?.removeUpdates(listener) }
        }
        pendingLocationListener = null
        super.onPause()
    }

    private fun setupProfileActions(root: View) {
        root.findViewById<View>(R.id.ivBack)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.HOME, animate = false)
        }
        root.findViewById<View>(R.id.cardAdmin)?.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateNoShift(AdminDashboardActivity::class.java)
        }
        (activity as? AppCompatActivity)?.bindNotificationEntry(R.id.ivNotifications)
        root.findViewById<TextView>(R.id.tvRole)?.text = getString(R.string.profile_signup_chip)
        root.findViewById<View>(R.id.cardAvatar)?.setOnClickListener { openAvatarPicker() }
        root.findViewById<View>(R.id.cardEdit)?.setOnClickListener { openAvatarPicker() }
        root.findViewById<View>(R.id.cardRole)?.setOnClickListener {
            (activity as? AppCompatActivity)?.showAuthChoiceDialog(
                onCreateAccount = { (activity as? AppCompatActivity)?.navigateNoShift(RegisterActivity::class.java) },
                onExistingClient = { (activity as? AppCompatActivity)?.navigateNoShift(LoginActivity::class.java) }
            )
        }
        root.findViewById<View>(R.id.tvRole)?.setOnClickListener {
            root.findViewById<View>(R.id.cardRole)?.performClick()
        }
        root.findViewById<View>(R.id.cardSettings)?.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateNoShift(SettingsActivity::class.java)
        }
        root.findViewById<View>(R.id.cardOrders)?.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateNoShift(OrdersHistoryActivity::class.java)
        }
        root.findViewById<View>(R.id.cardAddresses)?.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateNoShift(AddressesActivity::class.java)
        }
        (activity as? AppCompatActivity)?.bindComingSoon(R.id.cardHelp)
        root.findViewById<View>(R.id.cardLogout)?.setOnClickListener {
            FirebaseAuthManager.signOut()
            (activity as? AppCompatActivity)?.navigateNoShift(LoginActivity::class.java)
        }
        (activity as? AppCompatActivity)?.applyPressFeedback(
            R.id.ivBack,
            R.id.ivNotifications,
            R.id.cardRole,
            R.id.cardAvatar,
            R.id.cardEdit,
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
        val imageView = view?.findViewById<ImageView>(R.id.ivAvatar) ?: return
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
        val context = context ?: return
        val saved = context.getSharedPreferences(avatarPrefsName, Context.MODE_PRIVATE)
            .getString(avatarUriKey, null)
            ?: return

        // Support both legacy file:// URIs and new raw file paths
        val filePath = if (saved.startsWith("file://")) {
            Uri.parse(saved).path ?: return
        } else {
            saved
        }

        val file = java.io.File(filePath)
        if (!file.exists()) {
            Log.w(logTag, "Avatar file not found, clearing saved path")
            context.getSharedPreferences(avatarPrefsName, Context.MODE_PRIVATE)
                .edit().remove(avatarUriKey).apply()
            return
        }

        loadAvatarFromPath(filePath)
    }

    /** Refresh the displayed user name/email from Firebase Auth. */
    private fun refreshUserInfo() {
        val root = view ?: return
        val firebaseUser = FirebaseAuthManager.currentUser
        if (firebaseUser != null) {
            root.findViewById<TextView>(R.id.tvUserName)?.text =
                firebaseUser.displayName?.ifEmpty { null } ?: getString(R.string.user_guest_name)
            // If Firestore has a better display name (e.g. set during register), prefer it
            lifecycleScope.launch {
                val fsName = FirestoreService.fetchUserName(firebaseUser.uid)
                if (!fsName.isNullOrBlank()) {
                    root.findViewById<TextView>(R.id.tvUserName)?.text = fsName
                }
            }
            root.findViewById<TextView>(R.id.tvRole)?.text = firebaseUser.email ?: getString(R.string.profile_signup_chip)
        } else {
            root.findViewById<TextView>(R.id.tvUserName)?.text = getString(R.string.user_guest_name)
            root.findViewById<TextView>(R.id.tvRole)?.text = getString(R.string.profile_signup_chip)
        }
    }

    private val requestLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            refreshProfileLocation()
        } else {
            Log.w(logTag, "Location permissions denied")
        }
    }

    private fun refreshProfileLocation() {
        runCatching {
            val context = context ?: return
            val view = view ?: return
            val locationText = view.findViewById<TextView>(R.id.tvLocation) ?: return
            
            if (!LocationHelper.hasPermission(context)) {
                requestLocationLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
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
