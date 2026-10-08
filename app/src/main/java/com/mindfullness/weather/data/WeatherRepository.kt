package com.mindfullness.weather.data

import com.mindfullness.weather.domain.Forecast
import com.mindfullness.weather.domain.Place
import java.io.File
import java.util.Locale

/**
 * Fetches forecasts and keeps the last successful response per place on disk, so the app opens
 * instantly and still shows something useful when offline.
 */
class WeatherRepository(
    private val api: OpenMeteoApi,
    private val cacheDir: File,
) {
    suspend fun fetch(place: Place): Forecast {
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

    suspend fun search(query: String): List<Place> = api.search(query)

    private fun fileFor(place: Place): File {
        val key = if (place.isCurrentLocation) "current" else
            String.format(Locale.ROOT, "%d_%.3f_%.3f", place.id, place.latitude, place.longitude)
        return File(cacheDir, "forecast_$key.json")
    }
}
