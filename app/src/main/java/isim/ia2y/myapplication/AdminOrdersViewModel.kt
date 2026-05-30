package isim.ia2y.myapplication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.DocumentSnapshot
import isim.ia2y.myapplication.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Admin orders list ViewModel.
 *
 * Filtering is client-side: we load pages of recent orders (all statuses) and
 * filter the visible list locally. This avoids Firestore composite-index
 * requirements and keeps switching tabs instant.
 */
class AdminOrdersViewModel : ViewModel() {

    enum class Filter(val statusKey: String?) {
        ALL(null),
        PENDING(OrderStatuses.PENDING),
        CONFIRMED(OrderStatuses.CONFIRMED),
        PREPARING(OrderStatuses.PREPARING),
        SHIPPED(OrderStatuses.SHIPPED),
        DELIVERED(OrderStatuses.DELIVERED),
        CANCELLED(OrderStatuses.CANCELLED),
    }

    // ===== Exposed state =====

    private val _filter = MutableStateFlow(Filter.ALL)
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    private val _state = MutableStateFlow<UiState<List<Pair<String, AppOrder>>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Pair<String, AppOrder>>>> = _state.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    val hasMore: Boolean get() = _nextCursor != null && !_loadingMore.value

    // ===== Internal =====

    /** Full unfiltered page buffer. */
    private val _allOrders = mutableListOf<Pair<String, AppOrder>>()
    private var _nextCursor: DocumentSnapshot? = null

    private val pageSize = 25

    // ===== Public API =====

    fun load() {
        if (_state.value is UiState.Data) return
        refresh()
    }

    fun refresh() {
        _allOrders.clear()
        _nextCursor = null
        _state.value = UiState.Loading
        fetchPage()
    }

    fun setFilter(f: Filter) {
        _filter.value = f
        emitFiltered()
    }

    fun loadNextPage() {
        if (!hasMore) return
        fetchPage()
    }

    // ===== Internal =====

    private fun fetchPage() {
        if (_loadingMore.value) return
        _loadingMore.value = true
        viewModelScope.launch {
            val result = withTimeoutOrNull(20_000L) {
                runCatching {
                    val snapshot = AdminService.fetchOrdersPage(
                        pageSize = pageSize,
                        lastDoc = _nextCursor,
                    )
                    val newItems = snapshot.documents.mapNotNull { doc ->
                        val data = doc.data ?: return@mapNotNull null
                        doc.id to AppOrder.fromMap(data).copy(id = doc.id)
                    }
                    val cursor = snapshot.documents.lastOrNull()
                        ?.takeIf { snapshot.documents.size >= pageSize }
                    newItems to cursor
                }
            }
            _loadingMore.value = false
            when {
                result == null -> {
                    if (_allOrders.isEmpty())
                        _state.value = UiState.Error(RuntimeException("timeout"))
                }
                result.isFailure -> {
                    if (_allOrders.isEmpty())
                        _state.value = UiState.Error(result.exceptionOrNull())
                }
                else -> {
                    val (newItems, cursor) = result.getOrThrow()
                    _nextCursor = cursor
                    _allOrders.addAll(newItems)
                    emitFiltered()
                }
            }
        }
    }

    private fun emitFiltered() {
        val statusKey = _filter.value.statusKey
        val filtered = if (statusKey == null) _allOrders.toList()
        else _allOrders.filter {
            OrderStatuses.normalize(it.second.status) == statusKey
        }
        _state.value = if (filtered.isEmpty() && _allOrders.isEmpty()) UiState.Empty
        else if (filtered.isEmpty()) UiState.Empty
        else UiState.Data(filtered)
    }
}
