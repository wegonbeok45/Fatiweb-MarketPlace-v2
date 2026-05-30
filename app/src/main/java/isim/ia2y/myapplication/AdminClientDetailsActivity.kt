package isim.ia2y.myapplication

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import isim.ia2y.myapplication.ui.base.MsStatusPill
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdminClientDetailsActivity : AppCompatActivity() {
    private val ordersAdapter = ClientOrderHistoryAdapter()
    private var clientId: String = ""
    private var latestDetails: AdminService.ClientProfileDetails? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_client_details_v2)
        clientId = intent.getStringExtra(EXTRA_CLIENT_ID).orEmpty()
        setupInsets()
        setupTopBar()
        setupOrdersList()
        loadDetails()
    }

    private fun setupInsets() {
        val baseTopBarHeight = resources.getDimensionPixelSize(R.dimen.ms_top_bar_height)
        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.adminClientDetailsTopBar)
        ) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            v.updateLayoutParams<ViewGroup.LayoutParams> { height = baseTopBarHeight + top }
            insets
        }
        val scroll = findViewById<View>(R.id.adminClientDetailsScroll)
        val baseBottom = scroll?.paddingBottom ?: 0
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            v.updatePadding(bottom = baseBottom + bottom)
            insets
        }
    }

    private fun setupTopBar() {
        bindAdminBack(AdminNavTab.CLIENTS)
        findViewById<View>(R.id.adminClientDetailsIvBack)?.setOnClickListener {
            navigateAdminBack(AdminNavTab.CLIENTS)
        }
    }

    private fun setupOrdersList() {
        findViewById<RecyclerView>(R.id.adminClientDetailsOrdersList)?.apply {
            layoutManager = LinearLayoutManager(this@AdminClientDetailsActivity)
            adapter = ordersAdapter
            isNestedScrollingEnabled = false
        }
    }

    private fun loadDetails() {
        if (clientId.isBlank()) {
            showMotionSnackbar(getString(R.string.admin_client_details_load_error))
            finish()
            return
        }

        lifecycleScope.launch {
            if (!requireAdminRole()) return@launch
            setLoading(true)
            val result = runCatching {
                coroutineScope {
                    val profile = async { AdminService.fetchClientProfileDetails(clientId) }
                    val addresses = async { UserService.fetchAddresses(clientId) }
                    val orders = async { AdminService.fetchClientOrders(clientId) }
                    ClientDetailsBundle(
                        profile = profile.await(),
                        addresses = addresses.await(),
                        orders = orders.await()
                    )
                }
            }
            setLoading(false)
            result
                .onSuccess(::renderDetails)
                .onFailure {
                    showMotionSnackbar(getString(R.string.admin_client_details_load_error))
                    findViewById<TextView>(R.id.adminClientDetailsOrdersEmpty)?.apply {
                        visibility = View.VISIBLE
                        text = getString(R.string.admin_client_details_load_error)
                    }
                }
        }
    }

    private fun renderDetails(bundle: ClientDetailsBundle) {
        val profile = bundle.profile ?: run {
            showMotionSnackbar(getString(R.string.admin_client_details_missing))
            finish()
            return
        }
        latestDetails = profile

        val displayName = IdentityResolver.displayName(
            name = profile.name,
            email = profile.email,
            fallback = getString(R.string.admin_client_fallback_name)
        )
        val joined = formatDate(profile.createdAt)
        val address = resolveAddress(bundle.addresses, bundle.orders)
        val totalOrders = maxOf(profile.orderCount, bundle.orders.size)

        findViewById<ImageView>(R.id.adminClientDetailsAvatar)?.loadAvatarImage(profile.avatarUrl, 220)
        findViewById<TextView>(R.id.adminClientDetailsName)?.text = displayName
        findViewById<TextView>(R.id.adminClientDetailsEmail)?.text =
            profile.email.ifBlank { getString(R.string.admin_client_fallback_email) }
        // Role pill
        val roleTv = findViewById<TextView>(R.id.adminClientDetailsRole)
        val (roleKind, roleRes) = when (profile.role) {
            UserRoles.ADMIN  -> MsStatusPill.Kind.Info    to R.string.profile_role_admin
            UserRoles.VENDEUR -> MsStatusPill.Kind.Approved to R.string.profile_role_vendeur
            else             -> MsStatusPill.Kind.Pending  to R.string.profile_role_client
        }
        MsStatusPill.bind(roleTv, roleKind, roleRes)

        // Status pill
        val statusTv = findViewById<TextView>(R.id.adminClientDetailsStatus)
        val (statusKind, statusRes) = when (profile.status.lowercase(Locale.getDefault())) {
            "active"                        -> MsStatusPill.Kind.Approved to R.string.admin_client_status_active
            "disabled", "blocked", "suspended" -> MsStatusPill.Kind.Rejected to R.string.admin_client_status_blocked
            else                            -> MsStatusPill.Kind.Pending  to R.string.admin_client_status_active
        }
        MsStatusPill.bind(statusTv, statusKind, statusRes)
        findViewById<TextView>(R.id.adminClientDetailsJoined)?.text = joined
        findViewById<TextView>(R.id.adminClientDetailsTotalOrders)?.text = totalOrders.toString()
        findViewById<TextView>(R.id.adminClientDetailsPhone)?.text =
            profile.phone.ifBlank { getString(R.string.admin_client_fallback_phone) }
        findViewById<TextView>(R.id.adminClientDetailsAddress)?.text =
            address.ifBlank { getString(R.string.admin_client_details_no_address) }

        findViewById<MaterialButton>(R.id.adminClientDetailsContact)?.setOnClickListener {
            openEmail(profile.email, "FatiWeb - $displayName")
        }
        findViewById<MaterialButton>(R.id.adminClientDetailsPromote)?.apply {
            visibility = if (profile.role == UserRoles.CLIENT) View.VISIBLE else View.GONE
            isEnabled = true
            text = getString(R.string.admin_client_make_vendeur)
            setOnClickListener { promoteClient() }
        }
        findViewById<MaterialButton>(R.id.adminClientDetailsRevoke)?.apply {
            visibility = if (profile.role == UserRoles.VENDEUR) View.VISIBLE else View.GONE
            isEnabled = true
            text = getString(R.string.admin_client_revoke_vendeur)
            setOnClickListener { confirmRevoke() }
        }

        ordersAdapter.submitList(bundle.orders)
        findViewById<TextView>(R.id.adminClientDetailsOrdersEmpty)?.visibility =
            if (bundle.orders.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun promoteClient() {
        val details = latestDetails ?: return
        val button = findViewById<MaterialButton>(R.id.adminClientDetailsPromote)
        button?.isEnabled = false
        button?.text = getString(R.string.admin_client_promoting)
        lifecycleScope.launch {
            runCatching { AdminService.promoteUserToVendeur(details.uid) }
                .onSuccess {
                    showMotionSnackbar(getString(R.string.admin_client_promote_success))
                    loadDetails()
                }
                .onFailure {
                    showMotionSnackbar(getString(R.string.admin_client_promote_failed))
                    button?.isEnabled = true
                    button?.text = getString(R.string.admin_client_make_vendeur)
                }
        }
    }

    private fun confirmRevoke() {
        val details = latestDetails ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.admin_client_revoke_confirm_title)
            .setMessage(R.string.admin_client_revoke_confirm_message)
            .setPositiveButton(R.string.admin_client_revoke_cta) { _, _ -> revokeClient(details.uid) }
            .setNegativeButton(R.string.admin_dialog_cancel, null)
            .show()
    }

    private fun revokeClient(userId: String) {
        val button = findViewById<MaterialButton>(R.id.adminClientDetailsRevoke)
        button?.isEnabled = false
        button?.text = getString(R.string.admin_client_revoking)
        lifecycleScope.launch {
            runCatching { AdminService.revokeVendeurRole(userId) }
                .onSuccess {
                    showMotionSnackbar(getString(R.string.admin_client_revoke_success))
                    loadDetails()
                }
                .onFailure {
                    showMotionSnackbar(getString(R.string.admin_client_revoke_failed))
                    button?.isEnabled = true
                    button?.text = getString(R.string.admin_client_revoke_vendeur)
                }
        }
    }

    private fun setLoading(loading: Boolean) {
        findViewById<ProgressBar>(R.id.adminClientDetailsProgress)?.visibility =
            if (loading) View.VISIBLE else View.GONE
    }

    private fun resolveAddress(addresses: List<DeliveryAddress>, orders: List<AppOrder>): String {
        val savedAddress = addresses.firstOrNull { it.isDefault } ?: addresses.firstOrNull()
        if (savedAddress != null) {
            return listOf(savedAddress.summaryLine, savedAddress.detailsLine)
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }
        return orders.firstNotNullOfOrNull { order ->
            order.shippingAddress?.let { address ->
                listOf(address.summaryLine, address.detailsLine)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                    .takeIf { it.isNotBlank() }
            }
        }.orEmpty()
    }

    private fun formatDate(millis: Long): String {
        if (millis <= 0L) return getString(R.string.admin_client_unknown_date)
        return SimpleDateFormat("dd MMM yyyy", Locale.FRANCE).format(Date(millis))
    }

    private data class ClientDetailsBundle(
        val profile: AdminService.ClientProfileDetails?,
        val addresses: List<DeliveryAddress>,
        val orders: List<AppOrder>
    )

    private inner class ClientOrderHistoryAdapter :
        ListAdapter<AppOrder, ClientOrderHistoryAdapter.ViewHolder>(DiffCallback) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_admin_client_order_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            fun bind(order: AppOrder) {
                val firstItem = order.items.firstOrNull()
                itemView.findViewById<ImageView>(R.id.adminClientOrderImage)
                    ?.loadCatalogImage(firstItem?.thumbnailUrl, R.drawable.placeholder, 180)
                itemView.findViewById<TextView>(R.id.adminClientOrderTitle)?.text =
                    "${order.displayId} - ${order.statusLabel(itemView.context)}"
                itemView.findViewById<TextView>(R.id.adminClientOrderMeta)?.text =
                    "${formatDate(order.createdAtMillis)} - ${order.items.sumOf { it.quantity }} articles"
                itemView.findViewById<TextView>(R.id.adminClientOrderProducts)?.text =
                    order.items.take(4).joinToString("\n") { item ->
                        "${item.quantity}x ${item.name.ifBlank { item.productId }} - ${formatDt(item.priceAtPurchase)}"
                    }.ifBlank { getString(R.string.admin_client_details_no_products) }
                itemView.findViewById<TextView>(R.id.adminClientOrderTotal)?.text = formatDt(order.total)
            }
        }
    }

    private object DiffCallback : DiffUtil.ItemCallback<AppOrder>() {
        override fun areItemsTheSame(oldItem: AppOrder, newItem: AppOrder): Boolean = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: AppOrder, newItem: AppOrder): Boolean = oldItem == newItem
    }

    private fun String.roleLabel(): String = when (this) {
        UserRoles.ADMIN -> getString(R.string.profile_role_admin)
        UserRoles.VENDEUR -> getString(R.string.profile_role_vendeur)
        else -> getString(R.string.profile_role_client)
    }

    private fun String.statusLabel(): String = when (lowercase(Locale.getDefault())) {
        "active" -> getString(R.string.admin_client_status_active)
        "disabled", "blocked", "suspended" -> getString(R.string.admin_client_status_blocked)
        else -> replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    companion object {
        private const val EXTRA_CLIENT_ID = "extra_client_id"

        fun createIntent(context: Context, clientId: String): Intent {
            return Intent(context, AdminClientDetailsActivity::class.java)
                .putExtra(EXTRA_CLIENT_ID, clientId)
        }
    }
}
