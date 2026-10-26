package com.mindfullness.weather.data

import com.mindfullness.weather.domain.Forecast
import com.mindfullness.weather.domain.Place
import com.mindfullness.weather.domain.PollenBulletins
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
    private val bulletins: PollenBulletinApi = PollenBulletinApi(),
) {
    // Unbounded: with a fixed pool, extras of several places fetched at once would queue past their timeout.
    private val extras = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }

    fun fetch(place: Place): Forecast {
        // Model comparison, the ensemble and air quality are extras: they load alongside and may
        // fail on their own.
        val models = optional { api.modelsJson(place.latitude, place.longitude) }
        val ensemble = optional { api.ensembleJson(place.latitude, place.longitude) }
        val air = optional { api.airQualityJson(place.latitude, place.longitude) }
        // The city bulletin changes once a day, so a recent copy is reused rather than fetched hourly.
        val bulletin = PollenBulletins.near(place.latitude, place.longitude)
        val now = System.currentTimeMillis()
        val previous = bulletin?.let { city -> cachedPayload(place)?.bulletin?.takeIf { it.id == city.id } }
        val page = if (bulletin == null || (previous != null && now - previous.fetchedAt < BULLETIN_FRESH_MILLIS)) null
        else optional { bulletins.page(bulletin) }
        val forecastJson = api.forecastJson(place.latitude, place.longitude)
        val bulletinPage = page?.result()?.let { BulletinPage(bulletin!!.id, it, now) } ?: previous
        val payload = Payload(now, forecastJson, models.result(), air.result(), ensemble.result(), bulletinPage)
        val forecast = payload.parse()
        runCatching {
            cacheDir.mkdirs()
            fileFor(place).writeText(payload.toJson())
        }
        return forecast
    }

    fun cached(place: Place): Forecast? = runCatching { cachedPayload(place)?.parse() }.getOrNull()

    private fun cachedPayload(place: Place): Payload? = runCatching {
        val file = fileFor(place)
        if (file.exists()) Payload.fromCache(file.readText()) else null
    }.getOrNull()

    fun search(query: String): List<Place> = api.search(query)

    private fun optional(request: () -> String): Future<String?> = extras.submit<String?> { runCatching(request).getOrNull() }

    private fun Future<String?>.result(): String? = runCatching { get(25, TimeUnit.SECONDS) }.getOrNull()

    private companion object {
        const val BULLETIN_FRESH_MILLIS = 3 * 60 * 60 * 1000L
    }

    private fun fileFor(place: Place): File {
        val key = if (place.isCurrentLocation) "current" else
            String.format(Locale.ROOT, "%d_%.3f_%.3f", place.id, place.latitude, place.longitude)
        return File(cacheDir, "forecast_$key.json")
    }

    /** A city bulletin's page as downloaded; kept raw so a parser fix also reads older copies. */
    private class BulletinPage(val id: String, val html: String, val fetchedAt: Long) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("html", html).put("fetchedAt", fetchedAt)

        companion object {
            fun fromJson(json: JSONObject) = BulletinPage(json.getString("id"), json.getString("html"), json.getLong("fetchedAt"))
        }
    }

    /** The raw API responses of one fetch, as stored in the cache. */
    private class Payload(
        val fetchedAt: Long,
        val forecast: String,
        val models: String?,
        val air: String?,
        val ensemble: String?,
        val bulletin: BulletinPage? = null,
    ) {
        fun parse(): Forecast = ForecastParser.parse(forecast, fetchedAt).copy(
            models = models?.let { runCatching { ModelsParser.parse(it) }.getOrNull() }.orEmpty(),
            air = air?.let { runCatching { AirQualityParser.parse(it) }.getOrNull() }.orEmpty(),
            ensemble = ensemble?.let { runCatching { EnsembleParser.parse(it) }.getOrNull() }.orEmpty(),
            bulletin = bulletin?.let { page -> PollenBulletins.byId(page.id)?.let { BulletinParser.parse(it, page.html, page.fetchedAt) } },
        )

        fun toJson(): String = JSONObject()
            .put("fetchedAt", fetchedAt)
            .put("forecast", forecast)
            .putOpt("models", models)
            .putOpt("air", air)
            .putOpt("ensemble", ensemble)
            .putOpt("bulletin", bulletin?.toJson())
            .toString()

        companion object {
            fun fromCache(text: String): Payload {
                if (!text.startsWith("{")) {
                    // Format of version 1.0: "<fetchedAt>\n<forecast json>".
                    return Payload(text.substringBefore('\n').toLong(), text.substringAfter('\n'), null, null, null)
                }
                val json = JSONObject(text)
                return Payload(
                    json.getLong("fetchedAt"), json.getString("forecast"), json.stringOrNull("models"),
                    json.stringOrNull("air"), json.stringOrNull("ensemble"),
                    json.optJSONObject("bulletin")?.let { runCatching { BulletinPage.fromJson(it) }.getOrNull() },
                )
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
