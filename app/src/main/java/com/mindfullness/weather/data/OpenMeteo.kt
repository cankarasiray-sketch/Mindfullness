package com.mindfullness.weather.data

import com.mindfullness.weather.domain.CurrentWeather
import com.mindfullness.weather.domain.DailyPoint
import com.mindfullness.weather.domain.Forecast
import com.mindfullness.weather.domain.HourlyPoint
import com.mindfullness.weather.domain.Place
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

val AppJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/** Thin client for the free, key-less Open-Meteo forecast and geocoding APIs. */
class OpenMeteoApi {

    suspend fun forecastJson(latitude: Double, longitude: Double): String {
        val url = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=").append(coordinate(latitude))
            append("&longitude=").append(coordinate(longitude))
            append("&current=").append(CURRENT.joinToString(","))
            append("&hourly=").append(HOURLY.joinToString(","))
            append("&daily=").append(DAILY.joinToString(","))
            append("&timezone=auto&forecast_days=10&wind_speed_unit=kmh")
        }
        return get(url)
    }

    suspend fun search(query: String): List<Place> {
        val name = URLEncoder.encode(query.trim(), "UTF-8")
        val body = get("https://geocoding-api.open-meteo.com/v1/search?name=$name&count=15&language=tr&format=json")
        return AppJson.decodeFromString<GeocodingResponse>(body).results.map { it.toPlace() }
    }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "HavaUyari/1.0 (Android)")
            val code = connection.responseCode
            if (code !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException("HTTP $code ${error.take(200)}")
            }
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun coordinate(value: Double) = String.format(Locale.ROOT, "%.4f", value)

    companion object {
        private val CURRENT = listOf(
            "temperature_2m", "relative_humidity_2m", "apparent_temperature", "is_day", "precipitation",
            "weather_code", "cloud_cover", "pressure_msl", "wind_speed_10m", "wind_direction_10m", "wind_gusts_10m",
        )
        private val HOURLY = listOf(
            "temperature_2m", "apparent_temperature", "relative_humidity_2m", "precipitation_probability",
            "precipitation", "rain", "showers", "snowfall", "weather_code", "wind_speed_10m", "wind_gusts_10m",
            "wind_direction_10m", "visibility", "uv_index", "is_day",
        )
        private val DAILY = listOf(
            "weather_code", "temperature_2m_max", "temperature_2m_min", "apparent_temperature_max",
            "apparent_temperature_min", "sunrise", "sunset", "uv_index_max", "precipitation_sum",
            "precipitation_probability_max", "precipitation_hours", "snowfall_sum", "wind_speed_10m_max",
            "wind_gusts_10m_max", "wind_direction_10m_dominant",
        )
    }
}

object ForecastParser {
    fun parse(json: String, fetchedAtMillis: Long): Forecast =
        AppJson.decodeFromString<ForecastResponse>(json).toForecast(fetchedAtMillis)
}

// region DTOs ----------------------------------------------------------------------------

@Serializable
internal data class GeocodingResponse(val results: List<GeoResult> = emptyList())

@Serializable
internal data class GeoResult(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val country: String? = null,
    @SerialName("country_code") val countryCode: String? = null,
    val admin1: String? = null,
    val admin2: String? = null,
) {
    fun toPlace() = Place(
        id = id,
        name = name,
        region = listOfNotNull(admin2?.takeIf { it != name }, admin1).joinToString(", ").ifBlank { null },
        country = country,
        countryCode = countryCode,
        latitude = latitude,
        longitude = longitude,
    )
}

@Serializable
internal data class ForecastResponse(
    @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
    val current: CurrentDto,
    val hourly: HourlyDto,
    val daily: DailyDto,
)

@Serializable
internal data class CurrentDto(
    val time: String,
    @SerialName("temperature_2m") val temperature: Double? = null,
    @SerialName("relative_humidity_2m") val humidity: Double? = null,
    @SerialName("apparent_temperature") val apparentTemperature: Double? = null,
    @SerialName("is_day") val isDay: Double? = null,
    val precipitation: Double? = null,
    @SerialName("weather_code") val weatherCode: Double? = null,
    @SerialName("cloud_cover") val cloudCover: Double? = null,
    @SerialName("pressure_msl") val pressure: Double? = null,
    @SerialName("wind_speed_10m") val windSpeed: Double? = null,
    @SerialName("wind_direction_10m") val windDirection: Double? = null,
    @SerialName("wind_gusts_10m") val windGusts: Double? = null,
)

@Serializable
internal data class HourlyDto(
    val time: List<String>,
    @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
    @SerialName("apparent_temperature") val apparentTemperature: List<Double?> = emptyList(),
    @SerialName("relative_humidity_2m") val humidity: List<Double?> = emptyList(),
    @SerialName("precipitation_probability") val precipitationProbability: List<Double?> = emptyList(),
    val precipitation: List<Double?> = emptyList(),
    val rain: List<Double?> = emptyList(),
    val showers: List<Double?> = emptyList(),
    val snowfall: List<Double?> = emptyList(),
    @SerialName("weather_code") val weatherCode: List<Double?> = emptyList(),
    @SerialName("wind_speed_10m") val windSpeed: List<Double?> = emptyList(),
    @SerialName("wind_gusts_10m") val windGusts: List<Double?> = emptyList(),
    @SerialName("wind_direction_10m") val windDirection: List<Double?> = emptyList(),
    val visibility: List<Double?> = emptyList(),
    @SerialName("uv_index") val uvIndex: List<Double?> = emptyList(),
    @SerialName("is_day") val isDay: List<Double?> = emptyList(),
)

