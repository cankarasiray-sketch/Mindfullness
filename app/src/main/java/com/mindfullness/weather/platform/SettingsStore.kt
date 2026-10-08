package com.mindfullness.weather.platform

import android.content.Context
import com.mindfullness.weather.data.PlaceJson
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.domain.Severity
import org.json.JSONArray
import org.json.JSONObject

data class AppSettings(
    val alertsEnabled: Boolean,
    val minSeverity: Severity,
    val morningSummary: Boolean,
    val notificationPlace: Place?,
)

/** Small SharedPreferences-backed store for places and notification preferences. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("hava_uyari", Context.MODE_PRIVATE)

    val places: List<Place>
        get() = prefs.getString(KEY_PLACES, null)?.let { json ->
            runCatching {
                val array = JSONArray(json)
                (0 until array.length()).map { PlaceJson.fromJson(array.getJSONObject(it)) }
            }.getOrNull()
        }.orEmpty()

    val settings: AppSettings
        get() = AppSettings(
            alertsEnabled = prefs.getBoolean(KEY_ALERTS, false),
            minSeverity = prefs.getString(KEY_MIN_SEVERITY, null)
                ?.let { name -> Severity.values().firstOrNull { it.name == name } } ?: Severity.YELLOW,
            morningSummary = prefs.getBoolean(KEY_MORNING, false),
            notificationPlace = readPlace(KEY_NOTIFY_PLACE),
        )

    /** [Place.CURRENT_LOCATION_ID], a saved place id, or null before the first choice. */
    var selectedId: Long?
        get() = if (prefs.contains(KEY_SELECTED)) prefs.getLong(KEY_SELECTED, 0) else null
        set(value) {
            val editor = prefs.edit()
            if (value == null) editor.remove(KEY_SELECTED) else editor.putLong(KEY_SELECTED, value)
            editor.apply()
        }

    /** Last resolved device location, used offline and by background jobs. */
    var lastCurrentPlace: Place?
        get() = readPlace(KEY_CURRENT_PLACE)
        set(value) = writePlace(KEY_CURRENT_PLACE, value)

    fun addPlace(place: Place) {
        writePlaces((listOf(place) + places.filter { it.id != place.id }).take(MAX_PLACES))
    }

    fun removePlace(id: Long) {
        writePlaces(places.filter { it.id != id })
        if (selectedId == id) selectedId = null
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings)
        prefs.edit()
            .putBoolean(KEY_ALERTS, next.alertsEnabled)
            .putString(KEY_MIN_SEVERITY, next.minSeverity.name)
            .putBoolean(KEY_MORNING, next.morningSummary)
            .apply()
        writePlace(KEY_NOTIFY_PLACE, next.notificationPlace)
    }

    /**
     * Records that [key] was notified. Returns false if it already was within the last few days,
     * so the same storm is not announced again on every background check.
     */
    @Synchronized
    fun markNotified(key: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val stored = prefs.getString(KEY_NOTIFIED, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        val fresh = JSONObject()
        stored.keys().forEach { k ->
            val at = stored.optLong(k)
            if (nowMillis - at < NOTIFIED_TTL_MILLIS) fresh.put(k, at)
        }
        if (fresh.has(key)) return false
        fresh.put(key, nowMillis)
        prefs.edit().putString(KEY_NOTIFIED, fresh.toString()).commit()
        return true
    }

    private fun readPlace(key: String): Place? =
        prefs.getString(key, null)?.let { runCatching { PlaceJson.fromJson(JSONObject(it)) }.getOrNull() }

    private fun writePlace(key: String, place: Place?) {
        val editor = prefs.edit()
        if (place == null) editor.remove(key) else editor.putString(key, PlaceJson.toJson(place).toString())
        editor.apply()
    }

    private fun writePlaces(places: List<Place>) {
        val array = JSONArray()
        places.forEach { array.put(PlaceJson.toJson(it)) }
        prefs.edit().putString(KEY_PLACES, array.toString()).apply()
    }

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
