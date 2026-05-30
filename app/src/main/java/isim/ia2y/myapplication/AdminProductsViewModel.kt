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
 * Admin products list ViewModel.
 *
 * Filter switching is server-side (different Firestore queries per status) so
 * only the visible page is held in memory. Pagination appends to [_products].
 */
class AdminProductsViewModel : ViewModel() {

    enum class Filter(val approvalStatus: ProductApprovalStatus?) {
        ALL(null),
        PENDING(ProductApprovalStatus.PENDING),
        APPROVED(ProductApprovalStatus.APPROVED),
        REJECTED(ProductApprovalStatus.REJECTED),
        DRAFT(ProductApprovalStatus.DRAFT),
        ARCHIVED(ProductApprovalStatus.ARCHIVED),
    }

    // ===== Exposed state =====

    private val _filter = MutableStateFlow(Filter.ALL)
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    private val _state = MutableStateFlow<UiState<List<Product>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Product>>> = _state.asStateFlow()

    /** True while a next-page fetch is in flight. */
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    val hasMore: Boolean get() = _nextCursor != null && !_loadingMore.value

    // ===== Internal =====

    private val _products = mutableListOf<Product>()
    private var _nextCursor: DocumentSnapshot? = null

    // ===== Load / filter =====

    fun load() {
        if (_state.value is UiState.Data) return
        refresh()
    }

    fun refresh() {
        _products.clear()
        _nextCursor = null
        _state.value = UiState.Loading
        fetchPage()
    }

    fun setFilter(f: Filter) {
        if (_filter.value == f) return
        _filter.value = f
        refresh()
    }

    fun loadNextPage() {
        if (!hasMore) return
        fetchPage()
    }

    // ===== Action dispatchers =====

    fun approve(product: Product) = runAction(product) {
        AdminProductService.approve(product.id)
    }

    fun reject(product: Product, reason: String = "") = runAction(product) {
        AdminProductService.reject(product.id, reason)
    }

    fun archive(product: Product) = runAction(product) {
        AdminProductService.archive(product.id)
    }

    // ===== Internals =====

    private fun fetchPage() {
        if (_loadingMore.value) return
        _loadingMore.value = true

        viewModelScope.launch {
            val result = withTimeoutOrNull(20_000L) {
                runCatching {
                    AdminProductService.fetchAdminProductsPage(
                        approvalFilter = _filter.value.approvalStatus,
                        lastDoc = _nextCursor,
                    )
                }
            }
            _loadingMore.value = false

            when {
                result == null -> {
                    if (_products.isEmpty()) _state.value = UiState.Error(RuntimeException("timeout"))
                }
                result.isFailure -> {
                    if (_products.isEmpty()) _state.value = UiState.Error(result.exceptionOrNull())
                }
                else -> {
                    val (newItems, cursor) = result.getOrThrow()
                    _nextCursor = cursor
                    _products.addAll(newItems)
                    _state.value = if (_products.isEmpty()) UiState.Empty
                    else UiState.Data(_products.toList())
                }
            }
        }
    }

    /**
     * Runs a moderation action, then removes the mutated product from the
     * current list optimistically. Callers receive the result via [onResult].
     */
    private fun runAction(
        product: Product,
        onResult: ((Result<Unit>) -> Unit)? = null,
        action: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            val result = runCatching { action() }
            result.onSuccess {
                // Optimistic removal — the product's status changed, so it no
                // longer belongs in the current filtered view.
                _products.removeAll { it.id == product.id }
                _state.value = if (_products.isEmpty()) UiState.Empty
                else UiState.Data(_products.toList())
            }
            onResult?.invoke(result)
        }
    }
}
