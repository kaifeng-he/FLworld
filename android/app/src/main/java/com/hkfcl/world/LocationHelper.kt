package com.hkfcl.world

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

data class UploadLocation(val latitude: Double, val longitude: Double, val province: String, val city: String)

class LocationHelper(private val context: Context) {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasLocationServiceEnabled(): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
        return runCatching {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
                manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }.getOrDefault(true)
    }

    @SuppressLint("MissingPermission")
    suspend fun currentCoarseLocation(): UploadLocation? {
        if (!hasPermission()) return null
        if (!hasLocationServiceEnabled()) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val location = currentFusedLocation()
            ?: runCatching { client.lastLocation.await() }.getOrNull()
            ?: lastKnownSystemLocation()
            ?: return null
        val address = withContext(Dispatchers.IO) {
            runCatching {
                @Suppress("DEPRECATION")
                Geocoder(context, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull()
            }.getOrNull()
        }
        return UploadLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            province = address?.adminArea.orEmpty(),
            city = address?.locality ?: address?.subAdminArea.orEmpty()
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentFusedLocation(): Location? {
        val tokenSource = CancellationTokenSource()
        return try {
            val client = LocationServices.getFusedLocationProviderClient(context)
            withTimeoutOrNull(12_000) {
                client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, tokenSource.token).await()
            }
        } catch (_: SecurityException) {
            null
        } finally {
            tokenSource.cancel()
        }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownSystemLocation(): Location? {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        return providers.mapNotNull { provider ->
            runCatching {
                if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
            }.getOrNull()
        }.maxByOrNull { it.time }
    }
}