@Serializable
internal data class DailyDto(
    val time: List<String>,
    @SerialName("weather_code") val weatherCode: List<Double?> = emptyList(),
    @SerialName("temperature_2m_max") val temperatureMax: List<Double?> = emptyList(),
    @SerialName("temperature_2m_min") val temperatureMin: List<Double?> = emptyList(),
    @SerialName("apparent_temperature_max") val apparentMax: List<Double?> = emptyList(),
    @SerialName("apparent_temperature_min") val apparentMin: List<Double?> = emptyList(),
    val sunrise: List<String?> = emptyList(),
    val sunset: List<String?> = emptyList(),
    @SerialName("uv_index_max") val uvIndexMax: List<Double?> = emptyList(),
    @SerialName("precipitation_sum") val precipitationSum: List<Double?> = emptyList(),
    @SerialName("precipitation_probability_max") val precipitationProbabilityMax: List<Double?> = emptyList(),
    @SerialName("precipitation_hours") val precipitationHours: List<Double?> = emptyList(),
    @SerialName("snowfall_sum") val snowfallSum: List<Double?> = emptyList(),
    @SerialName("wind_speed_10m_max") val windSpeedMax: List<Double?> = emptyList(),
    @SerialName("wind_gusts_10m_max") val windGustsMax: List<Double?> = emptyList(),
    @SerialName("wind_direction_10m_dominant") val windDirectionDominant: List<Double?> = emptyList(),
)

// endregion
// region Mapping -------------------------------------------------------------------------

private fun <T> List<T?>.at(index: Int): T? = getOrNull(index)

private fun List<Double?>.num(index: Int, fallback: Double = 0.0): Double = at(index) ?: fallback

internal fun ForecastResponse.toForecast(fetchedAtMillis: Long): Forecast {
    val hourlyPoints = hourly.time.indices.mapNotNull { i ->
        val temperature = hourly.temperature.at(i) ?: return@mapNotNull null
        HourlyPoint(
            time = LocalDateTime.parse(hourly.time[i]),
            temperature = temperature,
            apparentTemperature = hourly.apparentTemperature.num(i, temperature),
            humidity = hourly.humidity.num(i).toInt(),
            precipitationProbability = hourly.precipitationProbability.at(i)?.toInt(),
            precipitation = hourly.precipitation.num(i),
            rain = hourly.rain.num(i),
            showers = hourly.showers.num(i),
            snowfall = hourly.snowfall.num(i),
            weatherCode = hourly.weatherCode.at(i)?.toInt() ?: 0,
            windSpeed = hourly.windSpeed.num(i),
            windGusts = hourly.windGusts.num(i),
            windDirection = hourly.windDirection.num(i),
            visibility = hourly.visibility.at(i),
            uvIndex = hourly.uvIndex.at(i),
            isDay = (hourly.isDay.at(i) ?: 1.0) >= 0.5,
        )
    }
    val dailyPoints = daily.time.indices.mapNotNull { i ->
        val max = daily.temperatureMax.at(i) ?: return@mapNotNull null
        val min = daily.temperatureMin.at(i) ?: return@mapNotNull null
        DailyPoint(
            date = LocalDate.parse(daily.time[i]),
            weatherCode = daily.weatherCode.at(i)?.toInt() ?: 0,
            temperatureMax = max,
            temperatureMin = min,
            apparentMax = daily.apparentMax.num(i, max),
            apparentMin = daily.apparentMin.num(i, min),
            sunrise = daily.sunrise.at(i)?.let(LocalDateTime::parse),
            sunset = daily.sunset.at(i)?.let(LocalDateTime::parse),
            uvIndexMax = daily.uvIndexMax.at(i),
            precipitationSum = daily.precipitationSum.num(i),
            precipitationProbabilityMax = daily.precipitationProbabilityMax.at(i)?.toInt(),
            precipitationHours = daily.precipitationHours.num(i),
            snowfallSum = daily.snowfallSum.num(i),
            windSpeedMax = daily.windSpeedMax.num(i),
            windGustsMax = daily.windGustsMax.num(i),
            windDirectionDominant = daily.windDirectionDominant.at(i),
        )
    }
    val currentTemperature = current.temperature ?: hourlyPoints.firstOrNull()?.temperature ?: 0.0
    return Forecast(
        fetchedAtMillis = fetchedAtMillis,
        utcOffsetSeconds = utcOffsetSeconds,
        current = CurrentWeather(
            time = LocalDateTime.parse(current.time),
            temperature = currentTemperature,
            apparentTemperature = current.apparentTemperature ?: currentTemperature,
            humidity = current.humidity?.toInt() ?: 0,
            isDay = (current.isDay ?: 1.0) >= 0.5,
            precipitation = current.precipitation ?: 0.0,
            weatherCode = current.weatherCode?.toInt() ?: 0,
            cloudCover = current.cloudCover?.toInt() ?: 0,
            pressure = current.pressure ?: 0.0,
            windSpeed = current.windSpeed ?: 0.0,
            windDirection = current.windDirection ?: 0.0,
            windGusts = current.windGusts ?: 0.0,
        ),
        hourly = hourlyPoints,
        daily = dailyPoints,
    )
}

// endregion
