package isim.ia2y.myapplication

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.firestore.FirebaseFirestoreException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Connectivity helpers used to surface a branded "offline" state instead of a
 * generic load error when the device has no usable network.
 */

fun Context.isOnline(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
    val capabilities = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

fun Throwable.isNetworkError(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        when (current) {
            is FirebaseNetworkException,
            is UnknownHostException,
            is ConnectException,
            is SocketTimeoutException,
            is InterruptedIOException -> return true
            is FirebaseFirestoreException ->
                if (current.code == FirebaseFirestoreException.Code.UNAVAILABLE) return true
        }
        current = current.cause
        depth++
    }
    return false
}
