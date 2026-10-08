package com.mindfullness.weather.platform

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import com.mindfullness.weather.domain.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** One-shot device location using the platform LocationManager (no Google Play Services needed). */
class DeviceLocationProvider(context: Context) {
    private val context = context.applicationContext

    fun hasPermission(): Boolean = granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Returns the current position as a [Place] named after the district, or null if unavailable. */
    suspend fun currentPlace(): Place? {
        val location = currentLocation() ?: return null
        val address = reverseGeocode(location.latitude, location.longitude)
        val name = address?.subAdminArea ?: address?.locality ?: address?.adminArea ?: "Mevcut konum"
        return Place(
            id = Place.CURRENT_LOCATION_ID,
            name = name,
            region = address?.adminArea?.takeIf { it != name },
            country = address?.countryName,
            countryCode = address?.countryCode,
            latitude = location.latitude,
            longitude = location.longitude,
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? {
        if (!hasPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                add(LocationManager.FUSED_PROVIDER)
            }
            if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION) && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
        }
        val lastKnown = providers
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        // A fix from the last 15 minutes is precise enough for a weather forecast.
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 15 * 60_000L) return lastKnown

        for (provider in providers) {
            val fix = withTimeoutOrNull(12_000) { requestSingleFix(manager, provider) }
            if (fix != null) return fix
        }
        return lastKnown
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleFix(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } else {
                // All callbacks are implemented explicitly: older platforms have no default methods.
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) continuation.resume(location)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onProviderDisabled(provider: String) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        }

    private suspend fun reverseGeocode(latitude: Double, longitude: Double): Address? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.forLanguageTag("tr-TR"))
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine<Address?> { continuation ->
                    geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
                }
            }
        } else {
            withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                runCatching { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }.getOrNull()
            }
        }
    }
}
