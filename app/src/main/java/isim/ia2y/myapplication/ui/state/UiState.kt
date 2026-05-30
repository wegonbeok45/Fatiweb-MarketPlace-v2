package isim.ia2y.myapplication.ui.state

sealed class UiState<out T> {
    data object Loading : UiState<Nothing>()
    data object Empty : UiState<Nothing>()
    data class Error(val cause: Throwable? = null, val messageRes: Int? = null) : UiState<Nothing>()
    data class Data<T>(val value: T) : UiState<T>()
}

fun <T> UiState<T>.dataOrNull(): T? = (this as? UiState.Data<T>)?.value

fun <T, R> UiState<T>.map(transform: (T) -> R): UiState<R> = when (this) {
    is UiState.Data -> UiState.Data(transform(value))
    is UiState.Error -> this
    UiState.Empty -> UiState.Empty
    UiState.Loading -> UiState.Loading
}
