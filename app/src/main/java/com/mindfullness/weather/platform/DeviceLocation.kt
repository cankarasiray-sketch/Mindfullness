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
import com.mindfullness.weather.domain.Place
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One-shot device location using the platform LocationManager (no Google Play Services needed). */
class DeviceLocationProvider(context: Context) {
    private val context = context.applicationContext

    fun hasPermission(): Boolean =
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Resolves the current position and its district name; [callback] runs on the main thread. */
    fun locate(callback: (Place?) -> Unit) {
        val manager = context.getSystemService(LocationManager::class.java)
        if (!hasPermission() || manager == null) {
            callback(null)
            return
        }
        val providers = enabledProviders(manager)
        val lastKnown = lastKnown(manager, providers)
        // A fix from the last 15 minutes is precise enough for a weather forecast.
        if (lastKnown != null && System.currentTimeMillis() - lastKnown.time < 15 * 60_000L) {
            describe(lastKnown, callback)
            return
        }
        requestFix(manager, providers, 0, lastKnown, callback)
    }

    private fun enabledProviders(manager: LocationManager): List<String> {
        val result = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isEnabled(manager, LocationManager.FUSED_PROVIDER)) {
            result.add(LocationManager.FUSED_PROVIDER)
        }
        if (isEnabled(manager, LocationManager.NETWORK_PROVIDER)) result.add(LocationManager.NETWORK_PROVIDER)
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) && isEnabled(manager, LocationManager.GPS_PROVIDER)) {
            result.add(LocationManager.GPS_PROVIDER)
        }
        return result
    }

    private fun isEnabled(manager: LocationManager, provider: String) =
        runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager, providers: List<String>): Location? =
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }

    /** Tries each provider in turn, giving each [TIMEOUT_MILLIS] to deliver a fix. */
    @SuppressLint("MissingPermission")
    private fun requestFix(
        manager: LocationManager,
        providers: List<String>,
        index: Int,
        fallback: Location?,
        callback: (Place?) -> Unit,
    ) {
        if (index >= providers.size) {
            if (fallback != null) describe(fallback, callback) else callback(null)
            return
        }
        val provider = providers[index]
        val done = AtomicBoolean(false)
        var cancel: () -> Unit = {}
        val timeout = Runnable {
            if (done.compareAndSet(false, true)) {
                cancel()
                requestFix(manager, providers, index + 1, fallback, callback)
            }
        }
        val onFix = { location: Location? ->
            if (done.compareAndSet(false, true)) {
                Async.main.removeCallbacks(timeout)
                if (location != null) describe(location, callback)
                else requestFix(manager, providers, index + 1, fallback, callback)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                cancel = { signal.cancel() }
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location -> onFix(location) }
            } else {
                // Every callback is overridden: these platforms have no default interface methods here.
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) = onFix(location)

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

                    override fun onProviderEnabled(provider: String) = Unit

                    override fun onProviderDisabled(provider: String) = onFix(null)
                }
                cancel = { manager.removeUpdates(listener) }
                @Suppress("DEPRECATION")
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
            Async.main.postDelayed(timeout, TIMEOUT_MILLIS)
        } catch (e: RuntimeException) {
            done.set(true)
            requestFix(manager, providers, index + 1, fallback, callback)
        }
    }

    /** Turns a fix into a [Place] named after the district (reverse geocoding off the main thread). */
    private fun describe(location: Location, callback: (Place?) -> Unit) {
        Async.run({ reverseGeocode(location.latitude, location.longitude) }) { result ->
            val address = result.getOrNull()
            val name = address?.subAdminArea ?: address?.locality ?: address?.adminArea ?: GENERIC_NAME
            callback(
                Place(
                    id = Place.CURRENT_LOCATION_ID,
                    name = name,
                    region = address?.adminArea?.takeIf { it != name },
                    country = address?.countryName,
                    countryCode = address?.countryCode,
                    latitude = location.latitude,
                    longitude = location.longitude,
                ),
            )
        }
    }

    /** Blocking; call from a background thread. */
    private fun reverseGeocode(latitude: Double, longitude: Double): Address? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.forLanguageTag("tr-TR"))
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            var address: Address? = null
            val latch = CountDownLatch(1)
            geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    address = addresses.firstOrNull()
                    latch.countDown()
                }

                override fun onError(errorMessage: String?) {
                    latch.countDown()
                }
            })
            latch.await(8, TimeUnit.SECONDS)
            address
        } else {
            @Suppress("DEPRECATION")
            runCatching { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }.getOrNull()
        }
    }

    companion object {
        /** Name used when reverse geocoding is unavailable (e.g. offline). */
        const val GENERIC_NAME = "Mevcut konum"
        private const val TIMEOUT_MILLIS = 12_000L
    }
}
