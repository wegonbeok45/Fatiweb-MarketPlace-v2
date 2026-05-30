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
 * ViewModel for the admin users list screen.
 *
 * Loads paginated [FirestoreService.ClientInfo] records from Firestore.
 * Search filtering is client-side (fast, no extra indexes needed) applied
 * on top of the locally buffered page set.
 *
 * Stats (total count, new in last 30 days) are derived from the full
 * [_allClients] buffer and exposed as plain properties — the activity
 * re-reads them after every [emitFiltered] call.
 */
class AdminClientsViewModel : ViewModel() {

    // ===== Exposed state =====

    private val _state = MutableStateFlow<UiState<List<FirestoreService.ClientInfo>>>(UiState.Loading)
    val state: StateFlow<UiState<List<FirestoreService.ClientInfo>>> = _state.asStateFlow()

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    val hasMore: Boolean get() = _cursor != null && !_loadingMore.value

    // Stats derived from the full buffer — read after each emit
    val totalCount: Int get() = _allClients.size
    val newCount: Int get() = _allClients.count { client ->
        System.currentTimeMillis() - client.createdAt <= 30L * 24L * 60L * 60L * 1_000L
    }

    // ===== Internal =====

    private val _allClients = mutableListOf<FirestoreService.ClientInfo>()
    private var _cursor: DocumentSnapshot? = null
    private val pageSize = 30

    // ===== Public API =====

    fun load() {
        if (_state.value is UiState.Data) return
        refresh()
    }

    fun refresh() {
        _allClients.clear()
        _cursor = null
        _state.value = UiState.Loading
        fetchPage()
    }

    fun loadNextPage() {
        if (!hasMore) return
        fetchPage()
    }

    fun setSearch(query: String) {
        _search.value = query
        emitFiltered()
    }

    // ===== Internal =====

    private fun fetchPage() {
        if (_loadingMore.value) return
        _loadingMore.value = true
        viewModelScope.launch {
            val result = withTimeoutOrNull(20_000L) {
                runCatching {
                    AdminService.fetchClientsPage(pageSize, _cursor)
                }
            }
            _loadingMore.value = false
            when {
                result == null -> {
                    if (_allClients.isEmpty())
                        _state.value = UiState.Error(RuntimeException("timeout"))
                }
                result.isFailure -> {
                    if (_allClients.isEmpty())
                        _state.value = UiState.Error(result.exceptionOrNull())
                }
                else -> {
                    val snapshot = result.getOrThrow()
                    val newClients = snapshot.documents.mapNotNull(AdminService::clientInfoFromDocument)
                    _cursor = snapshot.documents.lastOrNull()
                        ?.takeIf { snapshot.documents.size >= pageSize }
                    _allClients.addAll(newClients)
                    emitFiltered()
                }
            }
        }
    }

    private fun emitFiltered() {
        val query = _search.value.trim()
        val filtered = if (query.isBlank()) {
            _allClients.toList()
        } else {
            _allClients.filter { c ->
                c.name.contains(query, ignoreCase = true) ||
                    c.email.contains(query, ignoreCase = true) ||
                    c.phone.contains(query, ignoreCase = true) ||
                    c.role.contains(query, ignoreCase = true)
            }
        }
        _state.value = when {
            filtered.isEmpty() && _allClients.isEmpty() -> UiState.Empty
            filtered.isEmpty()                          -> UiState.Empty
            else                                        -> UiState.Data(filtered)
        }
    }
}
