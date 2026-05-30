package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.Source
import kotlinx.coroutines.launch

class OrdersHistoryActivity : AppCompatActivity() {
    private var allOrders: List<AppOrder> = emptyList()
    private var selectedFilter: OrderFilter = OrderFilter.ALL
    private var ordersErrorVisible: Boolean = false

    private val ordersAdapter = OrdersHistoryAdapter { order ->
        startActivity(OrderDetailsActivity.createIntent(this, order.id))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_orders_history)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<View>(R.id.ivBack)?.setOnClickListener { finishWithMotion(isForward = false) }
        findViewById<View>(R.id.btnOrdersBrowseHome)?.setOnClickListener {
            if (ordersErrorVisible) loadOrders() else handleEmptyStateAction()
        }
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvOrders)?.layoutManager =
            LinearLayoutManager(this)
        bindFilterControls()
        applyPressFeedback(R.id.ivBack, R.id.btnOrdersBrowseHome)
        loadOrders()
    }

    private fun loadOrders() {
        val uid = FirebaseAuthManager.currentUser?.uid
        if (uid == null) {
            renderGuestState()
            return
        }
        val localOrders = LocalOrderStore.getAll(this)
        if (localOrders.isNotEmpty()) {
            renderState(ScreenState.Content(localOrders))
        } else {
            renderState(ScreenState.Loading)
        }

        lifecycleScope.launch {
            runCatching { FirestoreService.fetchOrders(uid, source = Source.CACHE) }
                .getOrDefault(emptyList())
                .takeIf { it.isNotEmpty() }
                ?.let { cachedOrders ->
                    LocalOrderStore.mergeRemote(this@OrdersHistoryActivity, cachedOrders)
                    renderState(ScreenState.Content(LocalOrderStore.getAll(this@OrdersHistoryActivity)))
                }

            val result = runCatching { FirestoreService.fetchOrders(uid, source = Source.SERVER) }
            val state: ScreenState<List<AppOrder>> = result.fold(
                onSuccess = { orders ->
                    val merged = if (orders.isEmpty()) localOrders else {
                        LocalOrderStore.mergeRemote(this@OrdersHistoryActivity, orders)
                        LocalOrderStore.getAll(this@OrdersHistoryActivity)
                    }
                    if (merged.isEmpty()) ScreenState.Empty() else ScreenState.Content(merged)
                },
                onFailure = {
                    if (localOrders.isEmpty()) {
                        ScreenState.Error(getString(R.string.orders_history_load_error))
                    } else {
                        ScreenState.Content(localOrders)
                    }
                }
            )
            renderState(state)
        }
    }

    private fun renderState(state: ScreenState<List<AppOrder>>) {
        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvOrders) ?: return
        val emptyState = findViewById<View>(R.id.layoutEmptyOrdersState) ?: return
        val emptyAnimation = findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.ivEmptyOrdersAnimation)
        val shimmer = findViewById<View>(R.id.layoutOrdersShimmer)

        fun hideShimmer() {
            shimmer?.stopShimmerPulse()
            shimmer?.visibility = View.GONE
        }

        when (state) {
            is ScreenState.Content -> {
                ordersErrorVisible = false
                allOrders = state.data
                hideShimmer()
                resetEmptyAnimation(emptyAnimation)
                if (recycler.adapter == null) {
                    recycler.adapter = ordersAdapter
                }
                renderFilteredOrders()
            }
            is ScreenState.Empty -> {
                ordersErrorVisible = false
                allOrders = emptyList()
                hideShimmer()
                recycler.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
                configureEmptyState(
                    title = if (FirebaseAuthManager.currentUser == null) {
                        getString(R.string.orders_history_guest_title)
                    } else {
                        getString(R.string.orders_history_empty)
                    },
                    subtitle = if (FirebaseAuthManager.currentUser == null) {
                        getString(R.string.orders_history_guest_subtitle)
                    } else {
                        getString(R.string.orders_history_empty_subtitle)
                    },
                    action = if (FirebaseAuthManager.currentUser == null) {
                        getString(R.string.orders_history_guest_action)
                    } else {
                        getString(R.string.orders_history_empty_action)
                    }
                )
                playEmptyAnimation(emptyAnimation)
            }
            is ScreenState.Error -> {
                ordersErrorVisible = true
                allOrders = emptyList()
                hideShimmer()
                recycler.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
                configureEmptyState(
                    title = state.message,
                    subtitle = getString(R.string.orders_history_error_subtitle),
                    action = getString(R.string.cart_sync_retry_action)
                )
                playEmptyAnimation(emptyAnimation)
            }
            ScreenState.Loading -> {
                ordersErrorVisible = false
                allOrders = emptyList()
                shimmer?.visibility = View.VISIBLE
                shimmer?.startShimmerPulse()
                recycler.visibility = View.GONE
                emptyState.visibility = View.GONE
                resetEmptyAnimation(emptyAnimation)
            }
        }
    }

    private fun renderGuestState() {
        renderState(ScreenState.Empty())
    }

    private fun configureEmptyState(title: String, subtitle: String, action: String) {
        findViewById<TextView>(R.id.tvEmptyOrders)?.text = title
        findViewById<TextView>(R.id.tvEmptyOrdersSubtitle)?.text = subtitle
        (findViewById<View>(R.id.btnOrdersBrowseHome) as? com.google.android.material.button.MaterialButton)
            ?.text = action
    }

    private fun playEmptyAnimation(animationView: com.airbnb.lottie.LottieAnimationView?) {
        animationView?.apply {
            cancelAnimation()
            progress = 0f
            playAnimation()
        }
    }

    private fun resetEmptyAnimation(animationView: com.airbnb.lottie.LottieAnimationView?) {
        animationView?.apply {
            cancelAnimation()
            progress = 0f
        }
    }

    private fun handleEmptyStateAction() {
        if (FirebaseAuthManager.currentUser == null) {
            startActivity(
                LoginActivity.createIntent(
                    this,
                    returnToRoute = AUTH_RETURN_ROUTE_ORDERS
                )
            )
        } else {
            navigateToMainTab(MainActivity.Tab.HOME)
        }
    }

    private fun bindFilterControls() {
        findViewById<com.google.android.material.chip.ChipGroup>(R.id.chipGroupOrders)
            ?.setOnCheckedStateChangeListener { _, checkedIds ->
                selectedFilter = when (checkedIds.firstOrNull()) {
                    R.id.chipOrdersActive -> OrderFilter.ACTIVE
                    R.id.chipOrdersDelivered -> OrderFilter.DELIVERED
                    R.id.chipOrdersCancelled -> OrderFilter.CANCELLED
                    else -> OrderFilter.ALL
                }
                renderFilteredOrders()
            }
    }

    private fun renderFilteredOrders() {
        val recycler = findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvOrders) ?: return
        val emptyState = findViewById<View>(R.id.layoutEmptyOrdersState) ?: return
        val emptyAnimation = findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.ivEmptyOrdersAnimation)
        val filtered = allOrders.filter { order ->
            val normalized = OrderStatuses.normalize(order.status)
            when (selectedFilter) {
                OrderFilter.ALL -> true
                OrderFilter.ACTIVE -> normalized !in setOf(OrderStatuses.DELIVERED, OrderStatuses.CANCELLED)
                OrderFilter.DELIVERED -> normalized == OrderStatuses.DELIVERED
                OrderFilter.CANCELLED -> normalized == OrderStatuses.CANCELLED
            }
        }
        ordersErrorVisible = false
        recycler.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        emptyState.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        if (filtered.isEmpty()) {
            configureEmptyState(
                title = getString(R.string.orders_history_empty),
                subtitle = getString(R.string.orders_history_empty_subtitle),
                action = getString(R.string.orders_history_empty_action)
            )
            playEmptyAnimation(emptyAnimation)
        } else {
            resetEmptyAnimation(emptyAnimation)
        }
        ordersAdapter.submitList(filtered)
    }

    private enum class OrderFilter {
        ALL,
        ACTIVE,
        DELIVERED,
        CANCELLED
    }
}
