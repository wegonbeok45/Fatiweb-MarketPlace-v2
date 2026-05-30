package isim.ia2y.myapplication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import isim.ia2y.myapplication.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.util.Calendar

class VendorHomeViewModel : ViewModel() {

    data class Data(
        val shopName: String?,
        val totalRevenue: Double,
        val monthRevenue: Double,
        val monthOrders: Int,
        val avgBasket: Double,
        val pendingOrders: Int,
        val totalOrders: Int,
        val lowStockCount: Int,
        val activeProducts: Int,
        val totalProducts: Int,
        val recentRows: List<AdminService.SellerOrderRow>,
    )

    private val _state = MutableStateFlow<UiState<Data>>(UiState.Loading)
    val state: StateFlow<UiState<Data>> = _state.asStateFlow()

    fun load(sellerId: String?, shopName: String?) {
        if (sellerId.isNullOrBlank()) {
            _state.value = UiState.Error()
            return
        }
        _state.value = UiState.Loading
        viewModelScope.launch {
            runCatching {
                withTimeout(LOAD_TIMEOUT_MS) {
                    AdminService.fetchSellerWorkspace(sellerId)
                }
            }.onSuccess { workspace ->
                _state.value = compute(workspace, sellerId, shopName)
            }.onFailure {
                _state.value = UiState.Error(cause = it)
            }
        }
    }

    private fun compute(
        workspace: AdminService.SellerWorkspace,
        sellerId: String,
        shopName: String?,
    ): UiState<Data> {
        val rows = workspace.orders.toSellerRows(sellerId)
        if (rows.isEmpty() && workspace.products.totalProducts == 0) {
            return UiState.Data(
                Data(
                    shopName = shopName,
                    totalRevenue = 0.0,
                    monthRevenue = 0.0,
                    monthOrders = 0,
                    avgBasket = 0.0,
                    pendingOrders = 0,
                    totalOrders = 0,
                    lowStockCount = 0,
                    activeProducts = 0,
                    totalProducts = 0,
                    recentRows = emptyList(),
                )
            )
        }

        val (monthStart, _) = currentMonthRange()
        val stats = AdminService.sellerOrderStats(rows)
        val monthRows = rows.filter { it.order.createdAtMillis >= monthStart }
        val monthRevenue = monthRows.sumOf { it.sellerTotal }
        val pending = rows.count {
            OrderStatuses.normalize(it.order.status) == OrderStatuses.PENDING
        }
        val avgBasket = if (rows.isNotEmpty()) stats.totalRevenue / rows.size else 0.0
        val recent = rows.sortedByDescending { it.order.createdAtMillis }.take(5)

        return UiState.Data(
            Data(
                shopName = shopName,
                totalRevenue = stats.totalRevenue,
                monthRevenue = monthRevenue,
                monthOrders = monthRows.size,
                avgBasket = avgBasket,
                pendingOrders = pending,
                totalOrders = stats.totalOrders,
                lowStockCount = workspace.products.lowStockProducts,
                activeProducts = workspace.products.activeProducts,
                totalProducts = workspace.products.totalProducts,
                recentRows = recent,
            )
        )
    }

    private fun List<AppOrder>.toSellerRows(sellerId: String): List<AdminService.SellerOrderRow> =
        mapNotNull { order ->
            val mine = order.items.filter { it.sellerId == sellerId }
            if (mine.isEmpty()) null
            else AdminService.SellerOrderRow(order.uid, order, mine)
        }

    private fun currentMonthRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val start = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        return start to cal.timeInMillis
    }

    companion object {
        private const val LOAD_TIMEOUT_MS = 15_000L
    }
}
