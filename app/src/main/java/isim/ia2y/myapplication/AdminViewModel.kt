package isim.ia2y.myapplication

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

enum class DashboardInlineTab(val navTab: AdminNavTab, val titleRes: Int) {
    OVERVIEW(AdminNavTab.DASHBOARD, R.string.admin_title_dashboard),
    COMMANDES(AdminNavTab.COMMANDES, R.string.admin_title_orders),
    CLIENTS(AdminNavTab.CLIENTS, R.string.admin_title_clients)
}

class AdminViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val DASHBOARD_REFRESH_TTL_MS = 30_000L
    }

    private val _activeTab = MutableLiveData(DashboardInlineTab.OVERVIEW)
    val activeTab: LiveData<DashboardInlineTab> = _activeTab

    private val _isVerified = MutableLiveData(false)
    val isVerified: LiveData<Boolean> = _isVerified

    private val _stats = MutableLiveData<FirestoreService.AdminStats>()
    val stats: LiveData<FirestoreService.AdminStats> = _stats

    private val _orders = MutableLiveData<List<Pair<String, AppOrder>>>()
    val orders: LiveData<List<Pair<String, AppOrder>>> = _orders

    private val _clients = MutableLiveData<List<FirestoreService.ClientInfo>>()
    val clients: LiveData<List<FirestoreService.ClientInfo>> = _clients

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var lastDashboardLoadAt: Long = 0L
    private var dashboardLoadJob: Job? = null

    private fun appStr(resId: Int): String = getApplication<Application>().getString(resId)

    fun setTab(tab: DashboardInlineTab) {
        _activeTab.value = tab
    }

    fun verifyAdmin(uid: String) {
        if (AdminSession.isVerified(uid)) {
            _isVerified.value = true
            return
        }

        viewModelScope.launch {
            val role = runCatching { UserService.isCurrentUserAdmin() }.getOrNull()
            if (role == true) {
                AdminSession.markVerified(uid)
                _isVerified.value = true
            } else {
                _error.value = appStr(R.string.admin_access_denied)
            }
        }
    }

    fun loadDashboardData(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && dashboardLoadJob?.isActive == true) return
        if (force) dashboardLoadJob?.cancel()
        if (
            !force &&
            lastDashboardLoadAt > 0L &&
            now - lastDashboardLoadAt < DASHBOARD_REFRESH_TTL_MS &&
            _stats.value != null &&
            _orders.value != null &&
            _clients.value != null
        ) {
            return
        }

        dashboardLoadJob = viewModelScope.launch {
            _isLoading.value = true
            val statsDeferred = async { runCatching { AdminService.fetchAdminStats() } }
            val ordersDeferred = async { runCatching { AdminService.fetchRecentOrders() } }
            val clientsDeferred = async { runCatching { AdminService.fetchRecentClients() } }

            var failed = false
            statsDeferred.await()
                .onSuccess { _stats.postValue(it) }
                .onFailure {
                    failed = true
                    if (_stats.value == null) {
                        _stats.postValue(FirestoreService.AdminStats())
                    }
                }

            ordersDeferred.await()
                .onSuccess { _orders.postValue(it) }
                .onFailure {
                    failed = true
                    if (_orders.value == null) {
                        _orders.postValue(emptyList())
                    }
                }

            clientsDeferred.await()
                .onSuccess { _clients.postValue(it) }
                .onFailure {
                    failed = true
                    if (_clients.value == null) {
                        _clients.postValue(emptyList())
                    }
                }

            if (failed) {
                _error.postValue(appStr(R.string.admin_dashboard_data_error))
            }
            lastDashboardLoadAt = System.currentTimeMillis()
            _isLoading.value = false
        }
    }

    fun updateOrderStatus(uid: String, orderId: String, newStatus: String) {
        viewModelScope.launch {
            runCatching {
                OrderService.updateOrderStatus(uid, orderId, newStatus)
                loadDashboardData(force = true)
            }.onFailure {
                _error.postValue(appStr(R.string.admin_order_update_failed))
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
