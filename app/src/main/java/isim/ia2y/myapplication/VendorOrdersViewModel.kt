package isim.ia2y.myapplication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import isim.ia2y.myapplication.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class VendorOrdersViewModel : ViewModel() {

    enum class Filter(val statuses: Set<String>) {
        All(emptySet()),
        Pending(setOf(OrderStatuses.PENDING)),
        Confirmed(setOf(OrderStatuses.CONFIRMED)),
        Preparing(setOf(OrderStatuses.PREPARING)),
        Shipped(setOf(OrderStatuses.SHIPPED)),
        Delivered(setOf(OrderStatuses.DELIVERED)),
        Cancelled(setOf("cancelled")),
    }

    private val all = mutableListOf<AdminService.SellerOrderRow>()
    private var sellerId: String? = null

    private val _state = MutableStateFlow<UiState<List<AdminService.SellerOrderRow>>>(UiState.Loading)
    val state: StateFlow<UiState<List<AdminService.SellerOrderRow>>> = _state.asStateFlow()

    private val _filter = MutableStateFlow(Filter.All)
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    fun setSellerId(uid: String?) {
        if (sellerId == uid) return
        sellerId = uid
        refresh()
    }

    fun setFilter(filter: Filter) {
        if (_filter.value == filter) return
        _filter.value = filter
        emitFiltered()
    }

    fun refresh() {
        val uid = sellerId ?: run {
            _state.value = UiState.Error()
            return
        }
        if (uid.isBlank()) {
            _state.value = UiState.Empty
            return
        }
        _state.value = UiState.Loading
        viewModelScope.launch {
            runCatching {
                withTimeout(LOAD_TIMEOUT_MS) {
                    AdminService.fetchSellerWorkspace(uid)
                }
            }.onSuccess { workspace ->
                all.clear()
                all.addAll(workspace.orders.toSellerRows(uid))
                all.sortByDescending { it.order.createdAtMillis }
                emitFiltered()
            }.onFailure {
                if (all.isEmpty()) _state.value = UiState.Error(cause = it)
            }
        }
    }

    private fun emitFiltered() {
        val filter = _filter.value
        val filtered = if (filter == Filter.All) {
            all.toList()
        } else {
            all.filter { row -> OrderStatuses.normalize(row.order.status) in filter.statuses }
        }
        _state.value = if (filtered.isEmpty()) UiState.Empty else UiState.Data(filtered)
    }

    private fun List<AppOrder>.toSellerRows(sellerId: String): List<AdminService.SellerOrderRow> =
        mapNotNull { order ->
            val mine = order.items.filter { it.sellerId == sellerId }
            if (mine.isEmpty()) null
            else AdminService.SellerOrderRow(order.uid, order, mine)
        }

    companion object {
        private const val LOAD_TIMEOUT_MS = 15_000L
    }
}
