package isim.ia2y.myapplication

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

object LocationHelper {
    private const val TAG = "LocationFlow"

    fun hasPermission(context: Context): Boolean {
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return coarse || fine
    }

    fun isPermanentlyDenied(activity: Activity): Boolean {
        if (hasPermission(activity)) return false
        if (!LocationPermissionStore.wasPermissionEverRequested(activity)) return false
        val fineRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarseRationale = ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
        return !fineRationale && !coarseRationale
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    @SuppressLint("MissingPermission")
    suspend fun fetchCurrentLocation(context: Context): Result<UserLocation> = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) {
            Log.w(TAG, "Location permission missing")
            return@withContext Result.failure(IllegalStateException("Location permission missing"))
        }

        val appContext = context.applicationContext
        val locationManager = appContext.getSystemService(LocationManager::class.java)
        val providersEnabled = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).any { provider ->
            runCatching { locationManager?.isProviderEnabled(provider) == true }.getOrDefault(false)
        }
        if (!providersEnabled) {
            Log.w(TAG, "GPS/network location disabled; trying cached provider location")
        }

        val fused = LocationServices.getFusedLocationProviderClient(appContext)
        val location = runCatching { fused.lastLocation.await() }.getOrNull()
            ?: runCatching {
                fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null).await()
            }.getOrNull()
            ?: getBestLastKnownLocation(locationManager)

        if (location == null) {
            Log.w(TAG, "Location fetch failed: lastLocation and currentLocation returned null")
            return@withContext Result.failure(IllegalStateException("Location unavailable"))
        }

        Log.d(TAG, "Location fetched")
        val resolved = reverseGeocode(appContext, location)
        Result.success(
            UserLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                address = resolved?.first.orEmpty(),
                city = resolved?.second.orEmpty(),
                source = UserLocation.SOURCE_GPS,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun getBestLastKnownLocation(locationManager: LocationManager?): Location? {
        locationManager ?: return null
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider -> runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() }
            .maxByOrNull { it.time }
    }

    private fun reverseGeocode(context: Context, location: Location): Pair<String, String>? {
        if (!Geocoder.isPresent()) {
            Log.w(TAG, "Reverse geocode fail: Geocoder unavailable")
            return null
        }
        return runCatching {
            val geocoder = Geocoder(context, Locale.getDefault())
            @Suppress("DEPRECATION")
            val address = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
            val formatted = formatAddress(address)
            if (formatted.first.isNotBlank()) {
                Log.d(TAG, "Reverse geocode success")
                formatted
            } else {
                Log.w(TAG, "Reverse geocode fail: empty address")
                null
            }
        }.onFailure {
            Log.w(TAG, "Reverse geocode fail", it)
        }.getOrNull()
    }

    private fun formatAddress(address: Address?): Pair<String, String> {
        address ?: return "" to ""
        val city = address.locality ?: address.subAdminArea ?: address.adminArea ?: ""
        val line = address.getAddressLine(0)
            ?: listOf(city, address.countryName).filter { !it.isNullOrBlank() }.joinToString(", ")
        return line.orEmpty() to city
    }
}
