package isim.ia2y.myapplication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import isim.ia2y.myapplication.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AdminAnalyticsViewModel : ViewModel() {

    private val _state = MutableStateFlow<UiState<AdminService.AnalyticsSnapshot>>(UiState.Loading)
    val state: StateFlow<UiState<AdminService.AnalyticsSnapshot>> = _state.asStateFlow()

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            runCatching { AdminService.fetchAnalyticsSnapshot() }
                .onSuccess { snapshot ->
                    _state.value = if (snapshot.totalOrders == 0 &&
                        snapshot.topProducts.isEmpty() &&
                        snapshot.topVendors.isEmpty()
                    ) {
                        UiState.Empty
                    } else {
                        UiState.Data(snapshot)
                    }
                }
                .onFailure { error ->
                    _state.value = UiState.Error(cause = error)
                }
        }
    }
}
