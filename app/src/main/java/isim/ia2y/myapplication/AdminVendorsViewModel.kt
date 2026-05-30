package isim.ia2y.myapplication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import isim.ia2y.myapplication.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Admin vendors list ViewModel. Holds raw rows from [AdminVendorService.fetchVendors]
 * and filters them client-side by [Filter] so switching tabs is instant.
 */
class AdminVendorsViewModel : ViewModel() {

    enum class Filter {
        ALL, PENDING, APPROVED, SUSPENDED, REJECTED;
    }

    private val _filter = MutableStateFlow(Filter.ALL)
    val filter: StateFlow<Filter> = _filter.asStateFlow()

    private val _state = MutableStateFlow<UiState<List<AdminVendorService.VendorRow>>>(UiState.Loading)
    val state: StateFlow<UiState<List<AdminVendorService.VendorRow>>> = _state.asStateFlow()

    /** Full unfiltered list; filtering is applied in [emitFiltered]. */
    private var allRows: List<AdminVendorService.VendorRow> = emptyList()

    // ===== Loading =====

    fun load() {
        if (_state.value is UiState.Data) return
        refresh()
    }

    fun refresh() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            val result = withTimeoutOrNull(20_000L) {
                runCatching { AdminVendorService.fetchVendors(status = null) }
            }
            when {
                result == null -> _state.value = UiState.Error(RuntimeException("timeout"))
                result.isFailure -> _state.value = UiState.Error(result.exceptionOrNull())
                else -> {
                    allRows = result.getOrThrow()
                    emitFiltered()
                }
            }
        }
    }

    // ===== Filtering =====

    fun setFilter(f: Filter) {
        _filter.value = f
        emitFiltered()
    }

    private fun emitFiltered() {
        val filtered = when (_filter.value) {
            Filter.ALL -> allRows
            Filter.PENDING -> allRows.filter { it.status == VendorStatus.PENDING }
            Filter.APPROVED -> allRows.filter { it.status == VendorStatus.APPROVED }
            Filter.SUSPENDED -> allRows.filter { it.status == VendorStatus.SUSPENDED }
            Filter.REJECTED -> allRows.filter { it.status == VendorStatus.REJECTED }
        }
        _state.value = if (filtered.isEmpty()) UiState.Empty else UiState.Data(filtered)
    }

    // ===== Actions =====

    /** Called after an admin action mutates a vendor row. Refreshes from Firestore. */
    fun reload() {
        allRows = emptyList()
        refresh()
    }
}
