package isim.ia2y.myapplication

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import isim.ia2y.myapplication.ui.state.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

class VendorAnalyticsViewModel : ViewModel() {

    data class DayPoint(val dayLabel: String, val orderCount: Int, val revenue: Double)

    data class TopProduct(
        val productId: String,
        val name: String,
        val quantitySold: Int,
        val totalRevenue: Double,
        val thumbnailUrl: String,
    )

    data class LowStockProduct(
        val productId: String,
        val name: String,
        val stock: Int,
        val price: Double,
        val thumbnailUrl: String,
    )

    data class Data(
        val weekRevenue: Double,
        val weekOrders: Int,
        val avgBasket: Double,
        val deliveryRate: Double,
        val dayPoints: List<DayPoint>,
        val topProducts: List<TopProduct>,
        val lowStock: List<LowStockProduct>,
    )

    private val _state = MutableStateFlow<UiState<Data>>(UiState.Loading)
    val state: StateFlow<UiState<Data>> = _state.asStateFlow()

    fun load(sellerId: String?) {
        if (sellerId.isNullOrBlank()) {
            _state.value = UiState.Error()
            return
        }
        _state.value = UiState.Loading
        viewModelScope.launch {
            val workspaceResult = runCatching { AdminService.fetchSellerWorkspace(sellerId) }
            workspaceResult
                .onSuccess { workspace -> _state.value = compute(workspace, sellerId) }
                .onFailure { _state.value = UiState.Error(cause = it) }
        }
    }

    private suspend fun compute(
        workspace: AdminService.SellerWorkspace,
        sellerId: String,
    ): UiState<Data> {
        val rows = workspace.orders.toSellerRows(sellerId)
        val now = System.currentTimeMillis()
        val weekStart = now - TimeUnit.DAYS.toMillis(6)
        val weekRows = rows.filter { it.order.createdAtMillis >= startOfDay(weekStart) }
        val weekRevenue = weekRows.sumOf { it.sellerTotal }
        val weekOrders = weekRows.size
        val avgBasket = if (weekOrders > 0) weekRevenue / weekOrders else 0.0
        val delivered = weekRows.count {
            OrderStatuses.normalize(it.order.status) == OrderStatuses.DELIVERED
        }
        val deliveryRate = if (weekOrders > 0) delivered.toDouble() / weekOrders else 0.0

        val dayPoints = buildDayPoints(weekRows)
        val topProducts = buildTopProducts(rows)
        val lowStock = fetchLowStock(sellerId)

        return UiState.Data(
            Data(
                weekRevenue = weekRevenue,
                weekOrders = weekOrders,
                avgBasket = avgBasket,
                deliveryRate = deliveryRate,
                dayPoints = dayPoints,
                topProducts = topProducts,
                lowStock = lowStock,
            )
        )
    }

    private fun buildDayPoints(rows: List<AdminService.SellerOrderRow>): List<DayPoint> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // Build the 7 buckets newest-first then reverse for chart left-to-right.
        val buckets = ArrayList<DayPoint>(7)
        val labels = arrayOf("Dim", "Lun", "Mar", "Mer", "Jeu", "Ven", "Sam")
        for (i in 0 until 7) {
            val dayStart = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, 1)
            val dayEnd = cal.timeInMillis
            cal.add(Calendar.DAY_OF_YEAR, -1)

            val dayRows = rows.filter { it.order.createdAtMillis in dayStart until dayEnd }
            val cnt = dayRows.size
            val rev = dayRows.sumOf { it.sellerTotal }
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1
            buckets.add(DayPoint(labels[dayOfWeek], cnt, rev))
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return buckets.reversed()
    }

    private fun buildTopProducts(rows: List<AdminService.SellerOrderRow>): List<TopProduct> {
        data class Acc(var qty: Int, var revenue: Double, val name: String, val thumb: String)
        val map = mutableMapOf<String, Acc>()
        rows.forEach { row ->
            row.sellerItems.forEach { item ->
                val acc = map.getOrPut(item.productId) { Acc(0, 0.0, item.name, item.thumbnailUrl) }
                acc.qty += item.quantity
                acc.revenue += item.priceAtPurchase * item.quantity
            }
        }
        return map.entries
            .sortedByDescending { it.value.qty }
            .take(5)
            .map { (id, acc) ->
                TopProduct(id, acc.name, acc.qty, acc.revenue, acc.thumb)
            }
    }

    private suspend fun fetchLowStock(sellerId: String): List<LowStockProduct> {
        return runCatching {
            val (products, _) = ProductService.fetchProductsPaginated(
                pageSize = 50L,
                sellerIdFilter = sellerId,
            )
            products
                .filter { it.stock in 1..VendorProductsViewModel.LOW_STOCK_THRESHOLD }
                .sortedBy { it.stock }
                .take(5)
                .map { p ->
                    LowStockProduct(
                        productId = p.id,
                        name = p.title,
                        stock = p.stock,
                        price = p.effectivePrice,
                        thumbnailUrl = p.thumbnailUrl ?: p.imageUrl.orEmpty(),
                    )
                }
        }.getOrDefault(emptyList())
    }

    private fun List<AppOrder>.toSellerRows(sellerId: String): List<AdminService.SellerOrderRow> =
        mapNotNull { order ->
            val mine = order.items.filter { it.sellerId == sellerId }
            if (mine.isEmpty()) null
            else AdminService.SellerOrderRow(order.uid, order, mine)
        }

    private fun startOfDay(millis: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }
}
