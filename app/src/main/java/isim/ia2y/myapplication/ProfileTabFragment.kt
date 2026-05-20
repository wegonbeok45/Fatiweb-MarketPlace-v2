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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isGone
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import isim.ia2y.myapplication.databinding.FragmentProfileTabBinding
import android.view.LayoutInflater
import android.view.ViewGroup

class ProfileTabFragment : Fragment() {

    private var _binding: FragmentProfileTabBinding? = null
    private val binding get() = _binding!!
    private lateinit var profileViewModel: ProfileViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        profileViewModel = ViewModelProvider(this)[ProfileViewModel::class.java]
        _binding = FragmentProfileTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private val logTag = "ProfileTabFragment"
    private val mainHandler = Handler(Looper.getMainLooper())
    private var latestProfile: FirestoreService.UserProfile? = null
    private var latestRole: String = "client"

    private val pickAvatarLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        
        val uid = FirebaseAuthManager.currentUser?.uid ?: return@registerForActivityResult
        val appContext = context?.applicationContext ?: return@registerForActivityResult
        
        lifecycleScope.launch {
            runCatching { UserAvatarService.uploadAndSaveAvatar(appContext, uid, uri) }
                .onSuccess { remoteUrl ->
                    if (!isAdded) return@onSuccess
                    latestProfile = latestProfile?.copy(avatarUrl = remoteUrl)
                    loadAvatarUrl(remoteUrl)
                    (activity as? AppCompatActivity)?.showSuccess(getString(R.string.profile_avatar_updated))
                }
                .onFailure { error ->
                    Log.e(logTag, "Avatar upload failed", error)
                    (activity as? AppCompatActivity)?.showMotionSnackbar(getString(R.string.profile_avatar_update_failed))
                }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        runCatching {
            binding.layoutBottomNav.isGone = true
            binding.viewBottomDivider.isGone = true
            setupProfileActions()
            binding.tvUserName.text = getString(R.string.user_guest_name)
            updatePrimaryAccountAction(FirebaseAuthManager.isLoggedIn)
            observeProfileInfo()
            restoreAvatar()
            refreshProfileLocation()
            renderRecentlyViewedProducts()
            (activity as? AppCompatActivity)?.revealViewsInOrder(
                R.id.layoutTopBar,
                R.id.layoutHeader,
                R.id.cardOrders,
                R.id.cardAccountCenter,
                R.id.cardNotifications,
                R.id.cardAddresses,
                R.id.cardSettings,
                R.id.cardHelp,
                R.id.cardAbout,
                R.id.cardLogout
            )
        }.onFailure { error ->
            Log.e(logTag, "Failed to initialize profile tab", error)
            (activity as? AppCompatActivity)?.showToast(getString(R.string.profile_load_failed))
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.HOME, animate = false)
        }
    }

    override fun onResume() {
        super.onResume()
        (activity as? MainActivity)?.updateHostCartBadge()
        refreshProfileLocation()
        refreshUserInfo()
        renderRecentlyViewedProducts()
    }



    private fun observeProfileInfo() {
        profileViewModel.userInfo.observe(viewLifecycleOwner) { info ->
            val firebaseUser = FirebaseAuthManager.currentUser ?: return@observe
            val profile = info?.profile
            val role = normalizeRole(info?.role ?: profile?.role)
            latestProfile = profile
            latestRole = role
            if (role == UserRoles.ADMIN) {
                AdminSession.markVerified(firebaseUser.uid)
            }
            if (!profile?.name.isNullOrBlank()) {
                _binding?.tvUserName?.text = profile?.name
            }
            if (!profile?.avatarUrl.isNullOrBlank()) {
                loadAvatarUrl(profile?.avatarUrl)
            } else {
                loadAvatarUrl(null)
            }
            updateAdminDashboardEntry(firebaseUser.uid, role)
            _binding?.tvRole?.text = displayRoleLabel(role)
            val email = firebaseUser.email.orEmpty()
            if (email.isBlank()) {
                _binding?.tvAccountHint?.visibility = View.GONE
            } else {
                val emailStatus = getString(
                    if (firebaseUser.isEmailVerified) R.string.profile_email_verified
                    else R.string.profile_email_unverified
                )
                _binding?.tvAccountHint?.text =
                    getString(R.string.profile_account_hint_format, email, emailStatus)
                _binding?.tvAccountHint?.visibility = View.VISIBLE
            }
        }

        profileViewModel.loadError.observe(viewLifecycleOwner) { error ->
            error ?: return@observe
            Log.w(logTag, "Failed to refresh profile info", error)
            (activity as? AppCompatActivity)?.showMotionSnackbar(getString(R.string.profile_load_failed))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UserService.roleFlow.collect { entry ->
                    val firebaseUser = FirebaseAuthManager.currentUser ?: return@collect
                    if (entry == null || entry.first != firebaseUser.uid) return@collect
                    val normalized = normalizeRole(entry.second)
                    if (latestRole != normalized) {
                        latestRole = normalized
                        _binding?.tvRole?.text = displayRoleLabel(normalized)
                    }
                    updateAdminDashboardEntry(firebaseUser.uid, normalized)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                UserService.avatarUrlFlow.collect { entry ->
                    val firebaseUser = FirebaseAuthManager.currentUser ?: return@collect
                    if (entry == null || entry.first != firebaseUser.uid) return@collect
                    loadAvatarUrl(entry.second)
                    latestProfile = latestProfile?.copy(avatarUrl = entry.second)
                }
            }
        }
    }

    private fun setupProfileActions() {
        val root = binding.root
        binding.tvTopBrand.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.HOME, animate = false)
        }
        binding.ivBack.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.HOME, animate = false)
        }
        val cardAdmin = binding.cardAdmin
        cardAdmin.visibility = View.GONE
        cardAdmin.setOnClickListener {
            val destination = if (latestRole == UserRoles.VENDEUR) {
                SellerDashboardActivity::class.java
            } else {
                AdminDashboardActivity::class.java
            }
            (activity as? AppCompatActivity)?.navigateNoShift(destination)
        }
        binding.ivNotifications.setOnClickListener {
            (activity as? AppCompatActivity)?.openNotificationsScreenWithPermissionCheck()
        }
        updateProfileEditActions(FirebaseAuthManager.isLoggedIn)
        binding.tvRole.text = displayRoleLabel("guest")
        binding.cardAvatar.setOnClickListener { openAvatarPicker() }
        binding.cardEdit.setOnClickListener { openAvatarPicker() }
        binding.btnEditProfileHeader.setOnClickListener {
            if (FirebaseAuthManager.isLoggedIn) {
                showEditProfileDialog()
            } else {
                showAuthGate()
            }
        }
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
        root.findViewById<View>(R.id.cardSettings)?.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateNoShift(SettingsActivity::class.java)
        }
        root.findViewById<View>(R.id.cardAccountCenter)?.setOnClickListener {
            if (FirebaseAuthManager.isLoggedIn) {
                (activity as? AppCompatActivity)?.navigateNoShift(PersonalDetailsActivity::class.java)
            } else {
                showAuthGate(returnToTab = MainActivity.Tab.PROFILE)
            }
        }
        binding.cardOrders.setOnClickListener {
            if (FirebaseAuthManager.isLoggedIn) {
                (activity as? AppCompatActivity)?.navigateNoShift(OrdersHistoryActivity::class.java)
            } else {
                showAuthGate(returnRoute = AUTH_RETURN_ROUTE_ORDERS)
            }
        }
        binding.cardWishlistQuick.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateNoShift(FavoritesActivity::class.java)
        }
        binding.cardQuickAddresses.setOnClickListener {
            if (FirebaseAuthManager.isLoggedIn) {
                (activity as? AppCompatActivity)?.navigateNoShift(AddressesActivity::class.java)
            } else {
                showAuthGate(returnToTab = MainActivity.Tab.PROFILE)
            }
        }
        binding.cardQuickSupport.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateNoShift(HelpCenterActivity::class.java)
        }
        root.findViewById<View>(R.id.cardAddresses)?.setOnClickListener {
            if (FirebaseAuthManager.isLoggedIn) {
                (activity as? AppCompatActivity)?.navigateNoShift(AddressesActivity::class.java)
            } else {
                showAuthGate(returnToTab = MainActivity.Tab.PROFILE)
            }
        }
        root.findViewById<View>(R.id.cardNotifications)?.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateNoShift(NotificationPreferencesActivity::class.java)
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
        root.findViewById<View>(R.id.cardHelp)?.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateNoShift(HelpCenterActivity::class.java)
        }
        root.findViewById<View>(R.id.cardAbout)?.setOnClickListener {
            (activity as? AppCompatActivity)?.navigateNoShift(AboutCuratorActivity::class.java)
        }
        root.findViewById<View>(R.id.profileRecentSeeAll)?.setOnClickListener {
            (activity as? MainActivity)?.selectTab(MainActivity.Tab.EXPLORE)
        }
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
            R.id.tvTopBrand,
            R.id.ivNotifications,
            R.id.tvRole,
            R.id.tvLocation,
            R.id.cardAvatar,
            R.id.cardEdit,
            R.id.btnEditProfileHeader,
            R.id.tvUserName,
            R.id.cardAdmin,
            R.id.cardOrders,
            R.id.cardWishlistQuick,
            R.id.cardQuickAddresses,
            R.id.cardQuickSupport,
            R.id.cardAddresses,
            R.id.cardAccountCenter,
            R.id.cardNotifications,
            R.id.cardSettings,
            R.id.cardHelp,
            R.id.cardAbout,
            R.id.profileRecentSeeAll,
            R.id.cardLogout
        )
    }

    private fun renderRecentlyViewedProducts() {
        val bind = _binding ?: return
        val context = context ?: return
        val list = bind.root.findViewById<LinearLayout>(R.id.profileRecentlyViewedList) ?: return
        val scroll = bind.root.findViewById<View>(R.id.profileRecentlyViewedScroll)
        val empty = bind.root.findViewById<TextView>(R.id.profileRecentlyViewedEmpty)
        val seeAll = bind.root.findViewById<View>(R.id.profileRecentSeeAll)
        val header = bind.root.findViewById<View>(R.id.layoutProfileRecentlyViewedHeader)
        val products = RecentlyViewedStore.getProducts(context, limit = 12)

        list.removeAllViews()
        if (products.isEmpty()) {
            scroll?.visibility = View.GONE
            empty?.visibility = View.GONE
            seeAll?.visibility = View.GONE
            header?.visibility = View.GONE
            return
        }

        empty?.visibility = View.GONE
        scroll?.visibility = View.VISIBLE
        seeAll?.visibility = View.VISIBLE
        header?.visibility = View.VISIBLE
        products.forEachIndexed { index, product ->
            list.addView(buildRecentlyViewedCard(product, index == products.lastIndex))
        }
    }

    private fun buildRecentlyViewedCard(product: Product, isLast: Boolean): View {
        val context = requireContext()
        val cardWidth = resources.getDimensionPixelSize(R.dimen.home_product_carousel_card_width)
        val spacing = resources.getDimensionPixelSize(R.dimen.space_12)
        val card = layoutInflater.inflate(R.layout.item_home_catalog_product, null, false).apply {
            layoutParams = LinearLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = if (isLast) 0 else spacing
            }
            findViewById<ImageView>(R.id.homeDynamicProductImage)
                ?.loadCatalogImage(product.previewImageUrl(), product.catalogFallbackImageRes(), requestedSizePx = 640)
            findViewById<TextView>(R.id.homeDynamicProductTitle)?.text = product.title
            findViewById<TextView>(R.id.homeDynamicProductPrice)?.text = formatDt(product.unitPrice)
            findViewById<TextView>(R.id.homeDynamicProductOrigin)?.text = product.sellerDisplayName
            findViewById<TextView>(R.id.homeDynamicProductRating)?.text = productCardRatingText(product)
            findViewById<TextView>(R.id.homeDynamicProductCategory)?.text = productCardCategoryLabel(product)
            findViewById<ImageView>(R.id.homeDynamicFavoriteButton)?.apply {
                val isFavorite = FavoritesStore.isFavorite(context, product.id)
                setImageResource(if (isFavorite) R.drawable.ic_home_heart_filled else R.drawable.ic_home_heart)
                setColorFilter(
                    ContextCompat.getColor(
                        context,
                        if (isFavorite) R.color.home_heart_active else R.color.home_ref_text_primary
                    )
                )
                setOnClickListener {
                    FavoritesStore.toggleFavorite(context, product.id)
                    renderRecentlyViewedProducts()
                }
            }
            setOnClickListener {
                (activity as? AppCompatActivity)?.navigateToProductDetails(product.id)
            }
        }
        return card
    }

    private fun openAvatarPicker() {
        if (!FirebaseAuthManager.isLoggedIn) {
            showAuthGate(returnToTab = MainActivity.Tab.PROFILE)
            return
        }
        pickAvatarLauncher.launch("image/*")
    }

    private fun loadAvatarUrl(url: String?) {
        val imageView = _binding?.ivAvatar ?: return
        if (!url.isNullOrBlank()) {
            imageView.loadAvatarImage(url)
            return
        }
        imageView.setImageResource(R.drawable.profile_avatar_art)
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
                    loadAvatarUrl(profile.avatarUrl)
                    return@launch
                }
                loadAvatarUrl(null)
            }
        } else {
            loadAvatarUrl(null)
        }
    }

    /** Refresh the displayed user name/email from Firebase Auth. */
    private fun refreshUserInfo() {
        val bind = _binding ?: return
        val firebaseUser = FirebaseAuthManager.currentUser
        if (firebaseUser != null) {
            val cachedRole = UserService.cachedRole(firebaseUser.uid)
            if (cachedRole != null) {
                latestRole = normalizeRole(cachedRole)
                updateAdminDashboardEntry(firebaseUser.uid, latestRole)
                bind.tvRole.text = displayRoleLabel(latestRole)
            } else {
                updateAdminDashboardEntry(firebaseUser.uid, latestRole)
            }
            bind.tvUserName.text =
                firebaseUser.displayName?.ifEmpty { null } ?: getString(R.string.user_guest_name)
            bind.tvAccountHint.text = firebaseUser.email.orEmpty()
            bind.tvAccountHint.visibility = if (firebaseUser.email.isNullOrBlank()) View.GONE else View.VISIBLE
            updateProfileEditActions(isLoggedIn = true)
            updatePrimaryAccountAction(isLoggedIn = true)
            profileViewModel.loadUserInfo(firebaseUser.uid, forceRefresh = cachedRole == null)
            return
            /*
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
            */
        } else {
            profileViewModel.clear()
            latestProfile = null
            latestRole = "guest"
            bind.tvUserName.text = getString(R.string.user_guest_name)
            bind.tvRole.text = displayRoleLabel("guest")
            bind.tvAccountHint.text = getString(R.string.profile_guest_hint)
            bind.tvAccountHint.visibility = View.VISIBLE
            resetAdminCardState(bind.cardAdmin)
            bind.cardAdmin.visibility = View.GONE
            loadAvatarUrl(null)
            updateProfileEditActions(isLoggedIn = false)
            updatePrimaryAccountAction(isLoggedIn = false)
        }
    }

    private fun updateProfileEditActions(isLoggedIn: Boolean) {
        val visibility = if (isLoggedIn) View.VISIBLE else View.GONE
        _binding?.cardEdit?.visibility = visibility
        _binding?.btnEditProfileHeader?.visibility = visibility
    }

    private fun updateAdminDashboardEntry(uid: String, role: String?) {
        val button = _binding?.cardAdmin ?: return
        val normalizedRole = normalizeRole(role)
        val shouldShow = normalizedRole == UserRoles.ADMIN || normalizedRole == UserRoles.VENDEUR || AdminSession.isVerified(uid)
        button.animate().cancel()
        button.visibility = if (shouldShow) View.VISIBLE else View.GONE
        button.text = getString(
            if (normalizedRole == UserRoles.VENDEUR) R.string.profile_seller_dashboard_action
            else R.string.profile_admin_dashboard_action
        )
        resetAdminCardState(button)
    }

    private fun resetAdminCardState(button: View) {
        button.animate().cancel()
        button.alpha = 1f
        button.translationX = 0f
        button.translationY = 0f
        button.scaleX = 1f
        button.scaleY = 1f
    }

    private fun normalizeRole(role: String?): String {
        return when (role?.trim()?.lowercase(Locale.getDefault())) {
            UserRoles.ADMIN -> UserRoles.ADMIN
            UserRoles.VENDEUR, "seller", "vendor" -> UserRoles.VENDEUR
            UserRoles.CLIENT, "buyer", "customer" -> UserRoles.CLIENT
            "guest", null, "" -> "guest"
            else -> UserRoles.CLIENT
        }
    }

    private fun displayRoleLabel(role: String): String {
        return getString(
            when (normalizeRole(role)) {
                UserRoles.ADMIN -> R.string.profile_role_admin
                UserRoles.VENDEUR -> R.string.profile_role_vendeur
                UserRoles.CLIENT -> R.string.profile_role_client
                else -> R.string.profile_role_guest
            }
        )
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
