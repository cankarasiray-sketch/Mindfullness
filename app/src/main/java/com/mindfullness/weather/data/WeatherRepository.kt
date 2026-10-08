package com.mindfullness.weather.data

import com.mindfullness.weather.domain.Forecast
import com.mindfullness.weather.domain.Place
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Fetches forecasts and keeps the last successful response per place on disk, so the app opens
 * instantly and still shows something useful when offline. All calls block; run them off the
 * main thread.
 */
class WeatherRepository(
    private val api: OpenMeteoApi,
    private val cacheDir: File,
) {
    fun fetch(place: Place): Forecast {
        val json = api.forecastJson(place.latitude, place.longitude)
        val now = System.currentTimeMillis()
        val forecast = ForecastParser.parse(json, now)
        runCatching {
            cacheDir.mkdirs()
            fileFor(place).writeText("$now\n$json")
        }
        return forecast
    }

    fun cached(place: Place): Forecast? = runCatching {
        val file = fileFor(place)
        if (!file.exists()) return null
        val text = file.readText()
        val fetchedAt = text.substringBefore('\n').toLong()
        ForecastParser.parse(text.substringAfter('\n'), fetchedAt)
    }.getOrNull()

    fun search(query: String): List<Place> = api.search(query)

    private fun fileFor(place: Place): File {
        val key = if (place.isCurrentLocation) "current" else
            String.format(Locale.ROOT, "%d_%.3f_%.3f", place.id, place.latitude, place.longitude)
        return File(cacheDir, "forecast_$key.json")
    }
}

object PlaceJson {
    fun toJson(place: Place): JSONObject = JSONObject()
        .put("id", place.id)
        .put("name", place.name)
        .putOpt("region", place.region)
        .putOpt("country", place.country)
        .putOpt("countryCode", place.countryCode)
        .put("latitude", place.latitude)
        .put("longitude", place.longitude)

    fun fromJson(json: JSONObject): Place = Place(
        id = json.getLong("id"),
        name = json.getString("name"),
        region = json.stringOrNull("region"),
        country = json.stringOrNull("country"),
        countryCode = json.stringOrNull("countryCode"),
        latitude = json.getDouble("latitude"),
        longitude = json.getDouble("longitude"),
    )
}
