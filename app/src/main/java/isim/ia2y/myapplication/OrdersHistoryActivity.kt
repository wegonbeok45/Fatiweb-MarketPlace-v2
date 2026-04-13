package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.launch

class OrdersHistoryActivity : AppCompatActivity() {

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
        findViewById<View>(R.id.btnOrdersBrowseHome)?.setOnClickListener { handleEmptyStateAction() }
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvOrders)?.layoutManager =
            LinearLayoutManager(this)
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
        }

        lifecycleScope.launch {
            val result = runCatching { FirestoreService.fetchOrders(uid) }
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
        val empty = findViewById<TextView>(R.id.tvEmptyOrders) ?: return
        val emptyState = findViewById<View>(R.id.layoutEmptyOrdersState) ?: return
        val emptyAnimation = findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.ivEmptyOrdersAnimation)
        val loading = findViewById<ProgressBar>(R.id.loadingIndicator)

        when (state) {
            is ScreenState.Content -> {
                loading?.visibility = View.GONE
                recycler.visibility = View.VISIBLE
                emptyState.visibility = View.GONE
                empty.visibility = View.GONE
                resetEmptyAnimation(emptyAnimation)
                if (recycler.adapter == null) {
                    recycler.adapter = ordersAdapter
                }
                ordersAdapter.submitList(state.data)
            }
            is ScreenState.Empty -> {
                loading?.visibility = View.GONE
                recycler.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
                empty.visibility = View.VISIBLE
                playEmptyAnimation(emptyAnimation)
            }
            is ScreenState.Error -> {
                loading?.visibility = View.GONE
                recycler.visibility = View.GONE
                emptyState.visibility = View.VISIBLE
                empty.visibility = View.VISIBLE
                playEmptyAnimation(emptyAnimation)
                showMotionSnackbar(state.message)
            }
            ScreenState.Loading -> {
                loading?.visibility = View.VISIBLE
                recycler.visibility = View.GONE
                emptyState.visibility = View.GONE
                resetEmptyAnimation(emptyAnimation)
            }
        }
    }

    private fun renderGuestState() {
        findViewById<TextView>(R.id.tvEmptyOrders)?.text = getString(R.string.orders_history_guest_title)
        findViewById<TextView>(R.id.tvEmptyOrdersSubtitle)?.text = getString(R.string.orders_history_guest_subtitle)
        (findViewById<View>(R.id.btnOrdersBrowseHome) as? com.google.android.material.button.MaterialButton)
            ?.text = getString(R.string.orders_history_guest_action)
        renderState(ScreenState.Empty())
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
}
