package isim.ia2y.myapplication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.DocumentSnapshot
import isim.ia2y.myapplication.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class VendorProductsViewModel : ViewModel() {

    enum class Filter { All, Published, Drafts, Archived, Pending, Rejected, LowStock }

    private val all = mutableListOf<Product>()
    private var nextPageCursor: DocumentSnapshot? = null
    private var sellerId: String? = null
    private var isLoadingMore = false
    private var reachedEnd = false

    private val _state = MutableStateFlow<UiState<List<Product>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Product>>> = _state.asStateFlow()

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
        all.clear()
        nextPageCursor = null
        reachedEnd = false
        _state.value = UiState.Loading
        loadPage()
    }

    fun loadNextPage() {
        if (isLoadingMore || reachedEnd) return
        loadPage()
    }

    private fun loadPage() {
        val uid = sellerId ?: run {
            _state.value = UiState.Error()
            return
        }
        if (uid.isBlank()) {
            _state.value = UiState.Empty
            return
        }
        isLoadingMore = true
        viewModelScope.launch {
            runCatching {
                ProductService.fetchProductsPaginated(
                    pageSize = PAGE_SIZE,
                    lastDoc = nextPageCursor,
                    sellerIdFilter = uid,
                )
            }.onSuccess { (products, cursor) ->
                isLoadingMore = false
                if (cursor == null || products.isEmpty()) reachedEnd = true
                nextPageCursor = cursor
                all.addAll(products)
                emitFiltered()
            }.onFailure {
                isLoadingMore = false
                if (all.isEmpty()) _state.value = UiState.Error(cause = it)
            }
        }
    }

    private fun emitFiltered() {
        val source = all
        val filtered = when (_filter.value) {
            Filter.All -> source
            Filter.Published -> source.filter { it.status == "published" && it.isActive }
            Filter.Drafts -> source.filter { it.status == "draft" }
            Filter.Archived -> source.filter { it.status == "archived" || !it.isActive }
            Filter.Pending -> source.filter {
                ProductApprovalStatus.fromWire(it.approvalStatus) == ProductApprovalStatus.PENDING
            }
            Filter.Rejected -> source.filter {
                ProductApprovalStatus.fromWire(it.approvalStatus) == ProductApprovalStatus.REJECTED
            }
            Filter.LowStock -> source.filter { it.stock in 1..LOW_STOCK_THRESHOLD }
        }
        _state.value = if (filtered.isEmpty()) UiState.Empty else UiState.Data(filtered)
    }

    companion object {
        const val LOW_STOCK_THRESHOLD = 5
        private const val PAGE_SIZE = 20L
    }
}
