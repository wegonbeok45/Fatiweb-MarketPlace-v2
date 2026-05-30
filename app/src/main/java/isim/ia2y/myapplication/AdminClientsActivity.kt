package isim.ia2y.myapplication

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import isim.ia2y.myapplication.ui.state.StateRenderer
import isim.ia2y.myapplication.ui.state.UiState
import kotlinx.coroutines.launch

/**
 * Rebuilt admin users list screen.
 *
 * Paginated list of all registered users with client-side search filtering.
 * Stats (total loaded + new in last 30 days) shown in a KPI banner that
 * appears once the first page arrives.
 *
 * Tapping a row opens [AdminClientDetailsActivity] for the full profile,
 * order history, and promote/revoke actions.
 */
class AdminClientsActivity : AppCompatActivity() {

    private val viewModel: AdminClientsViewModel by viewModels()

    private val adapter = AdminClientsAdapter { client ->
        startActivity(AdminClientDetailsActivity.createIntent(this, client.uid))
    }

    private lateinit var listState: StateRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_admin_clients_v2)

        applyInsets()
        setupTopBar()
        setupSearch()
        setupList()
        setupBottomNav()

        listState = StateRenderer(
            loadingView = findViewById(R.id.adminClientsLoading),
            emptyView   = findViewById(R.id.adminClientsEmpty),
            errorView   = findViewById(R.id.adminClientsError),
            dataView    = findViewById(R.id.adminClientsList),
        ).also { r ->
            r.bindEmpty(
                titleRes    = R.string.admin_no_clients,
                subtitleRes = R.string.admin_clients_search_hint,
            )
            r.bindError(
                titleRes    = R.string.ms_error_default_title,
                subtitleRes = R.string.admin_clients_load_error,
                onRetry     = { viewModel.refresh() },
            )
        }

        observeState()

        lifecycleScope.launch {
            if (!requireAdminRole()) return@launch
            viewModel.load()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAdminBottomNav(AdminNavTab.CLIENTS)
    }

    // ===== Insets =====

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.adminClientsAppBar)
        ) { v, insets ->
            v.updatePadding(top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.adminClientsList)
        ) { v, insets ->
            v.updatePadding(
                bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom + 96
            )
            insets
        }
    }

    // ===== Top bar =====

    private fun setupTopBar() {
        findViewById<View>(R.id.adminClientsBell)?.setOnClickListener {
            openNotificationsScreenWithPermissionCheck()
        }
        applyPressFeedback(R.id.adminClientsBell)
    }

    // ===== Search =====

    private fun setupSearch() {
        findViewById<com.google.android.material.textfield.TextInputEditText>(
            R.id.adminClientsSearchInput
        )?.doAfterTextChanged { text ->
            viewModel.setSearch(text?.toString().orEmpty())
        }
    }

    // ===== List + infinite scroll =====

    private fun setupList() {
        val rv = findViewById<RecyclerView>(R.id.adminClientsList) ?: return
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (lm.findLastVisibleItemPosition() >= lm.itemCount - 5 && viewModel.hasMore) {
                    viewModel.loadNextPage()
                }
            }
        })
    }

    // ===== Bottom nav =====

    private fun setupBottomNav() {
        setupAdminBottomNav(AdminNavTab.CLIENTS)
    }

    // ===== State observation =====

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    listState.render(state)
                    when (state) {
                        is UiState.Data -> {
                            adapter.submitList(state.value)
                            updateKpi(show = true)
                        }
                        is UiState.Empty -> {
                            adapter.submitList(emptyList())
                            // Keep KPI visible if we had data before (search narrowed to zero)
                            updateKpi(show = viewModel.totalCount > 0)
                        }
                        is UiState.Error -> {
                            adapter.submitList(emptyList())
                            updateKpi(show = false)
                        }
                        else -> Unit
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.loadingMore.collect { loading ->
                    findViewById<ProgressBar>(R.id.adminClientsLoadMore)?.visibility =
                        if (loading && viewModel.state.value is UiState.Data) View.VISIBLE
                        else View.GONE
                }
            }
        }
    }

    // ===== KPI banner =====

    private fun updateKpi(show: Boolean) {
        val kpi = findViewById<View>(R.id.adminClientsKpiRow) ?: return
        if (!show) {
            kpi.visibility = View.GONE
            return
        }
        kpi.visibility = View.VISIBLE
        findViewById<TextView>(R.id.adminClientsTvTotal)?.text = viewModel.totalCount.toString()
        findViewById<TextView>(R.id.adminClientsTvNew)?.text   = viewModel.newCount.toString()
    }
}
