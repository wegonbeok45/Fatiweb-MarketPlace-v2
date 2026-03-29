package isim.ia2y.myapplication

sealed interface ScreenState<out T> {
    data object Loading : ScreenState<Nothing>
    data class Content<T>(val data: T) : ScreenState<T>
    data class Empty(val message: String = "") : ScreenState<Nothing>
    data class Error(val message: String) : ScreenState<Nothing>
}
