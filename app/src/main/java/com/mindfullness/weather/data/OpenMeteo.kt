package com.mindfullness.weather.data

import com.mindfullness.weather.domain.CurrentWeather
import com.mindfullness.weather.domain.DailyPoint
import com.mindfullness.weather.domain.Forecast
import com.mindfullness.weather.domain.HourlyPoint
import com.mindfullness.weather.domain.Place
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/** Thin blocking client for the free, key-less Open-Meteo forecast and geocoding APIs. */
class OpenMeteoApi {

    fun forecastJson(latitude: Double, longitude: Double): String {
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

    fun search(query: String): List<Place> {
        val name = URLEncoder.encode(query.trim(), "UTF-8")
        val body = get("https://geocoding-api.open-meteo.com/v1/search?name=$name&count=15&language=tr&format=json")
        return GeocodingParser.parse(body)
    }

    private fun get(url: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
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
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
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

object GeocodingParser {
    fun parse(json: String): List<Place> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            val name = item.stringOrNull("name") ?: return@mapNotNull null
            val admin1 = item.stringOrNull("admin1")
            val admin2 = item.stringOrNull("admin2")
            Place(
                id = item.optLong("id"),
                name = name,
                region = listOfNotNull(admin2?.takeIf { it != name }, admin1).joinToString(", ").ifBlank { null },
                country = item.stringOrNull("country"),
                countryCode = item.stringOrNull("country_code"),
                latitude = item.getDouble("latitude"),
                longitude = item.getDouble("longitude"),
            )
        }
    }
}

object ForecastParser {
    fun parse(json: String, fetchedAtMillis: Long): Forecast {
        val root = JSONObject(json)
        val current = root.getJSONObject("current")
        val hourly = root.getJSONObject("hourly")
        val daily = root.getJSONObject("daily")

        val hourTimes = hourly.strings("time")
        val temperature = hourly.doubles("temperature_2m")
        val apparent = hourly.doubles("apparent_temperature")
        val humidity = hourly.doubles("relative_humidity_2m")
        val probability = hourly.doubles("precipitation_probability")
        val precipitation = hourly.doubles("precipitation")
        val rain = hourly.doubles("rain")
        val showers = hourly.doubles("showers")
        val snowfall = hourly.doubles("snowfall")
        val code = hourly.doubles("weather_code")
        val windSpeed = hourly.doubles("wind_speed_10m")
        val gusts = hourly.doubles("wind_gusts_10m")
        val direction = hourly.doubles("wind_direction_10m")
        val visibility = hourly.doubles("visibility")
        val uv = hourly.doubles("uv_index")
        val isDay = hourly.doubles("is_day")

        val hourlyPoints = hourTimes.indices.mapNotNull { i ->
            val time = hourTimes[i] ?: return@mapNotNull null
            val t = temperature.at(i) ?: return@mapNotNull null
            HourlyPoint(
                time = LocalDateTime.parse(time),
                temperature = t,
                apparentTemperature = apparent.at(i) ?: t,
                humidity = (humidity.at(i) ?: 0.0).toInt(),
                precipitationProbability = probability.at(i)?.toInt(),
                precipitation = precipitation.at(i) ?: 0.0,
                rain = rain.at(i) ?: 0.0,
                showers = showers.at(i) ?: 0.0,
                snowfall = snowfall.at(i) ?: 0.0,
                weatherCode = code.at(i)?.toInt() ?: 0,
                windSpeed = windSpeed.at(i) ?: 0.0,
                windGusts = gusts.at(i) ?: 0.0,
                windDirection = direction.at(i) ?: 0.0,
                visibility = visibility.at(i),
                uvIndex = uv.at(i),
                isDay = (isDay.at(i) ?: 1.0) >= 0.5,
            )
        }

        val dayTimes = daily.strings("time")
        val dCode = daily.doubles("weather_code")
        val tMax = daily.doubles("temperature_2m_max")
        val tMin = daily.doubles("temperature_2m_min")
        val aMax = daily.doubles("apparent_temperature_max")
        val aMin = daily.doubles("apparent_temperature_min")
        val sunrise = daily.strings("sunrise")
        val sunset = daily.strings("sunset")
        val uvMax = daily.doubles("uv_index_max")
        val pSum = daily.doubles("precipitation_sum")
        val pProb = daily.doubles("precipitation_probability_max")
        val pHours = daily.doubles("precipitation_hours")
        val sSum = daily.doubles("snowfall_sum")
        val wMax = daily.doubles("wind_speed_10m_max")
        val gMax = daily.doubles("wind_gusts_10m_max")
        val dDir = daily.doubles("wind_direction_10m_dominant")

        val dailyPoints = dayTimes.indices.mapNotNull { i ->
            val date = dayTimes[i] ?: return@mapNotNull null
            val max = tMax.at(i) ?: return@mapNotNull null
            val min = tMin.at(i) ?: return@mapNotNull null
            DailyPoint(
                date = LocalDate.parse(date),
                weatherCode = dCode.at(i)?.toInt() ?: 0,
                temperatureMax = max,
                temperatureMin = min,
                apparentMax = aMax.at(i) ?: max,
                apparentMin = aMin.at(i) ?: min,
                sunrise = sunrise.getOrNull(i)?.let(LocalDateTime::parse),
                sunset = sunset.getOrNull(i)?.let(LocalDateTime::parse),
                uvIndexMax = uvMax.at(i),
                precipitationSum = pSum.at(i) ?: 0.0,
                precipitationProbabilityMax = pProb.at(i)?.toInt(),
                precipitationHours = pHours.at(i) ?: 0.0,
                snowfallSum = sSum.at(i) ?: 0.0,
                windSpeedMax = wMax.at(i) ?: 0.0,
                windGustsMax = gMax.at(i) ?: 0.0,
                windDirectionDominant = dDir.at(i),
            )
        }

        val currentTemperature = current.number("temperature_2m") ?: hourlyPoints.firstOrNull()?.temperature ?: 0.0
        return Forecast(
            fetchedAtMillis = fetchedAtMillis,
            utcOffsetSeconds = root.optInt("utc_offset_seconds", 0),
            current = CurrentWeather(
                time = LocalDateTime.parse(current.getString("time")),
                temperature = currentTemperature,
                apparentTemperature = current.number("apparent_temperature") ?: currentTemperature,
                humidity = current.number("relative_humidity_2m")?.toInt() ?: 0,
                isDay = (current.number("is_day") ?: 1.0) >= 0.5,
                precipitation = current.number("precipitation") ?: 0.0,
                weatherCode = current.number("weather_code")?.toInt() ?: 0,
                cloudCover = current.number("cloud_cover")?.toInt() ?: 0,
                pressure = current.number("pressure_msl") ?: 0.0,
                windSpeed = current.number("wind_speed_10m") ?: 0.0,
                windDirection = current.number("wind_direction_10m") ?: 0.0,
                windGusts = current.number("wind_gusts_10m") ?: 0.0,
            ),
            hourly = hourlyPoints,
            daily = dailyPoints,
        )
    }
}

// region org.json helpers --------------------------------------------------------------------

private fun List<Double?>.at(index: Int): Double? = getOrNull(index)

internal fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

private fun JSONObject.number(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null

private fun JSONObject.doubles(key: String): List<Double?> {
    val array: JSONArray = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map { i -> if (array.isNull(i)) null else array.optDouble(i).takeUnless { it.isNaN() } }
}

private fun JSONObject.strings(key: String): List<String?> {
    val array: JSONArray = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map { i -> if (array.isNull(i)) null else array.optString(i) }
}

// endregion
