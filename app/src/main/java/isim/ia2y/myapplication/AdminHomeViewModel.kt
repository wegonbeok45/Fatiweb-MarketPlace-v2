package isim.ia2y.myapplication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import isim.ia2y.myapplication.ui.state.UiState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Admin home dashboard ViewModel. Loads platform-wide stats, pending vendor
 * count, and a recent-orders feed in a single coroutine scope.
 */
class AdminHomeViewModel : ViewModel() {

    data class Data(
        val stats: FirestoreService.AdminStats,
        val totalVendors: Int,
        val pendingVendors: Int,
        /** Pair of (uid, AppOrder); AppOrder.id is the Firestore document id. */
        val recentOrders: List<Pair<String, AppOrder>>,
    )

    private val _state = MutableStateFlow<UiState<Data>>(UiState.Loading)
    val state: StateFlow<UiState<Data>> = _state.asStateFlow()

    fun load() {
        if (_state.value is UiState.Data) return   // skip if already populated on resume
        refresh()
    }

    fun refresh() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            val result = withTimeoutOrNull(20_000L) {
                runCatching {
                    coroutineScope {
                        val statsDeferred = async { FirestoreService.fetchAdminStats() }
                        val vendorsDeferred = async { AdminVendorService.countApprovedVendors() }
                        val pendingDeferred = async { AdminVendorService.countPendingVendors() }
                        val ordersDeferred = async { fetchRecentOrders() }

                        val stats = statsDeferred.await()
                        val vendors = vendorsDeferred.await()
                        val pending = pendingDeferred.await()
                        val orders = ordersDeferred.await()

                        Data(
                            stats = stats,
                            totalVendors = vendors,
                            pendingVendors = pending,
                            recentOrders = orders,
                        )
                    }
                }
            }
            when {
                result == null -> _state.value = UiState.Error(
                    cause = RuntimeException("timeout"),
                )
                result.isFailure -> _state.value = UiState.Error(
                    cause = result.exceptionOrNull(),
                )
                else -> {
                    val data = result.getOrThrow()
                    _state.value = if (data.stats.totalOrders == 0 &&
                        data.recentOrders.isEmpty()
                    ) UiState.Empty else UiState.Data(data)
                }
            }
        }
    }

    /**
     * Fetches the most recent 10 orders across the whole marketplace
     * using the AdminService cache layer.
     */
    private suspend fun fetchRecentOrders(): List<Pair<String, AppOrder>> = runCatching {
        AdminService.fetchRecentOrders(limit = 10)
    }.getOrDefault(emptyList())
}
