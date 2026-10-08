package com.mindfullness.weather.platform

import android.content.Context
import androidx.core.content.edit
import com.mindfullness.weather.data.AppJson
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.domain.Severity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

data class AppSettings(
    val alertsEnabled: Boolean,
    val minSeverity: Severity,
    val morningSummary: Boolean,
    val notificationPlace: Place?,
)

/** Small SharedPreferences-backed store for places and notification preferences. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hava_uyari", Context.MODE_PRIVATE)

    private val _places = MutableStateFlow(readPlaces())
    val places: StateFlow<List<Place>> = _places.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /** [Place.CURRENT_LOCATION_ID], a saved place id, or null before the first choice. */
    var selectedId: Long?
        get() = if (prefs.contains(KEY_SELECTED)) prefs.getLong(KEY_SELECTED, 0) else null
        set(value) = prefs.edit { if (value == null) remove(KEY_SELECTED) else putLong(KEY_SELECTED, value) }

    /** Last resolved device location, used offline and by the background worker. */
    var lastCurrentPlace: Place?
        get() = prefs.getString(KEY_CURRENT_PLACE, null)?.let { decodePlace(it) }
        set(value) = prefs.edit {
            if (value == null) remove(KEY_CURRENT_PLACE) else putString(KEY_CURRENT_PLACE, AppJson.encodeToString(Place.serializer(), value))
        }

    fun addPlace(place: Place) {
        val updated = (listOf(place) + _places.value.filter { it.id != place.id }).take(MAX_PLACES)
        writePlaces(updated)
    }

    fun removePlace(id: Long) {
        writePlaces(_places.value.filter { it.id != id })
        if (selectedId == id) selectedId = null
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(_settings.value)
        prefs.edit {
            putBoolean(KEY_ALERTS, next.alertsEnabled)
            putString(KEY_MIN_SEVERITY, next.minSeverity.name)
            putBoolean(KEY_MORNING, next.morningSummary)
            val place = next.notificationPlace
            if (place == null) remove(KEY_NOTIFY_PLACE) else putString(KEY_NOTIFY_PLACE, AppJson.encodeToString(Place.serializer(), place))
        }
        _settings.value = next
    }

    /**
     * Records that [key] was notified. Returns false if it already was within the last few days,
     * so the same storm is not announced again on every background check.
     */
    fun markNotified(key: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val serializer = MapSerializer(String.serializer(), Long.serializer())
        val existing = prefs.getString(KEY_NOTIFIED, null)
            ?.let { runCatching { AppJson.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()
            .filterValues { nowMillis - it < NOTIFIED_TTL_MILLIS }
        if (key in existing) return false
        prefs.edit { putString(KEY_NOTIFIED, AppJson.encodeToString(serializer, existing + (key to nowMillis))) }
        return true
    }

    private fun readSettings() = AppSettings(
        alertsEnabled = prefs.getBoolean(KEY_ALERTS, false),
        minSeverity = prefs.getString(KEY_MIN_SEVERITY, null)
            ?.let { name -> Severity.entries.firstOrNull { it.name == name } } ?: Severity.YELLOW,
        morningSummary = prefs.getBoolean(KEY_MORNING, false),
        notificationPlace = prefs.getString(KEY_NOTIFY_PLACE, null)?.let { decodePlace(it) },
    )

    private fun readPlaces(): List<Place> = prefs.getString(KEY_PLACES, null)
        ?.let { runCatching { AppJson.decodeFromString(ListSerializer(Place.serializer()), it) }.getOrNull() }
        .orEmpty()

    private fun writePlaces(places: List<Place>) {
        prefs.edit { putString(KEY_PLACES, AppJson.encodeToString(ListSerializer(Place.serializer()), places)) }
        _places.value = places
    }

    private fun decodePlace(json: String): Place? = runCatching { AppJson.decodeFromString(Place.serializer(), json) }.getOrNull()

    private companion object {
        const val KEY_PLACES = "places"
        const val KEY_SELECTED = "selected_place"
        const val KEY_CURRENT_PLACE = "current_place"
        const val KEY_ALERTS = "alerts_enabled"
        const val KEY_MIN_SEVERITY = "min_severity"
        const val KEY_MORNING = "morning_summary"
        const val KEY_NOTIFY_PLACE = "notification_place"
        const val KEY_NOTIFIED = "notified_keys"
        const val MAX_PLACES = 15
        const val NOTIFIED_TTL_MILLIS = 3 * 24 * 60 * 60 * 1000L
    }
}
