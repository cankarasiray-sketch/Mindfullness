package com.mindfullness.weather.data

import com.mindfullness.weather.domain.Forecast
import com.mindfullness.weather.domain.Place
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Fetches forecasts and keeps the last successful response per place on disk, so the app opens
 * instantly and still shows something useful when offline. All calls block; run them off the
 * main thread.
 */
class WeatherRepository(
    private val api: OpenMeteoApi,
    private val cacheDir: File,
) {
    private val extras = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable).apply { isDaemon = true } }

    fun fetch(place: Place): Forecast {
        // Model comparison and air quality are extras: they load alongside and may fail on their own.
        val models = optional { api.modelsJson(place.latitude, place.longitude) }
        val air = optional { api.airQualityJson(place.latitude, place.longitude) }
        val forecastJson = api.forecastJson(place.latitude, place.longitude)
        val payload = Payload(System.currentTimeMillis(), forecastJson, models.result(), air.result())
        val forecast = payload.parse()
        runCatching {
            cacheDir.mkdirs()
            fileFor(place).writeText(payload.toJson())
        }
        return forecast
    }

    fun cached(place: Place): Forecast? = runCatching {
        val file = fileFor(place)
        if (!file.exists()) return null
        Payload.fromCache(file.readText()).parse()
    }.getOrNull()

    fun search(query: String): List<Place> = api.search(query)

    private fun optional(request: () -> String): Future<String?> = extras.submit<String?> { runCatching(request).getOrNull() }

    private fun Future<String?>.result(): String? = runCatching { get(25, TimeUnit.SECONDS) }.getOrNull()

    private fun fileFor(place: Place): File {
        val key = if (place.isCurrentLocation) "current" else
            String.format(Locale.ROOT, "%d_%.3f_%.3f", place.id, place.latitude, place.longitude)
        return File(cacheDir, "forecast_$key.json")
    }

    /** The raw API responses of one fetch, as stored in the cache. */
    private class Payload(val fetchedAt: Long, val forecast: String, val models: String?, val air: String?) {
        fun parse(): Forecast = ForecastParser.parse(forecast, fetchedAt).copy(
            models = models?.let { runCatching { ModelsParser.parse(it) }.getOrNull() }.orEmpty(),
            air = air?.let { runCatching { AirQualityParser.parse(it) }.getOrNull() }.orEmpty(),
        )

        fun toJson(): String = JSONObject()
            .put("fetchedAt", fetchedAt)
            .put("forecast", forecast)
            .putOpt("models", models)
            .putOpt("air", air)
            .toString()

        companion object {
            fun fromCache(text: String): Payload {
                if (!text.startsWith("{")) {
                    // Format of version 1.0: "<fetchedAt>\n<forecast json>".
                    return Payload(text.substringBefore('\n').toLong(), text.substringAfter('\n'), null, null)
                }
                val json = JSONObject(text)
                return Payload(json.getLong("fetchedAt"), json.getString("forecast"), json.stringOrNull("models"), json.stringOrNull("air"))
            }
        }
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
