package isim.ia2y.myapplication

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.annotation.SuppressLint
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object LocationHelper {
    private const val TAG = "LocationHelper"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val geocodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var pendingListener: LocationListener? = null

    fun hasPermission(context: Context): Boolean {
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return coarse || fine
    }

    @SuppressLint("MissingPermission")
    fun resolveCurrentLocation(context: Context, onResolved: (String) -> Unit = {}) {
        if (!hasPermission(context)) return

        val locationManager = context.getSystemService(LocationManager::class.java) ?: return
        
        // 1. Try last known
        val lastKnown = getBestLastKnownLocation(locationManager)
        if (lastKnown != null) {
            reverseGeocode(context, lastKnown) { resolved ->
                onResolved(resolved)
            }
        }

        // 2. Request fresh update if needed
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER
        )

        val provider = providers.firstOrNull {
            runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false)
        } ?: return

        // Cleanup old listener
        pendingListener?.let { runCatching { locationManager.removeUpdates(it) } }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                reverseGeocode(context, location) { resolved ->
                    onResolved(resolved)
                }
                locationManager.removeUpdates(this)
                if (pendingListener == this) pendingListener = null
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        pendingListener = listener
        runCatching { locationManager.requestLocationUpdates(provider, 10000L, 50f, listener) }
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(locationManager: LocationManager): Location? {
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        return providers
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
    }

    private fun reverseGeocode(context: Context, location: Location, onResult: (String) -> Unit) {
        if (!Geocoder.isPresent()) return
        val geocoder = Geocoder(context, Locale.getDefault())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(location.latitude, location.longitude, 1) { addresses ->
                val resolved = formatAddress(addresses.firstOrNull())
                if (!resolved.isNullOrBlank()) {
                    mainHandler.post { onResult(resolved) }
                }
            }
            return
        }

        geocodeExecutor.execute {
            val resolved = runCatching {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(location.latitude, location.longitude, 1)
            }.getOrNull()?.let { addresses ->
                formatAddress(addresses.firstOrNull())
            }

            if (!resolved.isNullOrBlank()) {
                mainHandler.post { onResult(resolved) }
            }
        }
    }

    fun cleanup() {
        pendingListener?.let { listener ->
            runCatching {
                // Intentionally not using context here since the manager may be stale
            }
        }
        pendingListener = null
        geocodeExecutor.shutdownNow()
    }

    private fun formatAddress(address: Address?): String? {
        address ?: return null
        val city = address.locality ?: address.subAdminArea ?: address.adminArea
        val country = address.countryName
        return when {
            !city.isNullOrBlank() && !country.isNullOrBlank() -> "$city, $country"
            !country.isNullOrBlank() -> country
            else -> null
        }
    }
}
